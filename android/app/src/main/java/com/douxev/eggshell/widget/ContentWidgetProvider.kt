package com.douxev.eggshell.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.view.View
import android.widget.RemoteViews
import com.douxev.eggshell.R
import com.douxev.eggshell.modules.AppModule
import com.douxev.eggshell.modules.ModuleDeepLink

/**
 * A module widget that shows content, for every module except Traitements
 * (whose rows carry their own action buttons and therefore its own layout).
 *
 * All of these take route 2 of the [ModuleWidgetProvider] contract: what they
 * display would otherwise exist only inside the encrypted database, so it is
 * read from [WidgetContentMirror] — written only for a widget its owner
 * explicitly turned on, never in paranoid mode, kept across a lock (the home
 * screen is where it is read, and the app is locked there) and erased when the
 * consent goes.
 *
 * Which means the interesting part of this class is the three states, not the
 * drawing:
 *
 * - **Not opted in, or in paranoid mode.** Falls through to the plain door the
 *   base class draws. That is not a degraded version of the widget; it is what
 *   the user chose, and it is the default.
 * - **Opted in, nothing to show.** One neutral line. Deliberately the same line
 *   whether the mirror has not been filled yet (the opt-in was given with the
 *   app locked) or the module is simply empty: distinguishing them would tell
 *   whoever is looking at the home screen whether there is anything in there,
 *   which is itself an answer.
 * - **Opted in, mirror filled.** The rows.
 *
 * The drawing itself is split: [render] handles the header, the empty line and
 * the decoy blank, then hands the rows to [drawRows], which the two modules
 * with a layout of their own (Journal, Sport) override.
 */
