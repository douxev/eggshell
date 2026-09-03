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
 * explicitly turned on, never in paranoid mode, and emptied whenever the vault
 * locks.
 *
 * Which means the interesting part of this class is the three states, not the
 * drawing:
 *
 * - **Not opted in.** Falls through to the plain door the base class draws.
 *   That is not a degraded version of the widget; it is what the user chose,
 *   and it is the default.
 * - **Opted in, nothing to show.** One neutral line. Deliberately the same line
 *   whether the vault is locked or the module is simply empty: distinguishing
 *   them would tell whoever is looking at the home screen whether there is
 *   anything in there, which is itself an answer.
 * - **Opted in, mirror filled.** The rows.
 */
abstract class ContentWidgetProvider(module: AppModule) : ModuleWidgetProvider(module) {

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        // Launchers reuse widget ids. Leaving either row behind hands the next
        // widget placed in this slot the previous one\'s target and content.
        runCatching { WidgetConfigPrefs(context).remove(appWidgetIds) }
        runCatching { WidgetContentMirror(context).remove(appWidgetIds) }
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
     * The lines to draw. Defaults to the opt-in mirror; overridden by the
     * modules that have an off-vault source of their own.
     */
    protected open fun rows(
        context: Context,
        widgetId: Int,
        config: WidgetConfigPrefs.Config,
    ): List<WidgetContentMirror.Row> = WidgetContentMirror(context).rows(widgetId)

    override fun render(context: Context, manager: AppWidgetManager, widgetId: Int) {
        val config = runCatching { WidgetConfigPrefs(context).get(widgetId) }
            .getOrDefault(WidgetConfigPrefs.Config())

        if (requiresOptIn && !config.showsContent) {
            super.render(context, manager, widgetId)
            return
        }

        val views = RemoteViews(context.packageName, R.layout.widget_content)

        if (runCatching { isDecoyActive(context) }.getOrDefault(false)) {
            // Say nothing and offer nothing to tap. Naming the module to
            // explain the blank would be the leak.
            views.setTextViewText(R.id.content_widget_title, "")
            views.setTextViewText(R.id.content_widget_empty, "")
            views.setViewVisibility(R.id.content_widget_icon, View.GONE)
            views.setViewVisibility(R.id.content_widget_action_add, View.GONE)
            ROW_IDS.forEach { views.setViewVisibility(it.row, View.GONE) }
            manager.updateAppWidget(widgetId, views)
            return
        }

        views.setTextViewText(R.id.content_widget_title, context.getString(module.labelRes))
        views.setImageViewResource(R.id.content_widget_icon, module.iconRes)
        views.setViewVisibility(R.id.content_widget_icon, View.VISIBLE)
        views.setOnClickPendingIntent(
            R.id.content_widget_title,
            pendingIntent(context, ModuleDeepLink.openIntent(context, module), REQ_OPEN),
        )

        if (module.hasCaptureScreen) {
            views.setViewVisibility(R.id.content_widget_action_add, View.VISIBLE)
            views.setTextViewText(
                R.id.content_widget_action_add,
                context.getString(R.string.module_widget_add),
            )
            views.setOnClickPendingIntent(
                R.id.content_widget_action_add,
                pendingIntent(context, ModuleDeepLink.addIntent(context, module), REQ_ADD),
            )
        } else {
            views.setViewVisibility(R.id.content_widget_action_add, View.GONE)
        }

        val rows = runCatching { rows(context, widgetId, config) }.getOrDefault(emptyList())

        if (rows.isEmpty()) {
            views.setViewVisibility(R.id.content_widget_empty, View.VISIBLE)
            views.setTextViewText(
                R.id.content_widget_empty,
                context.getString(R.string.widget_content_locked),
            )
            views.setOnClickPendingIntent(
                R.id.content_widget_empty,
                pendingIntent(context, ModuleDeepLink.openIntent(context, module), REQ_OPEN),
            )
            ROW_IDS.forEach { views.setViewVisibility(it.row, View.GONE) }
        } else {
            views.setViewVisibility(R.id.content_widget_empty, View.GONE)
            ROW_IDS.forEachIndexed { index, ids ->
                val row = rows.getOrNull(index)
                if (row == null) {
                    views.setViewVisibility(ids.row, View.GONE)
                    return@forEachIndexed
                }
                views.setViewVisibility(ids.row, View.VISIBLE)
                views.setTextViewText(ids.title, row.title)
                views.setTextViewText(ids.preview, row.subtitle)
                views.setViewVisibility(
                    ids.preview,
                    if (row.subtitle.isBlank()) View.GONE else View.VISIBLE,
                )
                views.setOnClickPendingIntent(
                    ids.row,
                    // Opens the module, not the individual item. Deep-linking to
                    // one would need a route that survives the unlock screen,
                    // and landing on someone\'s note or journal entry the instant
                    // the vault opens is not obviously what they asked for.
                    pendingIntent(
                        context,
                        ModuleDeepLink.openIntent(context, module),
                        REQ_ROW + index,
                    ),
                )
            }
        }

        manager.updateAppWidget(widgetId, views)
    }

