package org.tasks.ui

import android.content.Context
import android.net.Uri
import androidx.annotation.MainThread
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.todoroo.astrid.activity.TaskEditFragment
import com.todoroo.astrid.alarms.AlarmService
import org.tasks.filters.CaldavFilter
import com.todoroo.astrid.api.GtasksFilter
import com.todoroo.astrid.dao.TaskDao
import com.todoroo.astrid.gcal.GCalHelper
import com.todoroo.astrid.service.TaskCompleter
import com.todoroo.astrid.service.TaskCreator.Companion.getDefaultAlarms
import com.todoroo.astrid.service.TaskDeleter
import com.todoroo.astrid.service.TaskMover
import com.todoroo.astrid.timers.TimerPlugin
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.tasks.R
import org.tasks.Strings
import org.tasks.analytics.Firebase
import org.tasks.calendars.CalendarEventProvider
import org.tasks.data.Location
import org.tasks.data.createDueDate
import org.tasks.data.dao.AlarmDao
import org.tasks.data.dao.CaldavDao
import org.tasks.data.dao.GoogleTaskDao
import org.tasks.data.dao.LocationDao
import org.tasks.data.dao.TagDao
import org.tasks.data.dao.TagDataDao
import org.tasks.data.dao.TaskAttachmentDao
import org.tasks.data.dao.UserActivityDao
import org.tasks.data.entity.Alarm
import org.tasks.data.entity.Alarm.Companion.TYPE_REL_END
import org.tasks.data.entity.Alarm.Companion.TYPE_REL_START
import org.tasks.data.entity.Alarm.Companion.whenDue
import org.tasks.data.entity.Alarm.Companion.whenOverdue
import org.tasks.data.entity.Alarm.Companion.whenStarted
import org.tasks.data.entity.Attachment
import org.tasks.data.entity.CaldavTask
import org.tasks.data.entity.FORCE_CALDAV_SYNC
import org.tasks.data.entity.TagData
import org.tasks.data.entity.Task
import org.tasks.data.entity.Task.Companion.NOTIFY_MODE_FIVE
import org.tasks.data.entity.Task.Companion.NOTIFY_MODE_NONSTOP
import org.tasks.data.entity.Task.Companion.hasDueTime
import org.tasks.data.entity.TaskAttachment
import org.tasks.data.entity.UserActivity
import org.tasks.data.setPicture
import org.tasks.date.DateTimeUtils.toDateTime
import org.tasks.files.FileHelper
import org.tasks.filters.Filter
import org.tasks.location.GeofenceApi
import org.tasks.preferences.PermissionChecker
import org.tasks.preferences.Preferences
import org.tasks.time.DateTimeUtils.startOfDay
import org.tasks.time.DateTimeUtils2.currentTimeMillis
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class TaskEditViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
    private val taskDao: TaskDao,
    private val taskDeleter: TaskDeleter,
    private val timerPlugin: TimerPlugin,
    private val permissionChecker: PermissionChecker,
    private val calendarEventProvider: CalendarEventProvider,
    private val gCalHelper: GCalHelper,
    private val taskMover: TaskMover,
    private val locationDao: LocationDao,
    private val geofenceApi: GeofenceApi,
    private val tagDao: TagDao,
    private val tagDataDao: TagDataDao,
    private val preferences: Preferences,
    private val googleTaskDao: GoogleTaskDao,
    private val caldavDao: CaldavDao,
    private val taskCompleter: TaskCompleter,
    private val alarmService: AlarmService,
    private val taskListEvents: TaskListEventBus,
    private val mainActivityEvents: MainActivityEventBus,
    private val firebase: Firebase? = null,
    private val userActivityDao: UserActivityDao,
    private val alarmDao: AlarmDao,
    private val taskAttachmentDao: TaskAttachmentDao,
) : ViewModel() {
    private val resources = context.resources
    private var cleared = false

    val task: Task = savedStateHandle[TaskEditFragment.EXTRA_TASK]!!

    val isNew = task.isNew

    var creationDate: Long = task.creationDate
    var modificationDate: Long = task.modificationDate
    var completionDate: Long = task.completionDate
    var title: String? = task.title
    var completed: Boolean = task.isCompleted
    var priority = MutableStateFlow(task.priority)
    var description: String? = task.notes.stripCarriageReturns()
    val recurrence = MutableStateFlow(task.recurrence)
    val repeatAfterCompletion = MutableStateFlow(task.repeatAfterCompletion())
    var eventUri = MutableStateFlow(task.calendarURI)
    val timerStarted = MutableStateFlow(task.timerStart)
    val estimatedSeconds = MutableStateFlow(task.estimatedSeconds)
    val elapsedSeconds = MutableStateFlow(task.elapsedSeconds)
    var newSubtasks = MutableStateFlow(emptyList<Task>())
    val hasParent: Boolean
        get() = task.parent > 0

    val dueDate = MutableStateFlow(task.dueDate)

    fun setDueDate(value: Long) {
        val addedDueDate = value > 0 && dueDate.value == 0L
        dueDate.value = when {
            value == 0L -> 0
            hasDueTime(value) -> createDueDate(Task.URGENCY_SPECIFIC_DAY_TIME, value)
            else -> createDueDate(Task.URGENCY_SPECIFIC_DAY, value)
        }
        if (addedDueDate) {
            val reminderFlags = preferences.defaultReminders
            if (reminderFlags.isFlagSet(Task.NOTIFY_AT_DEADLINE)) {
                selectedAlarms.value = selectedAlarms.value.plusAlarm(whenDue(task.id))
            }
            if (reminderFlags.isFlagSet(Task.NOTIFY_AFTER_DEADLINE)) {
                selectedAlarms.value = selectedAlarms.value.plusAlarm(whenOverdue(task.id))
            }
        }
    }

    val startDate = MutableStateFlow(task.hideUntil)

    fun setStartDate(value: Long) {
        val addedStartDate = value > 0 && startDate.value == 0L
        startDate.value = when {
            value == 0L -> 0
            hasDueTime(value) ->
                value.toDateTime().withSecondOfMinute(1).withMillisOfSecond(0).millis
            else -> value.startOfDay()
        }
        if (addedStartDate && preferences.defaultReminders.isFlagSet(Task.NOTIFY_AT_START)) {
            selectedAlarms.value = selectedAlarms.value.plusAlarm(whenStarted(task.id))
        }
    }

    private var originalCalendar: String? = if (isNew && permissionChecker.canAccessCalendars()) {
        preferences.defaultCalendar
    } else {
        null
    }
    var selectedCalendar = MutableStateFlow(originalCalendar)

    val originalList: Filter = savedStateHandle[TaskEditFragment.EXTRA_LIST]!!
    var selectedList = MutableStateFlow(originalList)

    private var originalLocation: Location? = savedStateHandle[TaskEditFragment.EXTRA_LOCATION]
    var selectedLocation = MutableStateFlow(originalLocation)

    private val originalTags: List<TagData> =
        savedStateHandle.get<ArrayList<TagData>>(TaskEditFragment.EXTRA_TAGS) ?: emptyList()
    val selectedTags = MutableStateFlow(ArrayList(originalTags))

    private lateinit var originalAttachments: List<TaskAttachment>
    val selectedAttachments = MutableStateFlow(emptyList<TaskAttachment>())

    private val originalAlarms: List<Alarm> = if (isNew) {
        ArrayList<Alarm>().apply {
            if (task.isNotifyAtStart) {
                add(whenStarted(0))
            }
            if (task.isNotifyAtDeadline) {
                add(whenDue(0))
            }
            if (task.isNotifyAfterDeadline) {
                add(whenOverdue(0))
            }
            if (task.randomReminder > 0) {
                add(Alarm(time = task.randomReminder, type = Alarm.TYPE_RANDOM))
            }
        }
    } else {
        savedStateHandle[TaskEditFragment.EXTRA_ALARMS]!!
    }

    var selectedAlarms = MutableStateFlow(originalAlarms)

    var ringNonstop: Boolean = task.isNotifyModeNonstop
        set(value) {
            field = value
            if (value) {
                ringFiveTimes = false
            }
        }

    var ringFiveTimes:Boolean = task.isNotifyModeFive
        set(value) {
            field = value
            if (value) {
                ringNonstop = false
            }
        }

    val isReadOnly = task.readOnly

    val isWritable = !isReadOnly

    fun hasChanges(): Boolean =
        (task.title != title || (isNew && title?.isNotBlank() == true)) ||
                task.isCompleted != completed ||
                task.dueDate != dueDate.value ||
                task.priority != priority.value ||
                if (task.notes.isNullOrBlank()) {
                    !description.isNullOrBlank()
                } else {
                    task.notes != description
                } ||
                task.hideUntil != startDate.value ||
                if (task.recurrence.isNullOrBlank()) {
                    !recurrence.value.isNullOrBlank()
                } else {
                    task.recurrence != recurrence.value
                } ||
                task.repeatAfterCompletion() != repeatAfterCompletion.value ||
                originalCalendar != selectedCalendar.value ||
                if (task.calendarURI.isNullOrBlank()) {
                    !eventUri.value.isNullOrBlank()
                } else {
                    task.calendarURI != eventUri.value
                } ||
                task.elapsedSeconds != elapsedSeconds.value ||
                task.estimatedSeconds != estimatedSeconds.value ||
                originalList != selectedList.value ||
                originalLocation != selectedLocation.value ||
                originalTags.toHashSet() != selectedTags.value.toHashSet() ||
                (::originalAttachments.isInitialized &&
                        originalAttachments.toHashSet() != selectedAttachments.value.toHashSet()) ||
                newSubtasks.value.isNotEmpty() ||
                getRingFlags() != when {
                    task.isNotifyModeFive -> NOTIFY_MODE_FIVE
                    task.isNotifyModeNonstop -> NOTIFY_MODE_NONSTOP
                    else -> 0
                } ||
                originalAlarms.toHashSet() != selectedAlarms.value.toHashSet()

    @MainThread
    suspend fun save(remove: Boolean = true): Boolean = withContext(NonCancellable) {
        if (cleared) {
            return@withContext false
        }
        if (!hasChanges() || isReadOnly) {
            discard(remove)
            return@withContext false
        }
        clear(remove)
        task.title = if (title.isNullOrBlank()) resources.getString(R.string.no_title) else title
        task.dueDate = dueDate.value
        task.priority = priority.value
        task.notes = description
        task.hideUntil = startDate.value
        task.recurrence = recurrence.value
        task.repeatFrom = if (repeatAfterCompletion.value) {
            Task.RepeatFrom.COMPLETION_DATE
        } else {
            Task.RepeatFrom.DUE_DATE
        }
        task.elapsedSeconds = elapsedSeconds.value
        task.estimatedSeconds = estimatedSeconds.value
        task.ringFlags = getRingFlags()

        applyCalendarChanges()

        if (isNew) {
            taskDao.createNew(task)
        }

        if ((isNew && selectedLocation.value != null) || originalLocation != selectedLocation.value) {
            originalLocation?.let { location ->
                if (location.geofence.id > 0) {
                    locationDao.delete(location.geofence)
                    geofenceApi.update(location.place)
                }
            }
            selectedLocation.value?.let { location ->
                val place = location.place
                locationDao.insert(
                    location.geofence.copy(
                        task = task.id,
                        place = place.uid,
                    )
                )
                geofenceApi.update(place)
            }
            task.putTransitory(FORCE_CALDAV_SYNC, true)
            task.modificationDate = currentTimeMillis()
        }

        if ((isNew && selectedTags.value.isNotEmpty()) || originalTags.toHashSet() != selectedTags.value.toHashSet()) {
            tagDao.applyTags(task, tagDataDao, selectedTags.value)
            task.modificationDate = currentTimeMillis()
        }

        if (!task.hasStartDate()) {
            selectedAlarms.value = selectedAlarms.value.filterNot { a -> a.type == TYPE_REL_START }
        }
        if (!task.hasDueDate()) {
            selectedAlarms.value = selectedAlarms.value.filterNot { a -> a.type == TYPE_REL_END }
        }

        if (
            selectedAlarms.value.toHashSet() != originalAlarms.toHashSet() ||
            (isNew && selectedAlarms.value.isNotEmpty())
        ) {
            alarmService.synchronizeAlarms(task.id, selectedAlarms.value.toMutableSet())
            task.putTransitory(FORCE_CALDAV_SYNC, true)
            task.modificationDate = currentTimeMillis()
        }

        taskDao.save(task, null)

        if (isNew || originalList != selectedList.value) {
            task.parent = 0
            taskMover.move(listOf(task.id), selectedList.value)
        }

        for (subtask in newSubtasks.value) {
            if (Strings.isNullOrEmpty(subtask.title)) {
                continue
            }
            if (!subtask.isCompleted) {
                subtask.completionDate = task.completionDate
            }
            taskDao.createNew(subtask)
            alarmDao.insert(subtask.getDefaultAlarms())
            firebase?.addTask("subtasks")
            when (val filter = selectedList.value) {
                is GtasksFilter -> {
                    val googleTask = CaldavTask(
                        task = subtask.id,
                        calendar = filter.remoteId,
                        remoteId = null,
                    )
                    subtask.parent = task.id
                    googleTask.isMoved = true
                    googleTaskDao.insertAndShift(
                        task = subtask,
                        caldavTask = googleTask,
                        top = if (isNew) false else preferences.addTasksToTop()
                    )
                }
                is CaldavFilter -> {
                    val caldavTask = CaldavTask(
                        task = subtask.id,
                        calendar = filter.uuid,
                    )
                    subtask.parent = task.id
                    caldavTask.remoteParent = caldavDao.getRemoteIdForTask(task.id)
                    taskDao.save(subtask)
                    caldavDao.insert(
                        task = subtask,
                        caldavTask = caldavTask,
                        addToTop = if (isNew) false else preferences.addTasksToTop()
                    )
                }
                else -> {
                    subtask.parent = task.id
                    taskDao.save(subtask)
                }
            }
        }

        if (
            this@TaskEditViewModel::originalAttachments.isInitialized &&
            selectedAttachments.value.toHashSet() != originalAttachments.toHashSet()
        ) {
            originalAttachments
                .minus(selectedAttachments.value.toSet())
                .map { it.remoteId }
                .let { taskAttachmentDao.delete(task.id, it) }
            selectedAttachments.value
                .minus(originalAttachments.toSet())
                .map {
                    Attachment(
                        task = task.id,
                        fileId = it.id!!,
                        attachmentUid = it.remoteId,
                    )
                }
                .let { taskAttachmentDao.insert(it) }
        }

        if (task.isCompleted != completed) {
            taskCompleter.setComplete(task, completed)
        }

        if (isNew) {
            val model = task
            taskListEvents.emit(TaskListEvent.TaskCreated(model.uuid))
            model.calendarURI?.takeIf { it.isNotBlank() }?.let {
                taskListEvents.emit(TaskListEvent.CalendarEventCreated(model.title, it))
            }
        }
        true
    }

    private suspend fun applyCalendarChanges() {
        if (!permissionChecker.canAccessCalendars()) {
            return
        }
        if (eventUri.value == null) {
            calendarEventProvider.deleteEvent(task)
        }
        if (!task.hasDueDate()) {
            return
        }
        selectedCalendar.value?.let {
            try {
                task.calendarURI = gCalHelper.createTaskEvent(task, it)?.toString()
            } catch (e: Exception) {
                Timber.e(e)
            }
        }
    }

    private fun getRingFlags() = when {
        ringNonstop -> NOTIFY_MODE_NONSTOP
        ringFiveTimes -> NOTIFY_MODE_FIVE
        else -> 0
    }

    suspend fun delete() {
        taskDeleter.markDeleted(task)
        discard()
    }

    suspend fun discard(remove: Boolean = true) {
        if (isNew) {
            timerPlugin.stopTimer(task)
            originalAttachments.plus(selectedAttachments.value).toSet().takeIf { it.isNotEmpty() }
                ?.onEach { FileHelper.delete(context, it.uri.toUri()) }
                ?.let { taskAttachmentDao.delete(it.toList()) }
        }
        clear(remove)
    }

    @MainThread
    suspend fun clear(remove: Boolean = true) {
        if (cleared) {
            return
        }
        cleared = true
        if (remove) {
            mainActivityEvents.emit(MainActivityEvent.ClearTaskEditFragment)
        }
    }

    override fun onCleared() {
        if (!cleared) {
            runBlocking {
                save(remove = false)
            }
        }
    }

    fun removeAlarm(alarm: Alarm) {
        selectedAlarms.update { it.minus(alarm) }
    }

    fun addAlarm(alarm: Alarm) {
        with (selectedAlarms) {
            if (value.none { it.same(alarm) }) {
                value = value.plus(alarm)
            }
        }
    }

    fun addComment(message: String?, picture: Uri?) {
        val userActivity = UserActivity()
        if (picture != null) {
            val output = FileHelper.copyToUri(context, preferences.attachmentsDirectory!!, picture)
            userActivity.setPicture(output)
        }
        userActivity.message = message
        userActivity.targetId = task.uuid
        userActivity.created = currentTimeMillis()
        viewModelScope.launch {
            withContext(NonCancellable) {
                userActivityDao.createNew(userActivity)
            }
        }
    }

    init {
        viewModelScope.launch {
            taskAttachmentDao.getAttachments(task.id).let { attachments ->
                selectedAttachments.update { attachments }
                originalAttachments = attachments
            }
        }
    }

    companion object {
        fun String?.stripCarriageReturns(): String? = this?.replace("\\r\\n?".toRegex(), "\n")

        private fun Int.isFlagSet(flag: Int): Boolean = this and flag > 0

        private fun List<Alarm>.plusAlarm(alarm: Alarm): List<Alarm> =
            if (any { it.same(alarm) }) this else this + alarm
    }
}
