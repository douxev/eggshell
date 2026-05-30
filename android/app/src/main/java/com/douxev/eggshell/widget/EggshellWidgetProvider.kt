package com.douxev.eggshell.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.douxev.eggshell.MainActivity
import com.douxev.eggshell.R
import com.douxev.eggshell.reminders.LabReminderPrefs
import com.douxev.eggshell.reminders.ReminderPrefs

/**
 * Home-screen widget. Reads from the unencrypted ReminderPrefs +
 * LabReminderPrefs mirror so it doesn't need the vault to be unlocked,
 * and renders up to three upcoming reminders. Two action pills:
 *   - "Ouvrir" lands on the home screen
 *   - "Noter" deep-links into the journal add screen so the user can log
 *     a feeling without unlocking-then-tapping-the-FAB
 *
 * Refresh strategy: no periodic update (battery cost). Anything that mutates
 * a reminder broadcasts [ACTION_REFRESH] so this provider re-renders.
 */
class EggshellWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        appWidgetIds.forEach { id ->
            runCatching { renderWidget(context, appWidgetManager, id) }
                .onFailure {
                    android.util.Log.e(TAG, "render failed for widget $id", it)
                    runCatching { renderFallback(context, appWidgetManager, id) }
                }
        }
    }

    // No custom onReceive: AppWidgetProvider's default handles
    // APPWIDGET_UPDATE for us and routes to onUpdate(). We removed the
    // ACTION_REFRESH branch — internal refreshes now go through
    // refreshNow() (direct AppWidgetManager call), and that action is no
    // longer advertised in the manifest, so external apps can no longer
    // force a re-render that would expose lab labels on the home screen.

    private fun renderWidget(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_transition)
        views.setTextViewText(R.id.widget_eyebrow, context.getString(R.string.widget_eyebrow))
        views.setTextViewText(R.id.widget_action_open, context.getString(R.string.widget_action_open))
        views.setTextViewText(R.id.widget_action_journal, context.getString(R.string.widget_action_journal))

        // Defense in depth: hide all real reminder text when a decoy PIN is
        // configured. The widget receiver should already be disabled via
        // WidgetVisibility when decoy is set, but a stale binding shouldn't
        // leak data.
        val hasDecoy = runCatching { isDecoyActive(context) }.getOrDefault(false)
        val rows = if (hasDecoy) emptyList()
        else runCatching { upcomingReminders(context, limit = 3) }.getOrDefault(emptyList())

        if (rows.isEmpty()) {
            views.setViewVisibility(R.id.widget_row_1, View.GONE)
            views.setViewVisibility(R.id.widget_row_2, View.GONE)
            views.setViewVisibility(R.id.widget_row_3, View.GONE)
            views.setViewVisibility(R.id.widget_empty, View.VISIBLE)
            views.setTextViewText(
                R.id.widget_empty,
                context.getString(R.string.widget_default_subtitle),
            )
        } else {
            views.setViewVisibility(R.id.widget_empty, View.GONE)
            renderRow(views, rows.getOrNull(0), R.id.widget_row_1, R.id.widget_title_1, R.id.widget_when_1, context)
            renderRow(views, rows.getOrNull(1), R.id.widget_row_2, R.id.widget_title_2, R.id.widget_when_2, context)
            renderRow(views, rows.getOrNull(2), R.id.widget_row_3, R.id.widget_title_3, R.id.widget_when_3, context)
        }

        // Click targets. NOTE: we deliberately do NOT set an OnClickPendingIntent
        // on the root LinearLayout. Several launcher implementations collapse all
        // child taps into the parent's PendingIntent when one is set on the
        // parent, which means the "Noter" pill silently fires the "Ouvrir"
        // intent instead of its own. Putting click handlers only on the
        // action pills + the reminder rows ensures each tap goes where the
        // user expects.
        views.setOnClickPendingIntent(R.id.widget_action_open, openAppPendingIntent(context))
        views.setOnClickPendingIntent(R.id.widget_action_journal, openJournalPendingIntent(context))
        // Tapping a reminder row opens the app to the home screen — useful
        // when the user wants to "mark as taken" or see details. The
        // eyebrow + empty-state texts also forward to "open" for the same
        // tap-anywhere intuition we lost by dropping the root handler.
        views.setOnClickPendingIntent(R.id.widget_eyebrow, openAppPendingIntent(context))
        views.setOnClickPendingIntent(R.id.widget_row_1, openAppPendingIntent(context))
        views.setOnClickPendingIntent(R.id.widget_row_2, openAppPendingIntent(context))
        views.setOnClickPendingIntent(R.id.widget_row_3, openAppPendingIntent(context))
        views.setOnClickPendingIntent(R.id.widget_empty, openAppPendingIntent(context))

        manager.updateAppWidget(widgetId, views)
    }

    private fun renderRow(
        views: RemoteViews,
        row: WidgetRow?,
        rowId: Int,
        titleId: Int,
        whenId: Int,
        context: Context,
    ) {
        if (row == null) {
            views.setViewVisibility(rowId, View.GONE)
            return
        }
        views.setViewVisibility(rowId, View.VISIBLE)
        views.setTextViewText(titleId, row.title)
        views.setTextViewText(whenId, row.whenLabel)
    }

    /** Static empty-state rendering when the dynamic render path throws. */
    private fun renderFallback(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_transition)
        views.setTextViewText(R.id.widget_eyebrow, context.getString(R.string.widget_eyebrow))
        views.setTextViewText(R.id.widget_action_open, context.getString(R.string.widget_action_open))
        views.setTextViewText(R.id.widget_action_journal, context.getString(R.string.widget_action_journal))
        views.setViewVisibility(R.id.widget_row_1, View.GONE)
        views.setViewVisibility(R.id.widget_row_2, View.GONE)
        views.setViewVisibility(R.id.widget_row_3, View.GONE)
        views.setViewVisibility(R.id.widget_empty, View.VISIBLE)
        views.setTextViewText(R.id.widget_empty, context.getString(R.string.widget_default_subtitle))
        views.setOnClickPendingIntent(R.id.widget_action_open, openAppPendingIntent(context))
        views.setOnClickPendingIntent(R.id.widget_action_journal, openJournalPendingIntent(context))
        views.setOnClickPendingIntent(R.id.widget_eyebrow, openAppPendingIntent(context))
        views.setOnClickPendingIntent(R.id.widget_empty, openAppPendingIntent(context))
        manager.updateAppWidget(widgetId, views)
    }

    private fun openAppPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context, REQ_OPEN, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openJournalPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            action = ACTION_JOURNAL_QUICK_ADD
            putExtra(EXTRA_DEEPLINK, DEEPLINK_JOURNAL_ADD)
        }
        return PendingIntent.getActivity(
            context, REQ_JOURNAL, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private data class WidgetRow(val title: String, val whenLabel: String)

    /** Up to [limit] upcoming reminders across med schedules + labs. */
    private fun upcomingReminders(context: Context, limit: Int): List<WidgetRow> {
        val now = System.currentTimeMillis()
        val medRows = ReminderPrefs(context).all()
            .filter { it.nextDueAtMs >= now }
            .map { entry ->
                Pair(
                    entry.nextDueAtMs,
                    WidgetRow(
                        title = context.getString(R.string.widget_med_title),
                        whenLabel = relativeLabel(context, entry.nextDueAtMs),
                    ),
                )
            }
        val labRows = LabReminderPrefs(context).all()
            .filter { it.nextDueAtMs >= now }
            .map { entry ->
                Pair(
                    entry.nextDueAtMs,
                    WidgetRow(
                        title = entry.label,
                        whenLabel = relativeLabel(context, entry.nextDueAtMs),
                    ),
                )
            }
        return (medRows + labRows)
            .sortedBy { it.first }
            .take(limit)
            .map { it.second }
    }

    /**
     * Reads the VaultPrefs SharedPreferences directly so we don't need the
     * Hilt graph from a BroadcastReceiver context.
     */
    private fun isDecoyActive(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(
            "transition_vault_prefs",
            Context.MODE_PRIVATE,
        )
        return prefs.contains("decoy_salt")
    }

    private fun relativeLabel(context: Context, atMs: Long): String {
        val now = System.currentTimeMillis()
        val diff = atMs - now
        if (diff <= 0) return context.getString(R.string.widget_now)
        val days = (diff / 86_400_000L).toInt()
        return when {
            days == 0 -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(atMs))
            days == 1 -> context.getString(R.string.widget_tomorrow)
            days < 7 -> context.getString(R.string.widget_in_days_fmt, days)
            else -> SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(atMs))
        }
    }

    companion object {
        private const val TAG = "TransitionWidget"
        private const val REQ_OPEN = 0
        private const val REQ_JOURNAL = 1
        const val ACTION_JOURNAL_QUICK_ADD = "com.douxev.eggshell.widget.JOURNAL_QUICK_ADD"
        const val EXTRA_DEEPLINK = "deeplink"
        const val DEEPLINK_JOURNAL_ADD = "journal_add"

        /**
         * Re-render all live widget instances NOW, in-process. Replaces the
         * old `sendBroadcast(ACTION_REFRESH)` approach — broadcasts traverse
         * the system queue and ACTION_REFRESH would have to be exported,
         * letting any installed app trigger a render. Calling
         * AppWidgetManager directly keeps the refresh entirely inside our
         * own process.
         */
        fun broadcastRefresh(context: Context) {
            val app = context.applicationContext
            val mgr = AppWidgetManager.getInstance(app)
            val ids = runCatching {
                mgr.getAppWidgetIds(ComponentName(app, EggshellWidgetProvider::class.java))
            }.getOrDefault(IntArray(0))
            if (ids.isEmpty()) return
            EggshellWidgetProvider().onUpdate(app, mgr, ids)
        }
    }
}
