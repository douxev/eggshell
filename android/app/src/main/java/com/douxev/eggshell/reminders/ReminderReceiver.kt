package com.douxev.eggshell.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import kotlin.math.roundToLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.douxev.eggshell.data.MedicationRepository
import com.douxev.eggshell.data.ScheduleRepository
import com.douxev.eggshell.data.VaultRepository

/**
 * Fired by [AlarmScheduler] when a reminder is due — for either a medication
 * schedule (action [AlarmScheduler.ACTION_REMINDER]) or a lab reminder
 * (action [AlarmScheduler.ACTION_LAB_REMINDER]).
 *
 * For med schedules: reads the metadata from [ReminderPrefs], notifies the
 * user, computes the next occurrence, persists it. If the vault is unlocked
 * we also write the new "next due" to the DB; otherwise the prefs mirror
 * stays ahead until [com.douxev.eggshell.data.ScheduleRepository.syncFromDb].
 *
 * For lab reminders: same flow against [LabReminderPrefs] (no DB sync needed
 * — labs live entirely in plain prefs).
 *
 * Priority flag for the heads-up channel comes from [PriorityPrefs], keyed
 * separately per kind.
 */
@AndroidEntryPoint
class ReminderReceiver : BroadcastReceiver() {

    @Inject lateinit var alarmScheduler: AlarmScheduler
    @Inject lateinit var notifier: ReminderNotifications
    @Inject lateinit var vault: VaultRepository
    @Inject lateinit var priority: PriorityPrefs
    @Inject lateinit var schedules: ScheduleRepository
    @Inject lateinit var medications: MedicationRepository
    @Inject lateinit var pendingDoses: PendingDosePrefs

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AlarmScheduler.ACTION_REMINDER -> handleMed(context, intent)
            AlarmScheduler.ACTION_LAB_REMINDER -> handleLab(context, intent)
            AlarmScheduler.ACTION_APPOINTMENT_REMINDER -> handleAppointment(intent)
            AlarmScheduler.ACTION_MARK_TAKEN -> handleMark(context, intent, status = "taken")
            AlarmScheduler.ACTION_MARK_SKIPPED -> handleMark(context, intent, status = "skipped")
            AlarmScheduler.ACTION_SNOOZE -> handleSnooze(context, intent)
            AlarmScheduler.ACTION_SNOOZE_FIRE -> handleSnoozeFire(context, intent)
        }
    }

    /**
     * "Rappeler plus tard" — dismiss the current notification and re-show the
     * same reminder after [AlarmScheduler.SNOOZE_MS]. Deliberately touches
     * neither the dose log nor the schedule's recurring cadence: the regular
     * next-due was already advanced when the reminder first fired, so this is a
     * one-off nudge independent of the cycle.
     */
    private fun handleSnooze(context: Context, intent: Intent) {
        val scheduleId = intent.getLongExtra(AlarmScheduler.EXTRA_SCHEDULE_ID, -1L)
        if (scheduleId < 0) return
        val medNotifId = (0x0000_0000) or (scheduleId.toInt() and 0x0000_FFFF)
        runCatching {
            context.getSystemService(android.app.NotificationManager::class.java).cancel(medNotifId)
        }
        alarmScheduler.scheduleSnooze(scheduleId, System.currentTimeMillis() + AlarmScheduler.SNOOZE_MS)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(
                context.applicationContext,
                context.getString(com.douxev.eggshell.R.string.reminder_snoozed_toast),
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }

    /**
     * The snooze one-shot fired: re-show the reminder. Reads the off-vault
     * mirror only (works while locked) and does NOT recompute next-due.
     */
    private fun handleSnoozeFire(context: Context, intent: Intent) {
        val scheduleId = intent.getLongExtra(AlarmScheduler.EXTRA_SCHEDULE_ID, -1L)
        if (scheduleId < 0) return
        val entry = ReminderPrefs(context).get(scheduleId) ?: return
        notifier.showMed(scheduleId, entry.displayLabel, priority.isMedPriority(scheduleId))
    }

    private fun handleMed(context: Context, intent: Intent) {
        val scheduleId = intent.getLongExtra(AlarmScheduler.EXTRA_SCHEDULE_ID, -1L)
        if (scheduleId < 0) return

        val prefs = ReminderPrefs(context)
        val entry = prefs.get(scheduleId) ?: return

        notifier.showMed(scheduleId, entry.displayLabel, priority.isMedPriority(scheduleId))

        val now = System.currentTimeMillis()
        val nextDue = NextDueCalculator.nextDueAfter(
            kind = entry.kind,
            intervalMinutes = entry.intervalMinutes,
            dailyHour = entry.dailyHour,
            dailyMinute = entry.dailyMinute,
            afterMs = now,
            intervalDays = entry.intervalDays,
            currentDueMs = entry.nextDueAtMs,
        )
        prefs.setNextDue(scheduleId, nextDue)
        alarmScheduler.schedule(scheduleId, nextDue)
        com.douxev.eggshell.widget.WidgetRefresh.refreshAll(context)

        if (vault.isUnlocked) {
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    runCatching {
                        vault.requireSession().setScheduleNextDue(scheduleId, nextDue)
                    }
                } finally {
                    pending.finish()
                }
            }
        }
    }

    /**
     * "Pris" / "Passer" notification action — fired from the phone's
     * notification shade or, via Wear OS notification bridging, from the user's
     * paired watch. [status] is "taken" or "skipped".
     *
     * Dismisses the notification immediately so the watch updates without
     * waiting on the IO work. Then, if the vault is unlocked, logs the dose
     * event (taken with the default dose, or a skip with no dose) + advances
     * the schedule to its next occurrence (full DB write). If the vault is
     * locked, we can't touch the encrypted dose log, so we queue the tap
     * (sealed) and commit it on the next real unlock, and just advance the
     * alarm so the user doesn't get re-pinged for the same dose.
     *
     * Either way the **prescribed time travels with the tap** — that is what
     * makes the offset ("+1 h 47") computable later. The offset itself is never
     * stored, only the two timestamps.
     */
    private fun handleMark(context: Context, intent: Intent, status: String) {
        val scheduleId = intent.getLongExtra(AlarmScheduler.EXTRA_SCHEDULE_ID, -1L)
        if (scheduleId < 0) return

        // Cancel the visible notification (and its mirror on the watch).
        // Must match the ID scheme in ReminderNotifications.showMed.
        val medNotifId = (0x0000_0000) or (scheduleId.toInt() and 0x0000_FFFF)
        val nm = context.getSystemService(android.app.NotificationManager::class.java)
        runCatching { nm.cancel(medNotifId) }

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // The widget can say when the dose was actually taken; a
                // notification action does not, and means "now".
                val now = intent.getLongExtra(AlarmScheduler.EXTRA_TAKEN_AT_MS, 0L)
                    .takeIf { it > 0L }
                    ?: System.currentTimeMillis()
                if (vault.isUnlocked) {
                    runCatching {
                        val sched = vault.requireSession().listActiveSchedules()
                            .firstOrNull { it.id == scheduleId }
                        val med = sched?.let { medications.get(it.medicationId) }
                        if (sched != null && med != null) {
                            val taken = status == "taken"
                            medications.logDose(
                                uniffi.transition.NewDoseEvent(
                                    medicationId = med.id,
                                    takenAtMs = now,
                                    dose = if (taken) med.defaultDose else null,
                                    doseUnit = if (taken) med.defaultDoseUnit else null,
                                    route = if (taken) med.route else null,
                                    injectionSite = null,
                                    notes = null,
                                    status = status,
                                    scheduledAtMs = DueOccurrence.nearest(
                                        kind = sched.kind,
                                        intervalMinutes = sched.intervalMinutes?.toInt(),
                                        dailyHour = sched.dailyHour?.toInt(),
                                        dailyMinute = sched.dailyMinute?.toInt(),
                                        intervalDays = sched.intervalDays?.toInt(),
                                        anchorMs = sched.nextDueAtMs,
                                        atMs = now,
                                    ),
                                    scheduleId = scheduleId,
                                )
                            )
                        }
                        schedules.advanceToNextOccurrence(scheduleId)
                    }
                } else {
                    // Vault locked: we can't touch the encrypted dose log, so
                    // queue the tap (sealed at rest) and commit it on the next
                    // real unlock. This works in every security mode, including
                    // Paranoid / passphrase, since the queue defers all DB
                    // writes. We need the medication id to log against later —
                    // it lives in the plain alarm mirror, not the DB, and so
                    // does the cadence we derive the prescribed time from.
                    val entry = ReminderPrefs(context).get(scheduleId)
                    val medId = entry?.medicationId ?: -1L
                    val queued = medId >= 0 && pendingDoses.add(
                        PendingDosePrefs.Pending(
                            scheduleId = scheduleId,
                            medicationId = medId,
                            takenAtMs = now,
                            status = status,
                            scheduledAtMs = entry?.let {
                                DueOccurrence.nearest(
                                    kind = it.kind,
                                    intervalMinutes = it.intervalMinutes,
                                    dailyHour = it.dailyHour,
                                    dailyMinute = it.dailyMinute,
                                    intervalDays = it.intervalDays,
                                    anchorMs = it.nextDueAtMs,
                                    atMs = now,
                                )
                            },
                        )
                    )
                    val toast = if (queued) {
                        // Recorded — will be folded into the stats on unlock.
                        com.douxev.eggshell.R.string.reminder_queued_toast
                    } else {
                        // Couldn't resolve the med or sealing failed: tell the
                        // user to record it after unlocking rather than imply
                        // it was saved.
                        com.douxev.eggshell.R.string.reminder_locked_toast
                    }
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        android.widget.Toast.makeText(
                            context.applicationContext,
                            context.getString(toast),
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    }
                }
                com.douxev.eggshell.widget.WidgetRefresh.refreshAll(context)
            } finally {
                pending.finish()
            }
        }
    }

    private fun handleLab(context: Context, intent: Intent) {
        val labId = intent.getLongExtra(AlarmScheduler.EXTRA_LAB_ID, -1L)
        if (labId < 0) return

        val prefs = LabReminderPrefs(context)
        val entry = prefs.get(labId) ?: return

        notifier.showLab(labId, entry.category, priority.isLabPriority(labId))

        val now = System.currentTimeMillis()
        val nextDue = LabNextDueCalculator.nextDueAfter(
            kind = entry.kind,
            intervalDays = entry.intervalDays,
            dailyHour = entry.dailyHour,
            dailyMinute = entry.dailyMinute,
            afterMs = now,
        )
        prefs.setNextDue(labId, nextDue)
        alarmScheduler.scheduleLab(labId, nextDue)
        com.douxev.eggshell.widget.WidgetRefresh.refreshAll(context)
    }

    /**
     * One-shot appointment reminder. We only have the numeric id here (the
     * appointment details live in the locked vault), so we post a generic
     * notification and do NOT reschedule — an appointment happens once.
     */
    private fun handleAppointment(intent: Intent) {
        val appointmentId = intent.getLongExtra(AlarmScheduler.EXTRA_APPOINTMENT_ID, -1L)
        if (appointmentId < 0) return
        notifier.showAppointment(appointmentId)
    }
}