    protected data class RowIds(val row: Int, val title: Int, val preview: Int)

    protected companion object {
        /** Row request codes, inside each module\'s own REQ_STRIDE slice. */
        const val REQ_ROW = 10

        val ROW_IDS = listOf(
            RowIds(R.id.content_row_1, R.id.content_title_1, R.id.content_preview_1),
            RowIds(R.id.content_row_2, R.id.content_title_2, R.id.content_preview_2),
            RowIds(R.id.content_row_3, R.id.content_title_3, R.id.content_preview_3),
            RowIds(R.id.content_row_4, R.id.content_title_4, R.id.content_preview_4),
        )
    }
}

// One concrete receiver per module: AppWidgetProvider is bound to a manifest
// <receiver>, and a receiver names exactly one class.
class NotesWidgetProvider : ContentWidgetProvider(AppModule.Notes)

/**
 * Analyses. Route 1, like Traitements: [com.douxev.eggshell.reminders.LabReminderPrefs]
 * is an off-vault mirror that exists so lab reminders can fire with the app
 * shut, and this shows what those reminders already show. No opt-in, and it
 * works in every security mode.
 *
 * The label is the user\'s own text for the reminder ("prise de sang",
 * "contrôle E2"), sealed at rest in that mirror and already displayed by the
 * notification — so it is theirs to have chosen, not something this widget
 * discloses on its own.
 */
class LabsWidgetProvider : ContentWidgetProvider(AppModule.Labs) {

    override val requiresOptIn: Boolean = false

    override fun rows(
        context: Context,
        widgetId: Int,
        config: WidgetConfigPrefs.Config,
    ): List<WidgetContentMirror.Row> {
        val floor = System.currentTimeMillis() - OVERDUE_WINDOW_MS
        return com.douxev.eggshell.reminders.LabReminderPrefs(context).all()
            .filter { it.nextDueAtMs >= floor }
            .sortedBy { it.nextDueAtMs }
            .take(config.rows.coerceIn(1, ROW_IDS.size))
            .map { entry ->
                WidgetContentMirror.Row(
                    title = entry.label,
                    subtitle = WidgetTime.relative(context, entry.nextDueAtMs),
                    targetId = entry.id,
                )
            }
    }

    private companion object {
        /** How long a missed lab reminder stays worth showing. */
        const val OVERDUE_WINDOW_MS = 7L * 24L * 60L * 60L * 1000L
    }
}
class JournalWidgetProvider : ContentWidgetProvider(AppModule.Journal)
class AppointmentsWidgetProvider : ContentWidgetProvider(AppModule.Appointments)
class WeightWidgetProvider : ContentWidgetProvider(AppModule.Weight)
class BleedingWidgetProvider : ContentWidgetProvider(AppModule.Bleeding)
class PhotosWidgetProvider : ContentWidgetProvider(AppModule.Photos)
class VoiceWidgetProvider : ContentWidgetProvider(AppModule.Voice)
class DreamsWidgetProvider : ContentWidgetProvider(AppModule.Dreams)
class SportWidgetProvider : ContentWidgetProvider(AppModule.Sport)
