package com.douxev.eggshell.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.douxev.eggshell.R
import com.douxev.eggshell.modules.AppModule
import com.douxev.eggshell.modules.ModuleDeepLink
import com.douxev.eggshell.reminders.AlarmScheduler
import com.douxev.eggshell.reminders.ReminderPrefs
import com.douxev.eggshell.reminders.ReminderReceiver

/**
 * The one module widget that shows real content in every security mode.
 *
 * It reads [ReminderPrefs], the off-vault mirror that exists so alarms can fire
 * and « Pris » can be tapped while the vault is shut. Route 1 of the two the
 * [ModuleWidgetProvider] contract allows: no new disclosure, because the widget
 * shows exactly what the notification for the same dose already shows, under
 * the same opt-in label rule — `displayLabel` is null unless the user chose to
 * put a name in the clear, and the generic string is used otherwise.
 *
 * So there is no content opt-in here and no paranoid carve-out: nothing new is
 * written outside the vault by this widget existing. What it does need is the
 * decoy check every module widget makes, because a placed widget keeps its last
 * `RemoteViews` until something repaints it.
 *
 * The actions reuse [ReminderReceiver] rather than reimplementing the write.
 * That receiver already handles both halves of the problem — vault open, log
 * straight to the dose history; vault shut, queue it sealed and fold it in at
 * the next real unlock — and having a second implementation of "record a dose"
 * is how the two would eventually disagree about someone\'s medical record.
 */
