package com.douxev.eggshell.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sets, replaces and cancels the platform-level AlarmManager entries that wake
 * the [ReminderReceiver] up at the right time.
 *
 * On Android 12+ the user can deny SCHEDULE_EXACT_ALARM — we fall back to the
 * inexact variant so reminders still fire (within Doze tolerances).
 */
@Singleton
class AlarmScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    fun schedule(scheduleId: Long, atMs: Long) {
        setAlarm(pendingIntentFor(scheduleId), atMs, "schedule=$scheduleId")
    }

    fun cancel(scheduleId: Long) {
        alarmManager.cancel(pendingIntentFor(scheduleId))
    }

    fun scheduleLab(labId: Long, atMs: Long) {
        setAlarm(labPendingIntentFor(labId), atMs, "lab=$labId")
    }

    fun cancelLab(labId: Long) {
        alarmManager.cancel(labPendingIntentFor(labId))
    }

    private fun setAlarm(pi: PendingIntent, atMs: Long, tag: String) {
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarmManager.canScheduleExactAlarms()
        if (canExact) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
            Log.w(TAG, "exact alarms not permitted; using inexact for $tag")
        }
    }

    private fun pendingIntentFor(scheduleId: Long): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_REMINDER
            putExtra(EXTRA_SCHEDULE_ID, scheduleId)
        }
        return PendingIntent.getBroadcast(
            context,
            scheduleId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    // Lab reminders use a distinct action so their PendingIntents never collide
    // with medication ones even if numeric IDs happen to match after truncation.
    private fun labPendingIntentFor(labId: Long): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_LAB_REMINDER
            putExtra(EXTRA_LAB_ID, labId)
        }
        return PendingIntent.getBroadcast(
            context,
            labId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val ACTION_REMINDER = "com.douxev.eggshell.REMINDER"
        const val EXTRA_SCHEDULE_ID = "schedule_id"
        const val ACTION_LAB_REMINDER = "com.douxev.eggshell.LAB_REMINDER"
        const val EXTRA_LAB_ID = "lab_id"
        /** Notification-action broadcast: the user tapped "Pris" on a med
         *  reminder (on phone or, via Wear bridging, on a paired watch). */
        const val ACTION_MARK_TAKEN = "com.douxev.eggshell.MARK_TAKEN"
        private const val TAG = "AlarmScheduler"
    }
}
