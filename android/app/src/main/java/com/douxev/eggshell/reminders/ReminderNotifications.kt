package com.douxev.eggshell.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import com.douxev.eggshell.MainActivity
import com.douxev.eggshell.R

/**
 * Two-channel notifier used by [ReminderReceiver].
 *
 * Priority OFF (default) → default-importance channel: shade-only, silent.
 * Priority ON              → high-importance channel: heads-up, vibration, sound.
 *
 * The body is generic ("Time for a dose") by default. If the user opted into
 * showing the medication name/alias (see `NotifContentPrefs`), the resolved
 * `displayLabel` is shown in the private (unlocked-screen) version, while the
 * public lock-screen version stays generic via `setPublicVersion`.
 *
 * Medication reminders ship with a "Pris" action button. Since Wear OS
 * automatically bridges phone notifications onto a paired watch (including
 * their actions), this gives us a full-fidelity WearOS integration without
 * needing to ship a separate Wear module: the user can mark a dose as
 * taken straight from the watch face and the same `PendingIntent` fires
 * back on the phone, hits [ReminderReceiver.handleMarkTaken] which logs
 * the dose and advances the schedule.
 */
@Singleton
class ReminderNotifications @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    init { ensureChannels() }

    /**
     * Used by med-schedule alarms. [displayLabel] is the opt-in name/alias to
     * show (null = the privacy-default generic copy). It only ever shows in the
     * private notification; the lock-screen public version stays generic.
     */
    fun showMed(scheduleId: Long, displayLabel: String?, priority: Boolean) {
        val title = displayLabel ?: context.getString(R.string.reminder_title)
        val body = if (displayLabel != null) {
            context.getString(R.string.reminder_body_named)
        } else {
            context.getString(R.string.reminder_body)
        }
        post(
            notifId = MED_NOTIF_BASE + (scheduleId.toInt() and ID_MASK),
            title = title,
            body = body,
            priority = priority,
            actions = listOf(
                markTakenAction(scheduleId),
                snoozeAction(scheduleId),
                markSkippedAction(scheduleId),
            ),
        )
    }

    /** Used by lab-reminder alarms. */
    fun showLab(labId: Long, label: String, priority: Boolean) {
        // We deliberately do NOT include `label` in the body — the lockscreen
        // would otherwise show "Estradiol", "T4 libre"… in the clear, which
        // defeats the privacy intent. Use the generic title; the user opens
        // the app to see which lab is due.
        val title = context.getString(R.string.lab_reminder_title)
        post(
            notifId = LAB_NOTIF_BASE + (labId.toInt() and ID_MASK),
            title = title,
            body = context.getString(R.string.reminder_public_body),
            priority = priority,
            actions = emptyList(),
        )
    }

    /** Used by appointment alarms. Generic copy only — the professional name,
     *  place and notes never leave the encrypted vault. */
    fun showAppointment(appointmentId: Long) {
        post(
            notifId = APPOINTMENT_NOTIF_BASE + (appointmentId.toInt() and ID_MASK),
            title = context.getString(R.string.appointment_reminder_title),
            body = context.getString(R.string.appointment_reminder_body),
            priority = true,
            actions = emptyList(),
        )
    }

    private fun post(
        notifId: Int,
        title: String,
        body: String,
        priority: Boolean,
        actions: List<NotificationCompat.Action>,
    ) {
        val mgr = context.getSystemService(NotificationManager::class.java)
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            context, notifId, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val channel = if (priority) CHANNEL_PRIORITY else CHANNEL_DEFAULT
        val notifPriority = if (priority) NotificationCompat.PRIORITY_HIGH
        else NotificationCompat.PRIORITY_DEFAULT
        // Public-facing copy never reveals which lab is due / which med is up.
        // Wear OS bridging mirrors notifications to the paired watch using
        // the lockscreen visibility — a generic "Rappel" body keeps the
        // wrist-glance copy private even if the user enabled per-app
        // sensitive-content on the watch.
        val publicVersion = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notif_reminder)
            .setContentTitle(context.getString(R.string.reminder_public_title))
            .setContentText(context.getString(R.string.reminder_public_body))
            .setPriority(notifPriority)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .build()
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notif_reminder)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(notifPriority)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
        actions.forEach { builder.addAction(it) }

        // WearableExtender: hoist the first action as the primary one on the
        // watch so it shows up under the notification text without an extra
        // tap to expand. Identical content otherwise.
        if (actions.isNotEmpty()) {
            val wear = NotificationCompat.WearableExtender()
                .setHintContentIntentLaunchesActivity(false)
            actions.forEach { wear.addAction(it) }
            builder.extend(wear)
        }

        mgr.notify(notifId, builder.build())
    }

    /**
     * Cancel a med reminder's visible notification AND its "Pris" action
     * PendingIntent. Called when a schedule is deleted so a lingering shade /
     * Wear action can't fire a mark-taken for a reminder that no longer exists.
     */
    fun cancelMed(scheduleId: Long) {
        val mgr = context.getSystemService(NotificationManager::class.java)
        mgr.cancel(MED_NOTIF_BASE + (scheduleId.toInt() and ID_MASK))
        cancelActionPi(scheduleId, AlarmScheduler.ACTION_MARK_TAKEN, MARK_TAKEN_BASE)
        cancelActionPi(scheduleId, AlarmScheduler.ACTION_MARK_SKIPPED, MARK_SKIPPED_BASE)
        cancelActionPi(scheduleId, AlarmScheduler.ACTION_SNOOZE, MARK_SNOOZE_BASE)
    }

    private fun cancelActionPi(scheduleId: Long, action: String, base: Int) {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            this.action = action
            putExtra(AlarmScheduler.EXTRA_SCHEDULE_ID, scheduleId)
        }
        PendingIntent.getBroadcast(
            context,
            base + (scheduleId.toInt() and ID_MASK),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )?.cancel()
    }

    private fun markTakenAction(scheduleId: Long): NotificationCompat.Action =
        broadcastAction(
            scheduleId,
            AlarmScheduler.ACTION_MARK_TAKEN,
            MARK_TAKEN_BASE,
            android.R.drawable.checkbox_on_background,
            R.string.reminder_action_mark_taken,
        )

    private fun markSkippedAction(scheduleId: Long): NotificationCompat.Action =
        broadcastAction(
            scheduleId,
            AlarmScheduler.ACTION_MARK_SKIPPED,
            MARK_SKIPPED_BASE,
            android.R.drawable.ic_menu_close_clear_cancel,
            R.string.reminder_action_skip,
        )

    private fun snoozeAction(scheduleId: Long): NotificationCompat.Action =
        broadcastAction(
            scheduleId,
            AlarmScheduler.ACTION_SNOOZE,
            MARK_SNOOZE_BASE,
            android.R.drawable.ic_menu_recent_history,
            R.string.reminder_action_snooze,
        )

    private fun broadcastAction(
        scheduleId: Long,
        action: String,
        base: Int,
        icon: Int,
        labelRes: Int,
    ): NotificationCompat.Action {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            this.action = action
            putExtra(AlarmScheduler.EXTRA_SCHEDULE_ID, scheduleId)
        }
        val pi = PendingIntent.getBroadcast(
            context,
            // Disjoint range from MED/LAB alarm PIs (which sit in 0..LAB_BASE).
            base + (scheduleId.toInt() and ID_MASK),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(icon, context.getString(labelRes), pi).build()
    }

    private fun ensureChannels() {
        val mgr = context.getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_DEFAULT) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_DEFAULT,
                    context.getString(R.string.reminder_channel_default_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = context.getString(R.string.reminder_channel_default_description)
                }
            )
        }
        if (mgr.getNotificationChannel(CHANNEL_PRIORITY) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_PRIORITY,
                    context.getString(R.string.reminder_channel_priority_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = context.getString(R.string.reminder_channel_priority_description)
                    enableLights(true)
                    enableVibration(true)
                }
            )
        }
    }

    companion object {
        private const val CHANNEL_DEFAULT = "medication_reminders"
        private const val CHANNEL_PRIORITY = "medication_reminders_priority"
        // ID space partitioning. Each kind gets a non-overlapping 16-bit
        // range derived from its DB id. We mask DB ids to 16 bits so
        // long-running installs that wrap past 65k still don't collide
        // with a different kind. (Collisions within a kind still happen
        // by design — same scheduleId reuses the same notification slot.)
        private const val ID_MASK = 0x0000FFFF
        private const val MED_NOTIF_BASE = 0x0000_0000
        private const val LAB_NOTIF_BASE = 0x0001_0000
        private const val MARK_TAKEN_BASE = 0x0002_0000
        private const val MARK_SKIPPED_BASE = 0x0003_0000
        private const val MARK_SNOOZE_BASE = 0x0004_0000
        private const val APPOINTMENT_NOTIF_BASE = 0x0005_0000
    }
}
