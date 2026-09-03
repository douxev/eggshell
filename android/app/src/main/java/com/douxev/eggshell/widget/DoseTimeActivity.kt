package com.douxev.eggshell.widget

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import com.douxev.eggshell.R
import com.douxev.eggshell.data.SecurityPrefs
import com.douxev.eggshell.reminders.AlarmScheduler
import com.douxev.eggshell.reminders.ReminderReceiver
import com.douxev.eggshell.ui.theme.EggshellTheme
import java.util.Calendar

/**
 * Asks what time a dose was actually taken, then records it.
 *
 * Reached only from the Traitements widget\'s 🕐 button. It exists because the
 * alternative is worse than it looks: someone who took their dose at 8h and
 * reaches their phone at 14h was otherwise recording 14h. That corrupts the one
 * thing the dose log is for — punctuality — and it does it invisibly, because a
 * wrong timestamp looks exactly like a right one.
 *
 * **Needs no vault.** It collects an hour and hands it to [ReminderReceiver],
 * which already knows how to record a dose with the vault open *or* shut
 * (queued sealed, folded in at the next real unlock). So this works from the
 * lock screen, in every security mode — which is the point of being able to do
 * it from the home screen at all.
 *
 * **Says nothing.** Generic title, medication never named: this window opens
 * over whatever is on screen, in front of whoever is holding the phone. Only
 * the schedule id travels, and it is resolved inside the receiver behind the
 * sealed mirror.
 */
class DoseTimeActivity : AppCompatActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The app-wide rule has to hold for a window opened from the launcher
        // too: this one shows that a dose is being logged.
        if (runCatching { SecurityPrefs(this).blockScreenshots.value }.getOrDefault(true)) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }

        val scheduleId = intent.getLongExtra(AlarmScheduler.EXTRA_SCHEDULE_ID, -1L)
        if (scheduleId < 0) {
            finish()
            return
        }

        val now = Calendar.getInstance()
        setContent {
            EggshellTheme {
                val state = rememberTimePickerState(
                    initialHour = now.get(Calendar.HOUR_OF_DAY),
                    initialMinute = now.get(Calendar.MINUTE),
                    is24Hour = android.text.format.DateFormat.is24HourFormat(this),
                )
                AlertDialog(
                    onDismissRequest = { finish() },
                    title = { Text(getString(R.string.meds_widget_taken_at_title)) },
                    text = { TimePicker(state = state) },
                    confirmButton = {
                        TextButton(onClick = {
                            record(scheduleId, state.hour, state.minute)
                            finish()
                        }) { Text(getString(R.string.action_ok)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { finish() }) {
                            Text(getString(R.string.action_cancel))
                        }
                    },
                )
            }
        }
    }

    private fun record(scheduleId: Long, hour: Int, minute: Int) {
        // "The most recent time it was HH:MM" — today if that has passed,
        // yesterday otherwise. See TakenAtClock for why the second case is the
        // common one rather than an edge case.
        val takenAt = TakenAtClock.mostRecentOccurrence(
            hour, minute, System.currentTimeMillis(),
        )

        sendBroadcast(
            Intent(this, ReminderReceiver::class.java).apply {
                action = AlarmScheduler.ACTION_MARK_TAKEN
                putExtra(AlarmScheduler.EXTRA_SCHEDULE_ID, scheduleId)
                putExtra(AlarmScheduler.EXTRA_TAKEN_AT_MS, takenAt)
                // Explicit component AND package: an implicit broadcast with
                // our action would be visible to any app that registered it.
                setPackage(packageName)
            }
        )
    }

    companion object {
        fun intent(context: Context, scheduleId: Long): Intent =
            Intent(context, DoseTimeActivity::class.java).apply {
                putExtra(AlarmScheduler.EXTRA_SCHEDULE_ID, scheduleId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
    }
}