abstract class ContentWidgetProvider(module: AppModule) : ModuleWidgetProvider(module) {

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        // Launchers reuse widget ids. Leaving either row behind hands the next
        // widget placed in this slot the previous one's target and content.
        WidgetMirrorUpdater.forget(context, appWidgetIds)
        super.onDeleted(context, appWidgetIds)
    }

    /**
     * Whether this widget needs the content opt-in before it may show anything.
     *
     * True for everything drawn from the vault. False for the modules whose
     * content already lives off-vault because alarms have to fire while the app
     * is shut — those show what their own notification already shows, so there
     * is no new disclosure to consent to. Analyses is the only one here;
     * Traitements is the other, and has its own provider.
     */
    protected open val requiresOptIn: Boolean = true

    /**
     * The line shown when there are no rows. For the mirrored modules it says
     * to open the app, which is what fills the mirror; a module that never
     * needs the vault overrides it with what the emptiness actually means.
     */
    protected open val emptyRes: Int = R.string.widget_content_locked

    /**
     * The reminder category whose next occurrence this widget lists after its
     * mirrored rows, or null for none. Read at draw time, see [WidgetReminderRow].
     */
    protected open val reminderCategory: String? = null

    /** The layout, and the ids of its header views. */
    protected open val layout: Layout = Layout(
        res = R.layout.widget_content,
        root = R.id.content_widget_root,
        icon = R.id.content_widget_icon,
        title = R.id.content_widget_title,
        add = R.id.content_widget_action_add,
        empty = R.id.content_widget_empty,
    )

    /**
     * The lines to draw. Defaults to the opt-in mirror; overridden by the
     * modules that have an off-vault source of their own.
     */
    protected open fun rows(
        context: Context,
        widgetId: Int,
        config: WidgetConfigPrefs.Config,
    ): List<WidgetContentMirror.Row> {
        val stored = WidgetContentMirror(context).rows(widgetId)
        val category = reminderCategory ?: return stored
        // Mirrors written before the reminder row was drawn live still carry
        // one; drop it so the widget does not list the reminder twice. Only
        // here — a Notes widget may well hold a note with that very title.
        val nextTitle = context.getString(R.string.widget_next_reminder)
        val mirrored = stored.filterNot { it.title == nextTitle }
        // Only next to content: on its own it would turn the « open the app »
        // state into something that looks like the module's whole content.
        if (mirrored.isEmpty()) return mirrored
        val next = WidgetReminderRow.next(context, category, R.string.widget_next_reminder)
            ?: return mirrored
        return mirrored.take(ROW_IDS.size - 1) + next
    }

    override fun render(context: Context, manager: AppWidgetManager, widgetId: Int) {
        val config = runCatching { WidgetConfigPrefs(context).get(widgetId) }
            .getOrDefault(WidgetConfigPrefs.Config())

        if (requiresOptIn && !(config.showsContent && optInAllowed(context))) {
            super.render(context, manager, widgetId)
            return
        }

        val views = RemoteViews(context.packageName, layout.res)
        val palette = WidgetPalette.current(context)
        WidgetPaint.card(views, palette, layout.root)

        if (runCatching { isDecoyActive(context) }.getOrDefault(false)) {
            // Say nothing and offer nothing to tap. Naming the module to
            // explain the blank would be the leak.
            views.setTextViewText(layout.title, "")
            views.setTextViewText(layout.empty, "")
            views.setViewVisibility(layout.icon, View.GONE)
            views.setViewVisibility(layout.add, View.GONE)
            hideRows(views)
            manager.updateAppWidget(widgetId, views)
            return
        }

        views.setTextViewText(layout.title, context.getString(module.labelRes))
        views.setImageViewResource(layout.icon, module.iconRes)
        views.setViewVisibility(layout.icon, View.VISIBLE)
        views.setOnClickPendingIntent(
            layout.title,
            pendingIntent(context, ModuleDeepLink.openIntent(context, module), REQ_OPEN),
        )
        WidgetPaint.onCard(views, palette, layout.title)
        WidgetPaint.icon(views, palette, layout.icon)
        WidgetPaint.muted(views, palette, layout.empty)

        if (module.hasCaptureScreen) {
            views.setViewVisibility(layout.add, View.VISIBLE)
            views.setTextViewText(layout.add, context.getString(R.string.module_widget_add))
            views.setOnClickPendingIntent(
                layout.add,
                pendingIntent(context, ModuleDeepLink.addIntent(context, module), REQ_ADD),
            )
            WidgetPaint.filledPill(views, palette, layout.add)
        } else {
            views.setViewVisibility(layout.add, View.GONE)
        }

        val rows = runCatching { rows(context, widgetId, config) }.getOrDefault(emptyList())

        if (rows.isEmpty()) {
            views.setViewVisibility(layout.empty, View.VISIBLE)
            views.setTextViewText(layout.empty, context.getString(emptyRes))
            views.setOnClickPendingIntent(
                layout.empty,
                pendingIntent(context, ModuleDeepLink.openIntent(context, module), REQ_OPEN),
            )
            hideRows(views)
        } else {
            views.setViewVisibility(layout.empty, View.GONE)
            drawRows(context, views, palette, rows, config)
        }

        manager.updateAppWidget(widgetId, views)
    }

    /** Draw [rows] into the layout. The default fills widget_content.xml. */
    protected open fun drawRows(
        context: Context,
        views: RemoteViews,
        palette: WidgetPalette?,
        rows: List<WidgetContentMirror.Row>,
        config: WidgetConfigPrefs.Config,
    ) {
        // A widget pointed at one note shows that note's opening rather than a
        // one-line preview; every other row keeps to a line.
        val singleNote = module == AppModule.Notes &&
            config.targetKind == WidgetMirrorUpdater.TARGET_NOTE
        ROW_IDS.forEachIndexed { index, ids ->
            val row = rows.getOrNull(index)
            if (row == null) {
                views.setViewVisibility(ids.row, View.GONE)
                return@forEachIndexed
            }
            views.setViewVisibility(ids.row, View.VISIBLE)
            views.setTextViewText(ids.title, row.title)
            views.setTextViewText(ids.preview, row.subtitle)
            views.setInt(ids.preview, "setMaxLines", if (singleNote) SINGLE_NOTE_LINES else 1)
            views.setViewVisibility(
                ids.preview,
                if (row.subtitle.isBlank()) View.GONE else View.VISIBLE,
            )
            if (row.badge.isBlank()) {
                views.setViewVisibility(ids.tile, View.GONE)
            } else {
                views.setViewVisibility(ids.tile, View.VISIBLE)
                views.setTextViewText(ids.tile, row.badge)
            }
            views.setOnClickPendingIntent(
                ids.row,
                // Opens the module, not the individual item. Deep-linking to
                // one would need a route that survives the unlock screen,
                // and landing on someone's note or journal entry the instant
                // the vault opens is not obviously what they asked for.
                pendingIntent(
                    context,
                    ModuleDeepLink.openIntent(context, module),
                    REQ_ROW + index,
                ),
            )
            WidgetPaint.onCard(views, palette, ids.title)
            WidgetPaint.muted(views, palette, ids.preview)
            WidgetPaint.tonalPill(views, palette, ids.tile)
        }
    }

    /** Hide every row; the default covers widget_content.xml. */
    protected open fun hideRows(views: RemoteViews) {
        ROW_IDS.forEach { views.setViewVisibility(it.row, View.GONE) }
    }

    protected data class Layout(
        val res: Int,
        val root: Int,
        val icon: Int,
        val title: Int,
        val add: Int,
        val empty: Int,
    )

    protected data class RowIds(val row: Int, val tile: Int, val title: Int, val preview: Int)

    protected companion object {
        /** Row request codes, inside each module's own REQ_STRIDE slice. */
        const val REQ_ROW = 10

        /** How much of a single note a 4x3 card shows before it ellipsises. */
        const val SINGLE_NOTE_LINES = 12

        val ROW_IDS = listOf(
            RowIds(R.id.content_row_1, R.id.content_tile_1, R.id.content_title_1, R.id.content_preview_1),
            RowIds(R.id.content_row_2, R.id.content_tile_2, R.id.content_title_2, R.id.content_preview_2),
            RowIds(R.id.content_row_3, R.id.content_tile_3, R.id.content_title_3, R.id.content_preview_3),
            RowIds(R.id.content_row_4, R.id.content_tile_4, R.id.content_title_4, R.id.content_preview_4),
        )
    }
}

