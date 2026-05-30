package com.douxev.eggshell.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.douxev.eggshell.reminders.AlarmScheduler
import com.douxev.eggshell.reminders.NextDueCalculator
import com.douxev.eggshell.reminders.PriorityPrefs
import com.douxev.eggshell.reminders.ReminderPrefs
import uniffi.transition.DoseSchedule
import uniffi.transition.NewDoseSchedule

/**
 * Bridges the Vault's schedule table with the plain [ReminderPrefs] mirror
 * and the [AlarmScheduler]. Every method here keeps all three in sync.
 */
@Singleton
class ScheduleRepository @Inject constructor(
    private val vault: VaultRepository,
    private val alarmScheduler: AlarmScheduler,
    private val priority: PriorityPrefs,
    @ApplicationContext private val context: Context,
) {
    private val prefs = ReminderPrefs(context)

    fun isPriority(scheduleId: Long): Boolean = priority.isMedPriority(scheduleId)
    fun setPriority(scheduleId: Long, priorityOn: Boolean) {
        priority.setMedPriority(scheduleId, priorityOn)
    }

    private fun refreshWidget() {
        com.douxev.eggshell.widget.EggshellWidgetProvider.broadcastRefresh(context)
    }

    suspend fun listAllActive(): List<DoseSchedule> = withContext(Dispatchers.IO) {
        vault.requireSession().listActiveSchedules()
    }

    suspend fun listForMedication(medicationId: Long, includeInactive: Boolean = false): List<DoseSchedule> =
        withContext(Dispatchers.IO) {
            vault.requireSession().listSchedulesForMedication(medicationId, includeInactive)
        }

    /**
     * Create a new interval schedule. The first due time is set to `now + interval`.
     */
    suspend fun createInterval(medicationId: Long, intervalMinutes: Int) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val nextDue = NextDueCalculator.nextDueAfter("interval", intervalMinutes, null, null, now)
        val schedule = vault.requireSession().addSchedule(
            NewDoseSchedule(
                medicationId = medicationId,
                kind = "interval",
                intervalMinutes = intervalMinutes.toUInt(),
                dailyHour = null,
                dailyMinute = null,
                nextDueAtMs = nextDue,
            ),
            now,
        )
        installAlarm(schedule)
    }

    /**
     * Create a new daily schedule at HH:MM local time.
     */
    suspend fun createDaily(medicationId: Long, hour: Int, minute: Int) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val nextDue = NextDueCalculator.nextDueAfter("daily", null, hour, minute, now)
        val schedule = vault.requireSession().addSchedule(
            NewDoseSchedule(
                medicationId = medicationId,
                kind = "daily",
                intervalMinutes = null,
                dailyHour = hour.toUInt(),
                dailyMinute = minute.toUInt(),
                nextDueAtMs = nextDue,
            ),
            now,
        )
        installAlarm(schedule)
    }

    /**
     * Advance the schedule's `next_due_at_ms` one occurrence forward. Used
     * when the user marks a dose as taken from the Today screen so the next
     * reminder doesn't fire for the same dose again.
     */
    suspend fun advanceToNextOccurrence(scheduleId: Long) = withContext(Dispatchers.IO) {
        val all = vault.requireSession().listActiveSchedules()
        val s = all.firstOrNull { it.id == scheduleId } ?: return@withContext
        val next = NextDueCalculator.nextDueAfter(
            s.kind,
            s.intervalMinutes?.toInt(),
            s.dailyHour?.toInt(),
            s.dailyMinute?.toInt(),
            System.currentTimeMillis(),
        )
        vault.requireSession().setScheduleNextDue(scheduleId, next)
        prefs.setNextDue(scheduleId, next)
        alarmScheduler.schedule(scheduleId, next)
        refreshWidget()
    }

    suspend fun setActive(scheduleId: Long, active: Boolean) = withContext(Dispatchers.IO) {
        vault.requireSession().setScheduleActive(scheduleId, active)
        if (active) {
            // Find the schedule (re-list) to know its kind/params.
            val schedule = vault.requireSession().listActiveSchedules()
                .firstOrNull { it.id == scheduleId }
            schedule?.let { installAlarm(it) }
        } else {
            alarmScheduler.cancel(scheduleId)
            prefs.remove(scheduleId)
            priority.removeMed(scheduleId)
        }
        refreshWidget()
    }

    /**
     * Reconcile prefs + alarms from the DB. Called on vault unlock so an
     * AlarmManager wakeup that happened while we were sleeping still finds
     * consistent state.
     */
    suspend fun syncFromDb() = withContext(Dispatchers.IO) {
        val active = vault.requireSession().listActiveSchedules()
        val activeIds = active.map { it.id }.toSet()
        prefs.all().filter { it.scheduleId !in activeIds }.forEach {
            alarmScheduler.cancel(it.scheduleId)
            prefs.remove(it.scheduleId)
            priority.removeMed(it.scheduleId)
        }
        active.forEach { installAlarm(it) }
        refreshWidget()
    }

    private fun installAlarm(s: DoseSchedule) {
        prefs.put(
            ReminderPrefs.Entry(
                scheduleId = s.id,
                kind = s.kind,
                intervalMinutes = s.intervalMinutes?.toInt(),
                dailyHour = s.dailyHour?.toInt(),
                dailyMinute = s.dailyMinute?.toInt(),
                nextDueAtMs = s.nextDueAtMs,
            )
        )
        alarmScheduler.schedule(s.id, s.nextDueAtMs)
    }
}
