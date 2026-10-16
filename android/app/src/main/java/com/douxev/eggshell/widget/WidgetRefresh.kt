package com.douxev.eggshell.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context

/**
 * Re-render every live widget instance now, in-process.
 *
 * In-process rather than by broadcast, for the reason
 * [EggshellWidgetProvider.broadcastRefresh] already documents: a refresh
 * action would have to be exported, which lets any installed app force this
 * app to draw its content on demand.
 *
 * The module widgets are included, which the reminder-only refresh did not do.
 * They rendered nothing before, so nothing could go stale; now that they show
 * doses, notes and dates, a widget that keeps yesterday's card is showing
 * something that is simply false — and in the lock direction it is showing
 * something it is no longer allowed to.
 *
 * Everything that changes what a widget shows calls this: logging a dose,
 * editing a schedule, writing a note, unlocking, and — the one that matters
 * most — locking.
 */
object WidgetRefresh {

    fun refreshAll(context: Context) {
        val app = context.applicationContext
        val mgr = AppWidgetManager.getInstance(app)

        push(app, mgr, ComponentName(app, EggshellWidgetProvider::class.java)) { ids ->
            EggshellWidgetProvider().onUpdate(app, mgr, ids)
        }
        moduleWidgets(app).forEach { (component, provider) ->
            push(app, mgr, component) { ids -> provider.onUpdate(app, mgr, ids) }
        }
    }

    /** Coalesces [refreshAllAsync] requests: at most one queued behind a running one. */
    private val queued = java.util.concurrent.atomic.AtomicBoolean(false)

    /** One background thread; repaints are sequential anyway. */
    private val worker: java.util.concurrent.ExecutorService by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { task ->
            Thread(task, "widget-repaint").apply { isDaemon = true }
        }
    }

    /**
     * [refreshAll], off the calling thread.
     *
     * For the vault's lock and unlock, which happen on the main thread — a lock
     * runs inside `onUserLeaveHint` — while a repaint reads and unseals the
     * reminder stores and every widget's configuration through the Keystore.
     * Nothing about the transition needs the repaint to have finished: the
     * mirror is not touched by a lock, so this only refreshes what is already
     * there. Requests that arrive while one is waiting fold into it.
     */
    fun refreshAllAsync(context: Context) {
        val app = context.applicationContext
        if (!queued.compareAndSet(false, true)) return
        runCatching {
            worker.execute {
                queued.set(false)
                runCatching { refreshAll(app) }
                    .onFailure { android.util.Log.e("WidgetRefresh", "async repaint failed", it) }
            }
        }.onFailure { queued.set(false) }
    }

    /** Re-render only the widgets of one module. */
    fun refreshModule(context: Context, module: com.douxev.eggshell.modules.AppModule) {
        val app = context.applicationContext
        val mgr = AppWidgetManager.getInstance(app)
        moduleWidgets(app)
            .filter { (_, provider) -> provider.module == module }
            .forEach { (component, provider) ->
                push(app, mgr, component) { ids -> provider.onUpdate(app, mgr, ids) }
            }
    }

    private inline fun push(
        context: Context,
        mgr: AppWidgetManager,
        component: ComponentName,
        render: (IntArray) -> Unit,
    ) {
        // getAppWidgetIds throws if the component is disabled — which is
        // exactly the decoy state, where doing nothing is the correct answer.
        val ids = runCatching { mgr.getAppWidgetIds(component) }.getOrDefault(IntArray(0))
        if (ids.isEmpty()) return
        runCatching { render(ids) }
            .onFailure { android.util.Log.e("WidgetRefresh", "render failed for $component", it) }
    }
}