// One concrete receiver per module: AppWidgetProvider is bound to a manifest
// <receiver>, and a receiver names exactly one class.
class NotesWidgetProvider : ContentWidgetProvider(AppModule.Notes)
class AppointmentsWidgetProvider : ContentWidgetProvider(AppModule.Appointments)
class BleedingWidgetProvider : ContentWidgetProvider(AppModule.Bleeding)
class PhotosWidgetProvider : ContentWidgetProvider(AppModule.Photos) {
    override val reminderCategory: String = com.douxev.eggshell.reminders.LabReminderPrefs.CATEGORY_PHOTO
}
class VoiceWidgetProvider : ContentWidgetProvider(AppModule.Voice) {
    override val reminderCategory: String = com.douxev.eggshell.reminders.LabReminderPrefs.CATEGORY_VOICE
}
class DreamsWidgetProvider : ContentWidgetProvider(AppModule.Dreams) {
    override val reminderCategory: String = com.douxev.eggshell.reminders.LabReminderPrefs.CATEGORY_DREAM
}

/**
 * Analyses. Route 1, like Traitements: [com.douxev.eggshell.reminders.LabReminderPrefs]
 * is an off-vault store that exists so lab reminders can fire with the app
 * shut, and this draws from it. No vault, and it works in every security mode.
 *
 * What it does *not* show by default is the reminder's label. That label is
 * the user's own text ("bilan hormonal T4", "contrôle E2"), and the lab
 * notification deliberately refuses to show it — a lock screen, and a home
 * screen even more so, is read by people other than its owner. So each row is
 * the generic « Bilan à prévoir » and its date, exactly what the notification
 * says, unless this widget was given the same per-widget consent as the
 * mirrored widgets; never in paranoid mode, whatever the consent says.
 */
class LabsWidgetProvider : ContentWidgetProvider(AppModule.Labs) {

    override val requiresOptIn: Boolean = false

    /** No rows here means nothing is planned — never that the app is shut. */
    override val emptyRes: Int = R.string.widget_labs_empty

    override fun rows(
        context: Context,
        widgetId: Int,
        config: WidgetConfigPrefs.Config,
    ): List<WidgetContentMirror.Row> {
        val now = System.currentTimeMillis()
        val floor = now - OVERDUE_WINDOW_MS
        val showLabels = config.showsContent && optInAllowed(context)
        val generic = context.getString(R.string.widget_lab_title)
        return com.douxev.eggshell.reminders.LabReminderPrefs(context).all()
            .filter { it.category == com.douxev.eggshell.reminders.LabReminderPrefs.CATEGORY_LAB }
            .filter { it.nextDueAtMs >= floor }
            .sortedBy { it.nextDueAtMs }
            .take(config.rows.coerceIn(1, ROW_IDS.size))
            .map { entry ->
                WidgetContentMirror.Row(
                    title = if (showLabels) entry.label.ifBlank { generic } else generic,
                    subtitle = WidgetTime.relative(context, entry.nextDueAtMs, now),
                    targetId = entry.id,
                    badge = WidgetTime.dateTile(entry.nextDueAtMs),
                )
            }
    }

    private companion object {
        /** How long a missed lab reminder stays worth showing. */
        const val OVERDUE_WINDOW_MS = 7L * 24L * 60L * 60L * 1000L
    }
}

/**
 * Journal: the latest entry's gauges as bars, in the colours the journal
 * itself uses for them. See widget_journal.xml for what is shown and what is
 * deliberately not; the rows arrive in the order [WidgetMirrorUpdater.JournalRows]
 * fixes.
 */
