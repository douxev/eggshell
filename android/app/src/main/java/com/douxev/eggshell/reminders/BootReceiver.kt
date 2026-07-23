package com.douxev.eggshell.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Re-installs the [AlarmManager] entries after a reboot. Reads from
 * [ReminderPrefs] only — no DB access needed.
 *
 * Also handles `MY_PACKAGE_REPLACED` so reminders survive an app upgrade.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var alarmScheduler: AlarmScheduler

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val now = System.currentTimeMillis()

                // Medication schedules. Each entry is fenced on its own — one
                // corrupt row (unknown kind after a downgrade, null interval)
                // must not kill the loop and leave every other reminder dead.
                val medPrefs = ReminderPrefs(context)
                medPrefs.all().forEach { entry ->
                    runCatching {
                    val due = if (entry.nextDueAtMs > now) entry.nextDueAtMs else {
                        // The reboot took longer than the next due — slide
                        // forward to the next occurrence so we don't fire
                        // immediately for something the user might have done.
                        // intervalDays + currentDueMs are required for
                        // days_interval (phase-preserving step); without them
                        // the calculator throws and the whole re-arm loop dies.
                        val next = NextDueCalculator.nextDueAfter(
                            kind = entry.kind,
                            intervalMinutes = entry.intervalMinutes,
                            dailyHour = entry.dailyHour,
                            dailyMinute = entry.dailyMinute,
                            afterMs = now,
                            intervalDays = entry.intervalDays,
                            currentDueMs = entry.nextDueAtMs,
                        )
                        medPrefs.setNextDue(entry.scheduleId, next)
                        next
                    }
                    alarmScheduler.schedule(entry.scheduleId, due)
                    }
                }

                // Lab reminders — same slide-forward rule, same per-entry fence.
                val labPrefs = LabReminderPrefs(context)
                labPrefs.all().forEach { entry ->
                    runCatching {
                    val due = if (entry.nextDueAtMs > now) entry.nextDueAtMs else {
                        val next = LabNextDueCalculator.nextDueAfter(
                            kind = entry.kind,
                            intervalDays = entry.intervalDays,
                            dailyHour = entry.dailyHour,
                            dailyMinute = entry.dailyMinute,
                            afterMs = now,
                        )
                        labPrefs.setNextDue(entry.id, next)
                        next
                    }
                    alarmScheduler.scheduleLab(entry.id, due)
                    }
                }
            }
        }
    }
}