class MedsWidgetProvider : ModuleWidgetProvider(AppModule.Meds) {

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        // Launchers reuse widget ids. A row left behind is not litter — it is
        // the next widget placed here silently inheriting this one\'s target.
        runCatching { WidgetConfigPrefs(context).remove(appWidgetIds) }
        super.onDeleted(context, appWidgetIds)
    }

    override fun render(context: Context, manager: AppWidgetManager, widgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_meds)

        if (runCatching { isDecoyActive(context) }.getOrDefault(false)) {
            // Say nothing and offer nothing to tap, exactly as the base class
            // does. Naming the module to explain the blank would be the leak.
            views.setTextViewText(R.id.meds_widget_title, "")
            views.setTextViewText(R.id.meds_widget_empty, "")
            views.setViewVisibility(R.id.meds_widget_icon, View.GONE)
            views.setViewVisibility(R.id.meds_widget_action_add, View.GONE)
            ROW_IDS.forEach { views.setViewVisibility(it.row, View.GONE) }
            manager.updateAppWidget(widgetId, views)
            return
        }

        views.setTextViewText(R.id.meds_widget_title, context.getString(module.labelRes))
        views.setImageViewResource(R.id.meds_widget_icon, module.iconRes)
        views.setViewVisibility(R.id.meds_widget_icon, View.VISIBLE)
        views.setViewVisibility(R.id.meds_widget_action_add, View.VISIBLE)
        views.setTextViewText(
            R.id.meds_widget_action_add,
            context.getString(R.string.module_widget_add),
        )
        views.setOnClickPendingIntent(
            R.id.meds_widget_action_add,
            pendingIntent(context, ModuleDeepLink.addIntent(context, module), REQ_ADD),
        )
        views.setOnClickPendingIntent(
            R.id.meds_widget_title,
            pendingIntent(context, ModuleDeepLink.openIntent(context, module), REQ_OPEN),
        )

        val config = runCatching { WidgetConfigPrefs(context).get(widgetId) }
            .getOrDefault(WidgetConfigPrefs.Config())
        val due = runCatching { upcoming(context, config) }.getOrDefault(emptyList())

        if (due.isEmpty()) {
            views.setViewVisibility(R.id.meds_widget_empty, View.VISIBLE)
            views.setTextViewText(
                R.id.meds_widget_empty,
                context.getString(R.string.meds_widget_nothing_due),
            )
            ROW_IDS.forEach { views.setViewVisibility(it.row, View.GONE) }
        } else {
            views.setViewVisibility(R.id.meds_widget_empty, View.GONE)
            ROW_IDS.forEachIndexed { index, ids ->
                renderRow(context, views, ids, due.getOrNull(index), index)
            }
        }

        manager.updateAppWidget(widgetId, views)
    }

    private fun renderRow(
        context: Context,
        views: RemoteViews,
        ids: RowIds,
        entry: ReminderPrefs.Entry?,
        index: Int,
    ) {
        if (entry == null) {
            views.setViewVisibility(ids.row, View.GONE)
            return
        }
        views.setViewVisibility(ids.row, View.VISIBLE)
        views.setTextViewText(
            ids.title,
            // Null unless the user opted into a name or an alias, exactly as
            // the notification resolves it. Never the real name by default.
            entry.displayLabel ?: context.getString(R.string.widget_med_title),
        )
        views.setTextViewText(ids.whenLabel, WidgetTime.relative(context, entry.nextDueAtMs))
        views.setTextViewText(ids.taken, context.getString(R.string.meds_widget_taken))
        views.setTextViewText(ids.takenAt, context.getString(R.string.meds_widget_taken_at))

        views.setOnClickPendingIntent(
            ids.body,
            pendingIntent(context, ModuleDeepLink.openIntent(context, module), REQ_OPEN),
        )
        views.setOnClickPendingIntent(
            ids.taken,
            markTakenNow(context, entry.scheduleId, index),
        )
        views.setOnClickPendingIntent(
            ids.takenAt,
            askForTime(context, entry.scheduleId, index),
        )
    }

    /** The notification\'s « Pris », sent from the home screen instead. */
    private fun markTakenNow(context: Context, scheduleId: Long, index: Int): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = AlarmScheduler.ACTION_MARK_TAKEN
            putExtra(AlarmScheduler.EXTRA_SCHEDULE_ID, scheduleId)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode(REQ_ROW_TAKEN, index),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Same recording path, but through a time picker first. */
    private fun askForTime(context: Context, scheduleId: Long, index: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode(REQ_ROW_TAKEN_AT, index),
            DoseTimeActivity.intent(context, scheduleId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /**
     * A distinct code per (action, row) inside this module\'s own slice.
     *
     * PendingIntents that match on requestCode and `filterEquals` are the same
     * object, and Intent equality ignores extras — so two rows sharing a code
     * would both record the first row\'s schedule. Which, here, means logging a
     * dose of the wrong medication.
     */
    private fun requestCode(base: Int, index: Int) =
        module.ordinal * REQ_STRIDE + base + index

    /**
     * The next doses due, honouring this instance\'s target.
     *
     * Recently-overdue entries are kept rather than filtered out, unlike the
     * reminder widget: the point of this one is to record a dose, and a dose
     * you have not logged yet is by definition already past.
     *
     * But only recently. A schedule left behind — a treatment stopped without
     * the reminder being deleted — sits months overdue, and sorting purely by
     * due time would let it hold the first row forever while today\'s actual
     * dose is pushed off the widget. Past [OVERDUE_WINDOW_MS] it is not "the
     * next dose" any more; retro-logging that far back belongs in the module,
     * which has a date picker.
     */
    private fun upcoming(
        context: Context,
        config: WidgetConfigPrefs.Config,
    ): List<ReminderPrefs.Entry> {
        val wanted = config.targetId?.takeIf { config.targetKind == TARGET_MEDICATION }
        val floor = System.currentTimeMillis() - OVERDUE_WINDOW_MS
        return ReminderPrefs(context).all()
            .filter { wanted == null || it.medicationId == wanted }
            .filter { it.nextDueAtMs >= floor }
            .sortedBy { it.nextDueAtMs }
            .take(config.rows.coerceIn(1, ROW_IDS.size))
    }

    private data class RowIds(
        val row: Int,
        val body: Int,
        val title: Int,
        val whenLabel: Int,
        val taken: Int,
        val takenAt: Int,
    )

    private companion object {
        const val TARGET_MEDICATION = "medication"
        /** Row-action request codes, offset inside this module\'s REQ_STRIDE slice. */
        const val REQ_ROW_TAKEN = 10
        const val REQ_ROW_TAKEN_AT = 20
        /** How far back a missed dose stays offerable on the widget. */
        const val OVERDUE_WINDOW_MS = 24L * 60L * 60L * 1000L

        val ROW_IDS = listOf(
            RowIds(
                R.id.meds_row_1, R.id.meds_row_body_1, R.id.meds_title_1,
                R.id.meds_when_1, R.id.meds_taken_1, R.id.meds_taken_at_1,
            ),
            RowIds(
                R.id.meds_row_2, R.id.meds_row_body_2, R.id.meds_title_2,
                R.id.meds_when_2, R.id.meds_taken_2, R.id.meds_taken_at_2,
            ),
            RowIds(
                R.id.meds_row_3, R.id.meds_row_body_3, R.id.meds_title_3,
                R.id.meds_when_3, R.id.meds_taken_3, R.id.meds_taken_at_3,
            ),
        )
    }
}