class JournalWidgetProvider : ContentWidgetProvider(AppModule.Journal) {

    override val layout = Layout(
        res = R.layout.widget_journal,
        root = R.id.journal_widget_root,
        icon = R.id.journal_widget_icon,
        title = R.id.journal_widget_title,
        add = R.id.journal_widget_action_add,
        empty = R.id.journal_widget_empty,
    )

    override fun drawRows(
        context: Context,
        views: RemoteViews,
        palette: WidgetPalette?,
        rows: List<WidgetContentMirror.Row>,
        config: WidgetConfigPrefs.Config,
    ) {
        val header = rows[WidgetMirrorUpdater.JournalRows.HEADER]
        val open = pendingIntent(context, ModuleDeepLink.openIntent(context, module), REQ_ROW)

        views.setViewVisibility(R.id.journal_when, View.VISIBLE)
        views.setTextViewText(R.id.journal_when, header.title)
        views.setOnClickPendingIntent(R.id.journal_when, open)
        views.setViewVisibility(
            R.id.journal_summary,
            if (header.subtitle.isBlank()) View.GONE else View.VISIBLE,
        )
        views.setTextViewText(R.id.journal_summary, header.subtitle)
        WidgetPaint.onCard(views, palette, R.id.journal_when)
        WidgetPaint.muted(views, palette, R.id.journal_summary)

        val gauges = rows.drop(1)
        GAUGE_IDS.forEachIndexed { index, ids ->
            val gauge = gauges.getOrNull(index)
            if (gauge == null || gauge.progress < 0) {
                views.setViewVisibility(ids.row, View.GONE)
                return@forEachIndexed
            }
            views.setViewVisibility(ids.row, View.VISIBLE)
            views.setTextViewText(ids.label, gauge.title)
            views.setTextViewText(ids.value, gauge.badge)
            views.setProgressBar(ids.bar, gauge.max.coerceAtLeast(1), gauge.progress, false)
            WidgetPaint.muted(views, palette, ids.label)
            WidgetPaint.onCard(views, palette, ids.value)
            WidgetPaint.gauge(views, palette, ids.bar, WidgetPalette.Role.of(gauge.accent))
        }
    }

    override fun hideRows(views: RemoteViews) {
        views.setViewVisibility(R.id.journal_when, View.GONE)
        views.setViewVisibility(R.id.journal_summary, View.GONE)
        GAUGE_IDS.forEach { views.setViewVisibility(it.row, View.GONE) }
    }

    private data class GaugeIds(val row: Int, val label: Int, val bar: Int, val value: Int)

    private companion object {
        val GAUGE_IDS = listOf(
            GaugeIds(R.id.journal_gauge_1, R.id.journal_label_1, R.id.journal_bar_1, R.id.journal_value_1),
            GaugeIds(R.id.journal_gauge_2, R.id.journal_label_2, R.id.journal_bar_2, R.id.journal_value_2),
            GaugeIds(R.id.journal_gauge_3, R.id.journal_label_3, R.id.journal_bar_3, R.id.journal_value_3),
            GaugeIds(R.id.journal_gauge_4, R.id.journal_label_4, R.id.journal_bar_4, R.id.journal_value_4),
            GaugeIds(R.id.journal_gauge_5, R.id.journal_label_5, R.id.journal_bar_5, R.id.journal_value_5),
            GaugeIds(R.id.journal_gauge_6, R.id.journal_label_6, R.id.journal_bar_6, R.id.journal_value_6),
        )
    }
}

/**
 * Sport: the module's dashboard. Three stat tiles, today's steps against the
 * goal, the next planned session and the last one logged — rows arrive in the
 * order [WidgetMirrorUpdater.SportRows] fixes, blanks included, except the next
 * session, which is read at draw time from the reminder store.
 */
class SportWidgetProvider : ContentWidgetProvider(AppModule.Sport) {

    override val layout = Layout(
        res = R.layout.widget_sport,
        root = R.id.sport_widget_root,
        icon = R.id.sport_widget_icon,
        title = R.id.sport_widget_title,
        add = R.id.sport_widget_action_add,
        empty = R.id.sport_widget_empty,
    )

