package com.douxev.eggshell.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
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

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AlarmScheduler.ACTION_REMINDER -> handleMed(context, intent)
            AlarmScheduler.ACTION_LAB_REMINDER -> handleLab(context, intent)
            AlarmScheduler.ACTION_MARK_TAKEN -> handleMarkTaken(context, intent)
        }
    }

    private fun handleMed(context: Context, intent: Intent) {
        val scheduleId = intent.getLongExtra(AlarmScheduler.EXTRA_SCHEDULE_ID, -1L)
        if (scheduleId < 0) return

        val prefs = ReminderPrefs(context)
        val entry = prefs.get(scheduleId) ?: return

        notifier.showMed(scheduleId, priority.isMedPriority(scheduleId))

        val now = System.currentTimeMillis()
        val nextDue = NextDueCalculator.nextDueAfter(
            kind = entry.kind,
            intervalMinutes = entry.intervalMinutes,
            dailyHour = entry.dailyHour,
            dailyMinute = entry.dailyMinute,
            afterMs = now,
        )
        prefs.setNextDue(scheduleId, nextDue)
        alarmScheduler.schedule(scheduleId, nextDue)
        com.douxev.eggshell.widget.EggshellWidgetProvider.broadcastRefresh(context)

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
     * "Pris" notification action — fired from the phone's notification shade
     * or, via Wear OS notification bridging, from the user's paired watch.
     *
     * Dismisses the notification immediately so the watch updates without
     * waiting on the IO work. Then, if the vault is unlocked, logs the dose
     * + advances the schedule to its next occurrence (full DB write). If
     * the vault is locked, we can't touch the encrypted dose log, so we
     * just advance the alarm so the user doesn't get re-pinged for the
     * same dose, and rely on them logging it manually next unlock.
     */
    private fun handleMarkTaken(context: Context, intent: Intent) {
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
                if (vault.isUnlocked) {
                    runCatching {
                        val med = vault.requireSession().listActiveSchedules()
                            .firstOrNull { it.id == scheduleId }
                            ?.let { sched -> medications.get(sched.medicationId) }
                        if (med != null) {
                            medications.logDose(
                                uniffi.transition.NewDoseEvent(
                                    medicationId = med.id,
                                    takenAtMs = System.currentTimeMillis(),
                                    dose = med.defaultDose,
                                    doseUnit = med.defaultDoseUnit,
                                    route = med.route,
                                    injectionSite = null,
                                    notes = null,
                                )
                            )
                        }
                        schedules.advanceToNextOccurrence(scheduleId)
                    }
                } else {
                    // Vault locked: we can't log the dose. Silently
                    // dismissing the notification + advancing the alarm
                    // would mean the user thinks they recorded the dose
                    // (the watch / shade swallowed the tap) when they
                    // didn't — they'd see no entry later and re-take.
                    // Instead, surface a toast from the main thread and
                    // do NOT advance the schedule, so the next alarm
                    // still fires and the user gets a second chance to
                    // log it after unlocking.
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        android.widget.Toast.makeText(
                            context.applicationContext,
                            context.getString(com.douxev.eggshell.R.string.reminder_locked_toast),
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    }
                }
                com.douxev.eggshell.widget.EggshellWidgetProvider.broadcastRefresh(context)
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

        notifier.showLab(labId, entry.label, priority.isLabPriority(labId))

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
        com.douxev.eggshell.widget.EggshellWidgetProvider.broadcastRefresh(context)
    }
}
