package com.douxev.eggshell.widget

import android.content.Context
import androidx.annotation.StringRes
import com.douxev.eggshell.reminders.LabReminderPrefs
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * "Prochain rappel · dans 2 j · 18:00" — the next reminder of one category, as
 * a widget row, read at draw time.
 *
 * Read live rather than mirrored. The reminder store ([LabReminderPrefs])
 * already lives off-vault so alarms can fire with the app shut, so drawing from
 * it costs no new disclosure — and a mirrored copy went stale the moment the
 * alarm fired and the reminder moved on, which happens while the vault is shut
 * and nothing can rebuild the mirror. Drawn live, the row moves with every
 * repaint the reminder machinery already pushes.
 *
 * Only the time, never the label: the label is the user's free text, and the
 * notification for these categories deliberately shows a neutral title instead
 * of it. The widget keeps to what the notification would say.
 */
internal object WidgetReminderRow {

    /** Overdue by more than a day is not "next" any more. */
    private const val STALE_AFTER_MS = 24L * 60 * 60 * 1000

    fun next(
        context: Context,
        category: String,
        @StringRes titleRes: Int,
        now: Long = System.currentTimeMillis(),
    ): WidgetContentMirror.Row? {
        val next = LabReminderPrefs(context).all()
            .filter { it.category == category && it.nextDueAtMs >= now - STALE_AFTER_MS }
            .minByOrNull { it.nextDueAtMs } ?: return null
        return WidgetContentMirror.Row(
            title = context.getString(titleRes),
            subtitle = WidgetTime.relative(context, next.nextDueAtMs, now) + " · " +
                clock(next.nextDueAtMs),
            targetId = next.id,
            badge = WidgetTime.dateTile(next.nextDueAtMs),
        )
    }

    private fun clock(atMs: Long): String =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
            .withLocale(Locale.getDefault())
            .format(Instant.ofEpochMilli(atMs).atZone(ZoneId.systemDefault()))
}