    override fun drawRows(
        context: Context,
        views: RemoteViews,
        palette: WidgetPalette?,
        rows: List<WidgetContentMirror.Row>,
        config: WidgetConfigPrefs.Config,
    ) {
        val open = pendingIntent(context, ModuleDeepLink.openIntent(context, module), REQ_ROW)
        val at = { index: Int -> rows.getOrNull(index) }

        views.setViewVisibility(R.id.sport_stats, View.VISIBLE)
        views.setOnClickPendingIntent(R.id.sport_stats, open)
        STAT_IDS.forEachIndexed { index, ids ->
            val stat = at(index)
            views.setTextViewText(ids.value, stat?.title.orEmpty())
            views.setTextViewText(ids.label, stat?.subtitle.orEmpty())
            // The tile is a LinearLayout: background only, then its two texts.
            WidgetPaint.tile(views, palette, ids.tile)
            if (palette != null) {
                views.setTextColor(ids.value, palette.onAccentContainer)
                views.setTextColor(ids.label, palette.onAccentContainer)
            }
        }

        val steps = at(WidgetMirrorUpdater.SportRows.STEPS)
        if (steps == null || steps.progress < 0) {
            views.setViewVisibility(R.id.sport_steps, View.GONE)
        } else {
            // Today's figure, live: the mirror holds what the vault had at the
            // last refresh, the pedometer's sealed ledger what has been counted
            // since — every quarter of an hour, vault shut or not. A mirrored
            // figure from another day counts for nothing today.
            val today = java.time.LocalDate.now()
            val mirrored = if (steps.targetId == today.toEpochDay()) steps.progress.toLong() else 0L
            val counted = runCatching { com.douxev.eggshell.data.StepLedger.peekToday(context) }
                .getOrNull() ?: 0L
            val live = maxOf(mirrored, counted).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            views.setViewVisibility(R.id.sport_steps, View.VISIBLE)
            views.setTextViewText(
                R.id.sport_steps_title,
                context.getString(
                    R.string.widget_sport_steps,
                    java.text.NumberFormat.getIntegerInstance().format(live),
                ),
            )
            views.setTextViewText(R.id.sport_steps_sub, steps.subtitle)
            views.setProgressBar(
                R.id.sport_steps_bar,
                steps.max.coerceAtLeast(1),
                live.coerceAtMost(steps.max.coerceAtLeast(1)),
                false,
            )
            views.setOnClickPendingIntent(R.id.sport_steps, open)
            WidgetPaint.onCard(views, palette, R.id.sport_steps_title)
            WidgetPaint.muted(views, palette, R.id.sport_steps_sub)
            WidgetPaint.gauge(views, palette, R.id.sport_steps_bar, WidgetPalette.Role.of(steps.accent))
        }

        // The next session is read live from the off-vault reminder store, so
        // it moves on when the alarm fires (WidgetReminderRow); the mirror
        // keeps that slot blank.
        val nextSession = WidgetReminderRow.next(
            context,
            com.douxev.eggshell.reminders.LabReminderPrefs.CATEGORY_SPORT,
            R.string.widget_sport_next_session,
        )
        listOf(
            WidgetMirrorUpdater.SportRows.NEXT to LINE_IDS[0],
            WidgetMirrorUpdater.SportRows.LAST to LINE_IDS[1],
        ).forEach { (index, ids) ->
            val line = if (index == WidgetMirrorUpdater.SportRows.NEXT) nextSession else at(index)
            if (line == null || line.title.isBlank()) {
                views.setViewVisibility(ids.row, View.GONE)
                return@forEach
            }
            views.setViewVisibility(ids.row, View.VISIBLE)
            views.setTextViewText(ids.title, line.title)
            views.setTextViewText(ids.sub, line.subtitle)
            views.setOnClickPendingIntent(ids.row, open)
            WidgetPaint.onCard(views, palette, ids.title)
            WidgetPaint.muted(views, palette, ids.sub)
        }
    }

    override fun hideRows(views: RemoteViews) {
        views.setViewVisibility(R.id.sport_stats, View.GONE)
        views.setViewVisibility(R.id.sport_steps, View.GONE)
        LINE_IDS.forEach { views.setViewVisibility(it.row, View.GONE) }
    }

    private data class StatIds(val tile: Int, val value: Int, val label: Int)
    private data class LineIds(val row: Int, val title: Int, val sub: Int)

    private companion object {
        val STAT_IDS = listOf(
            StatIds(R.id.sport_stat_1, R.id.sport_stat_value_1, R.id.sport_stat_label_1),
            StatIds(R.id.sport_stat_2, R.id.sport_stat_value_2, R.id.sport_stat_label_2),
            StatIds(R.id.sport_stat_3, R.id.sport_stat_value_3, R.id.sport_stat_label_3),
        )
        val LINE_IDS = listOf(
            LineIds(R.id.sport_line_1, R.id.sport_line_title_1, R.id.sport_line_sub_1),
            LineIds(R.id.sport_line_2, R.id.sport_line_title_2, R.id.sport_line_sub_2),
        )
    }
}