/**
 * Which occurrence of a schedule an intake answers.
 *
 * You cannot simply read a schedule's `next_due_at_ms` and call it the
 * prescribed time: by the time the user taps "Pris" on the notification,
 * [ReminderReceiver.handleMed] has already advanced both the plain mirror and
 * the DB row to the *following* occurrence. So we replay the cadence and keep
 * the occurrence closest to the intake — which is exactly the rule
 * [com.douxev.eggshell.data.PlannedDoses] uses when it pairs occurrences with
 * intakes, so the writer and the reader can never disagree.
 *
 * Returns null when the schedule carries no usable cadence: no prescribed time
 * is far better than a made-up one (D2).
 */
internal object DueOccurrence {

    fun nearest(
        kind: String,
        intervalMinutes: Int?,
        dailyHour: Int?,
        dailyMinute: Int?,
        intervalDays: Int?,
        anchorMs: Long,
        atMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long? = when (kind) {
        "interval" -> {
            val cadence = (intervalMinutes ?: 0).toLong() * 60_000L
            if (cadence <= 0L) {
                null
            } else {
                // Pure arithmetic: an "every N hours" schedule has no wall-clock
                // anchor to preserve across a DST change.
                val steps = ((atMs - anchorMs).toDouble() / cadence).roundToLong()
                anchorMs + steps * cadence
            }
        }
        "daily", "days_interval" -> {
            val hour = dailyHour
            val minute = dailyMinute
            val step = if (kind == "days_interval") (intervalDays ?: 0).toLong() else 1L
            if (hour == null || minute == null || step <= 0L) {
                null
            } else {
                // plusDays/minusDays keep the wall-clock HH:MM across DST, which
                // is what a daily reminder means to the person taking it.
                var at = Instant.ofEpochMilli(anchorMs).atZone(zone)
                    .withHour(hour).withMinute(minute).withSecond(0).withNano(0)
                var guard = 0
                while (at.toInstant().toEpochMilli() > atMs && guard++ < MAX_STEPS) {
                    at = at.minusDays(step)
                }
                while (
                    at.plusDays(step).toInstant().toEpochMilli() <= atMs && guard++ < MAX_STEPS
                ) {
                    at = at.plusDays(step)
                }
                val previous = at.toInstant().toEpochMilli()
                val next = at.plusDays(step).toInstant().toEpochMilli()
                if (atMs - previous <= next - atMs) previous else next
            }
        }
        else -> null
    }

    /** Half a cadence — outside it, an intake answers no occurrence at all. */
    fun toleranceMs(kind: String, intervalMinutes: Int?, intervalDays: Int?): Long = when (kind) {
        "interval" -> (intervalMinutes?.toLong() ?: DAY_MINUTES) * 60_000L
        "days_interval" -> (intervalDays?.toLong() ?: 1L) * 86_400_000L
        else -> 86_400_000L
    }.coerceAtLeast(60_000L) / 2L

    private const val MAX_STEPS = 4000
    private const val DAY_MINUTES = 24L * 60L
}
