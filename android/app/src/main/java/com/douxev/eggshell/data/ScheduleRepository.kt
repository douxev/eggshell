package com.douxev.eggshell.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.douxev.eggshell.reminders.AlarmScheduler
import com.douxev.eggshell.reminders.MedAliasPrefs
import com.douxev.eggshell.reminders.NextDueCalculator
import com.douxev.eggshell.reminders.NotifContentPrefs
import com.douxev.eggshell.reminders.PendingDosePrefs
import com.douxev.eggshell.reminders.PriorityPrefs
import com.douxev.eggshell.reminders.ReminderNotifications
import com.douxev.eggshell.reminders.ReminderPrefs
import uniffi.transition.DoseSchedule
import uniffi.transition.NewDoseEvent
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
    private val notifContent: NotifContentPrefs,
    private val medAlias: MedAliasPrefs,
    private val pendingDoses: PendingDosePrefs,
    private val notifications: ReminderNotifications,
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
    suspend fun createInterval(
        medicationId: Long,
        intervalMinutes: Int,
        label: String? = null,
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val nextDue = NextDueCalculator.nextDueAfter("interval", intervalMinutes, null, null, now)
        val schedule = vault.requireSession().addSchedule(
            NewDoseSchedule(
                medicationId = medicationId,
                kind = "interval",
                intervalMinutes = intervalMinutes.toUInt(),
                dailyHour = null,
                dailyMinute = null,
                intervalDays = null,
                nextDueAtMs = nextDue,
                label = label,
            ),
            now,
        )
        installAlarm(schedule)
    }

    /**
     * Create a new daily schedule at HH:MM local time.
     */
    suspend fun createDaily(
        medicationId: Long,
        hour: Int,
        minute: Int,
        label: String? = null,
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val nextDue = NextDueCalculator.nextDueAfter("daily", null, hour, minute, now)
        val schedule = vault.requireSession().addSchedule(
            NewDoseSchedule(
                medicationId = medicationId,
                kind = "daily",
                intervalMinutes = null,
                dailyHour = hour.toUInt(),
                dailyMinute = minute.toUInt(),
                intervalDays = null,
                nextDueAtMs = nextDue,
                label = label,
            ),
            now,
        )
        installAlarm(schedule)
    }

    /**
     * Create an "every N days at HH:MM" schedule, anchored at [startDateMs]
     * (a local midnight epoch ms for the chosen start day). The first
     * occurrence is that day at HH:MM if it's still in the future, else the
     * next N-day step after now — so picking "today" but a time already passed
     * rolls to the first future occurrence while keeping the cadence phase.
     */
    suspend fun createDaysInterval(
        medicationId: Long,
        intervalDays: Int,
        hour: Int,
        minute: Int,
        startDateMs: Long,
        label: String? = null,
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val nextDue = daysIntervalNextDue(intervalDays, hour, minute, startDateMs, now)
        val schedule = vault.requireSession().addSchedule(
            NewDoseSchedule(
                medicationId = medicationId,
                kind = "days_interval",
                intervalMinutes = null,
                dailyHour = hour.toUInt(),
                dailyMinute = minute.toUInt(),
                intervalDays = intervalDays.toUInt(),
                nextDueAtMs = nextDue,
                label = label,
            ),
            now,
        )
        installAlarm(schedule)
    }

    /**
     * Edit a reminder in place — cadence, time and custom label. The id stays
     * stable so the sealed mirror, armed alarm and any queued snooze keep
     * pointing at the same reminder; the next occurrence is recomputed from
     * the new spec. A paused reminder stays paused (its alarm stays cancelled
     * until resume re-installs everything from the fresh row).
     */
    suspend fun updateSchedule(
        scheduleId: Long,
        medicationId: Long,
        kind: String,
        intervalMinutes: Int?,
        hour: Int?,
        minute: Int?,
        intervalDays: Int?,
        startDateMs: Long?,
        label: String?,
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val existing = vault.requireSession()
            .listSchedulesForMedication(medicationId, true)
            .firstOrNull { it.id == scheduleId }
        val nextDue = when (kind) {
            // Keep the running countdown when the cadence didn't change — a
            // label-only edit must not push an "every 12 h" reminder back by
            // however far into the cycle the user happened to be.
            "interval" -> {
                val cadenceUnchanged = existing?.kind == "interval" &&
                    existing.intervalMinutes?.toInt() == intervalMinutes
                if (cadenceUnchanged && existing != null && existing.nextDueAtMs > now) {
                    existing.nextDueAtMs
                } else {
                    NextDueCalculator.nextDueAfter("interval", intervalMinutes, null, null, now)
                }
            }
            "daily" -> NextDueCalculator.nextDueAfter("daily", null, hour, minute, now)
            // The screen seeds startDateMs from the schedule's current next-due
            // day, so the N-day phase carries over unless the user picks a new
            // start date on purpose.
            else -> daysIntervalNextDue(
                intervalDays ?: 1, hour ?: 0, minute ?: 0, startDateMs ?: now, now,
            )
        }
        val updated = vault.requireSession().updateSchedule(
            scheduleId,
            NewDoseSchedule(
                medicationId = medicationId,
                kind = kind,
                intervalMinutes = intervalMinutes?.toUInt(),
                dailyHour = hour?.toUInt(),
                dailyMinute = minute?.toUInt(),
                intervalDays = intervalDays?.toUInt(),
                nextDueAtMs = nextDue,
                label = label,
            ),
        )
        if (updated.active) {
            installAlarm(updated)
        } else {
            alarmScheduler.cancel(scheduleId)
        }
        refreshWidget()
    }

    /** Anchor = chosen start day at HH:MM; step the N-day phase until after now. */
    private fun daysIntervalNextDue(
        intervalDays: Int,
        hour: Int,
        minute: Int,
        startDateMs: Long,
        nowMs: Long,
    ): Long {
        val zone = java.time.ZoneId.systemDefault()
        val anchor = java.time.Instant.ofEpochMilli(startDateMs).atZone(zone)
            .withHour(hour).withMinute(minute).withSecond(0).withNano(0)
            .toInstant().toEpochMilli()
        return NextDueCalculator.nextDueAfter(
            kind = "days_interval",
            intervalMinutes = null,
            dailyHour = hour,
            dailyMinute = minute,
            afterMs = nowMs - 1,
            intervalDays = intervalDays,
            currentDueMs = anchor,
        )
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
            kind = s.kind,
            intervalMinutes = s.intervalMinutes?.toInt(),
            dailyHour = s.dailyHour?.toInt(),
            dailyMinute = s.dailyMinute?.toInt(),
            afterMs = System.currentTimeMillis(),
            intervalDays = s.intervalDays?.toInt(),
            currentDueMs = s.nextDueAtMs,
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
     * Permanently delete a schedule (a true delete, not a pause). Removes the
     * DB row AND every off-vault trace that references its id, so it can't be
     * resurrected by [com.douxev.eggshell.reminders.BootReceiver] after a
     * reboot or reappear in the widget while the vault is locked. Dose history
     * is untouched — it belongs to the medication, not the schedule.
     */
    suspend fun deleteSchedule(scheduleId: Long) = withContext(Dispatchers.IO) {
        vault.requireSession().deleteSchedule(scheduleId)
        alarmScheduler.cancel(scheduleId)
        alarmScheduler.cancelSnooze(scheduleId)
        prefs.remove(scheduleId)
        priority.removeMed(scheduleId)
        notifications.cancelMed(scheduleId)
        pendingDoses.removeForSchedule(scheduleId)
        refreshWidget()
    }

    /**
     * Tear down all OFF-vault state for a medication that's about to be
     * hard-deleted: for each of its schedules, cancel the alarm + snooze and
     * drop the sealed reminder mirror, priority flag, any posted notification
     * and queued taps; then clear the medication alias.
     *
     * The in-vault rows (medication + cascaded doses/schedules/treatment
     * changes) are removed separately via [MedicationRepository.delete]. Its
     * cascade deletes the dose_schedules rows, so we MUST read the schedule ids
     * here, BEFORE that call, or they'd be gone and the alarms orphaned.
     */
    suspend fun deleteMedicationCleanup(medicationId: Long) = withContext(Dispatchers.IO) {
        val schedules = vault.requireSession().listSchedulesForMedication(medicationId, true)
        schedules.forEach { s ->
            alarmScheduler.cancel(s.id)
            alarmScheduler.cancelSnooze(s.id)
            prefs.remove(s.id)
            priority.removeMed(s.id)
            notifications.cancelMed(s.id)
            pendingDoses.removeForSchedule(s.id)
        }
        medAlias.set(medicationId, null)
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
                medicationId = s.medicationId,
                kind = s.kind,
                intervalMinutes = s.intervalMinutes?.toInt(),
                dailyHour = s.dailyHour?.toInt(),
                dailyMinute = s.dailyMinute?.toInt(),
                nextDueAtMs = s.nextDueAtMs,
                displayLabel = resolveDisplayLabel(s.medicationId, s.label),
                intervalDays = s.intervalDays?.toInt(),
            )
        )
        alarmScheduler.schedule(s.id, s.nextDueAtMs)
    }

    /**
     * The text a reminder is allowed to show, per the global
     * [NotifContentPrefs] mode. GENERIC → null (nothing identifying in clear,
     * including any custom label); NAME → the reminder's own label when set,
     * else the real name; ALIAS → the reminder's label, else the
     * per-medication alias, or null (generic) — never the real name as a
     * fallback.
     */
    private fun resolveDisplayLabel(medicationId: Long, scheduleLabel: String?): String? =
        when (notifContent.current) {
            NotifContentPrefs.Mode.GENERIC -> null
            NotifContentPrefs.Mode.NAME -> scheduleLabel
                ?: runCatching { vault.requireSession().getMedication(medicationId)?.name }.getOrNull()
            NotifContentPrefs.Mode.ALIAS -> scheduleLabel ?: medAlias.get(medicationId)
        }

    /**
     * Drain the locked-while-taken dose queue into the encrypted vault. Called
     * on real unlock (never under a decoy PIN — that path never opens a
     * session). Logs each queued dose and advances its schedule, mirroring the
     * unlocked "Pris" path, then clears the queue. Runs before [syncFromDb] so
     * the freshly advanced schedules are what gets reconciled.
     */
    suspend fun flushPendingDoses() = withContext(Dispatchers.IO) {
        val items = pendingDoses.all()
        if (items.isEmpty()) return@withContext
        val session = vault.requireSession()
        val affectedSchedules = mutableSetOf<Long>()
        items.forEach { p ->
            runCatching {
                val med = session.getMedication(p.medicationId) ?: return@forEach
                val taken = p.status == "taken"
                session.logDose(
                    NewDoseEvent(
                        medicationId = med.id,
                        takenAtMs = p.takenAtMs,
                        dose = if (taken) med.defaultDose else null,
                        doseUnit = if (taken) med.defaultDoseUnit else null,
                        route = if (taken) med.route else null,
                        injectionSite = null,
                        notes = null,
                        status = p.status,
                        scheduledAtMs = null,
                        scheduleId = p.scheduleId,
                    )
                )
                affectedSchedules += p.scheduleId
            }
        }
        // Clear unconditionally: a med deleted while locked can't be logged and
        // must not wedge the queue into retrying forever.
        pendingDoses.clear()
        affectedSchedules.forEach { runCatching { advanceToNextOccurrence(it) } }
        refreshWidget()
    }
}
