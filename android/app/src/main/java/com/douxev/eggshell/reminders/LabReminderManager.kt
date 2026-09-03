package com.douxev.eggshell.reminders

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CRUD on lab reminders, plus the side-effects of (un)installing the alarm.
 * Mirrors what [com.douxev.eggshell.data.ScheduleRepository] does for
 * medication schedules, but lab reminders aren't backed by the encrypted DB
 * so there is no syncFromDb step.
 */
@Singleton
class LabReminderManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val alarmScheduler: AlarmScheduler,
    private val priority: PriorityPrefs,
) {
    private val prefs = LabReminderPrefs(context)

    private fun refreshWidget() {
        com.douxev.eggshell.widget.WidgetRefresh.refreshAll(context)
    }

    fun list(): List<LabReminderPrefs.Entry> = prefs.all()

    fun createInterval(
        label: String,
        intervalDays: Int,
        category: String = LabReminderPrefs.CATEGORY_LAB,
    ): LabReminderPrefs.Entry {
        require(label.isNotBlank()) { "label required" }
        require(intervalDays > 0) { "intervalDays must be positive" }
        val id = prefs.nextId()
        val now = System.currentTimeMillis()
        val nextDue = LabNextDueCalculator.nextDueAfter(
            kind = "interval",
            intervalDays = intervalDays,
            dailyHour = null,
            dailyMinute = null,
            afterMs = now,
        )
        val entry = LabReminderPrefs.Entry(
            id = id,
            label = label.trim(),
            kind = "interval",
            intervalDays = intervalDays,
            dailyHour = null,
            dailyMinute = null,
            nextDueAtMs = nextDue,
            category = category,
        )
        prefs.put(entry)
        alarmScheduler.scheduleLab(id, nextDue)
        refreshWidget()
        return entry
    }

    fun createDaily(
        label: String,
        hour: Int,
        minute: Int,
        category: String = LabReminderPrefs.CATEGORY_LAB,
    ): LabReminderPrefs.Entry {
        require(label.isNotBlank()) { "label required" }
        require(hour in 0..23) { "hour out of range" }
        require(minute in 0..59) { "minute out of range" }
        val id = prefs.nextId()
        val now = System.currentTimeMillis()
        val nextDue = LabNextDueCalculator.nextDueAfter(
            kind = "daily",
            intervalDays = null,
            dailyHour = hour,
            dailyMinute = minute,
            afterMs = now,
        )
        val entry = LabReminderPrefs.Entry(
            id = id,
            label = label.trim(),
            kind = "daily",
            intervalDays = null,
            dailyHour = hour,
            dailyMinute = minute,
            nextDueAtMs = nextDue,
            category = category,
        )
        prefs.put(entry)
        alarmScheduler.scheduleLab(id, nextDue)
        refreshWidget()
        return entry
    }

    /**
     * Overwrite an existing reminder's schedule + label. Keeps the same id
     * (so priority preference and any saved widget reference survive), cancels
     * the old alarm, and schedules the fresh nextDueAt.
     */
    fun updateInterval(id: Long, label: String, intervalDays: Int) {
        require(label.isNotBlank()) { "label required" }
        require(intervalDays > 0) { "intervalDays must be positive" }
        val existing = prefs.get(id) ?: error("no reminder with id $id")
        val now = System.currentTimeMillis()
        val nextDue = LabNextDueCalculator.nextDueAfter(
            kind = "interval",
            intervalDays = intervalDays,
            dailyHour = null,
            dailyMinute = null,
            afterMs = now,
        )
        alarmScheduler.cancelLab(id)
        prefs.put(
            existing.copy(
                label = label.trim(),
                kind = "interval",
                intervalDays = intervalDays,
                dailyHour = null,
                dailyMinute = null,
                nextDueAtMs = nextDue,
            )
        )
        alarmScheduler.scheduleLab(id, nextDue)
        refreshWidget()
    }

    fun updateDaily(id: Long, label: String, hour: Int, minute: Int) {
        require(label.isNotBlank()) { "label required" }
        require(hour in 0..23) { "hour out of range" }
        require(minute in 0..59) { "minute out of range" }
        val existing = prefs.get(id) ?: error("no reminder with id $id")
        val now = System.currentTimeMillis()
        val nextDue = LabNextDueCalculator.nextDueAfter(
            kind = "daily",
            intervalDays = null,
            dailyHour = hour,
            dailyMinute = minute,
            afterMs = now,
        )
        alarmScheduler.cancelLab(id)
        prefs.put(
            existing.copy(
                label = label.trim(),
                kind = "daily",
                intervalDays = null,
                dailyHour = hour,
                dailyMinute = minute,
                nextDueAtMs = nextDue,
            )
        )
        alarmScheduler.scheduleLab(id, nextDue)
        refreshWidget()
    }

    fun delete(id: Long) {
        alarmScheduler.cancelLab(id)
        prefs.remove(id)
        priority.removeLab(id)
        refreshWidget()
    }

    fun setPriority(id: Long, priorityOn: Boolean) {
        priority.setLabPriority(id, priorityOn)
    }

    fun isPriority(id: Long): Boolean = priority.isLabPriority(id)
}
