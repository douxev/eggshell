package com.douxev.eggshell.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.douxev.eggshell.data.FeaturesPrefs
import com.douxev.eggshell.data.SecurePrefs
import com.douxev.eggshell.modules.enabledModules
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Enables / disables the widget receivers as `PackageManager` components.
 *
 * When the user has set a decoy PIN, the home-screen widget would expose
 * real reminder copy ("Prise programmée dans 3 j", lab labels…) to anyone
 * who has the decoy PIN. That defeats the point of decoy. We disable the
 * receiver entirely in that case: the widget vanishes from the picker and
 * any already-placed instance shows the launcher's "widget unavailable"
 * placeholder.
 *
 * Toggling the component state via `setComponentEnabledSetting` also
 * removes the widget from the picker on most launchers, which is what we
 * want — the user shouldn't be able to add a widget that leaks data.
 *
 * Outside a decoy, each module's widget follows its module: a module switched
 * off in Réglages takes its widget out of the picker, the same way it takes its
 * launcher shortcut away. The decoy always wins — see [ModuleWidgetSync].
 */
@Singleton
class WidgetVisibility @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun setEnabled(enabled: Boolean) {
        val pm = context.packageManager
        val reminderWidget = ComponentName(context, EggshellWidgetProvider::class.java)

        if (enabled) {
            // No decoy: the reminder widget comes back, and every module widget
            // takes the state its module is in.
            runCatching {
                pm.setComponentEnabledSetting(
                    reminderWidget,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
            ModuleWidgetSync.apply(context)
            return
        }

        // The reminder widget and every module widget move together. A
        // module widget carries no vault data, but its *existence in the
        // picker* names a module — which is the disclosure the decoy is there
        // to prevent, and one reachable without ever meeting the PIN prompt.
        val modules = moduleWidgets(context)
        (listOf(reminderWidget) + modules.map { it.first }).forEach { component ->
            runCatching {
                pm.setComponentEnabledSetting(
                    component,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
        }

        // Withdraw every content opt-in and empty the mirror. The providers
        // blank themselves under a decoy, so nothing is *displayed* either way
        // — but leaving note titles and journal gauges sitting in a file while
        // the decoy is active keeps a copy of exactly what the decoy exists to
        // hide. Revoked rather than merely emptied: removing the decoy later
        // must not quietly put that content back on the home screen; the user
        // turns it on again, widget by widget.
        WidgetMirrorUpdater.revokeAllOptIns(context)

        // Disabling a component does not repaint the widgets already sitting on
        // someone's home screen — those keep their last RemoteViews until
        // something replaces them. Push one final render so a placed card
        // blanks itself now rather than whenever the launcher next asks.
        //
        // Direct AppWidgetManager calls rather than a broadcast, for the reason
        // EggshellWidgetProvider.broadcastRefresh documents: a broadcast action
        // would have to be exported, letting any installed app trigger a render.
        val mgr = AppWidgetManager.getInstance(context)
        runCatching {
            if (mgr.getAppWidgetIds(reminderWidget).isNotEmpty()) {
                EggshellWidgetProvider.broadcastRefresh(context)
            }
        }
        modules.forEach { (component, provider) ->
            runCatching {
                val ids = mgr.getAppWidgetIds(component)
                if (ids.isNotEmpty()) {
                    provider.onUpdate(context.applicationContext, mgr, ids)
                }
            }
        }
    }

    fun isEnabled(): Boolean {
        val pm = context.packageManager
        val component = ComponentName(context, EggshellWidgetProvider::class.java)
        return when (pm.getComponentEnabledSetting(component)) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
            else -> true
        }
    }
}

/**
 * Each module widget's receiver follows its module's switch.
 *
 * A module turned off in Réglages should stop advertising itself outside the
 * app, which the launcher shortcuts already do: « Menstruations » sitting in
 * the widget picker of someone who switched that module off names it to anyone
 * scrolling the picker. Disabling the receiver takes it out of the picker, and
 * the system removes any copy of it already placed.
 *
 * Two rules keep this safe:
 *
 * - **The decoy wins.** While a decoy PIN is set every receiver stays disabled,
 *   whatever the modules say; this never enables anything then. The decoy
 *   check reads the vault prefs directly, so it holds even when called from a
 *   setting screen that knows nothing about decoys.
 * - **Content goes before the widget.** A placed widget of a module being
 *   switched off loses its mirrored rows and its configuration *before* its
 *   receiver is disabled, because once disabled it is never told it was
 *   removed — and a mirror row of a module the user just turned off would
 *   otherwise sit off-vault with nothing left to erase it. Nothing in the
 *   vault is touched: switching a module off hides it, it does not delete it.
 */
internal object ModuleWidgetSync {

    fun apply(context: Context) {
        val app = context.applicationContext
        if (decoyActive(app)) return
        val enabled = runCatching { FeaturesPrefs(app).enabledModules().toSet() }
            .getOrElse { return }
        val pm = app.packageManager
        val mgr = AppWidgetManager.getInstance(app)
        moduleWidgets(app).forEach { (component, provider) ->
            val wanted = provider.module in enabled
            val current = runCatching { pm.getComponentEnabledSetting(component) }
                .getOrDefault(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
            val isOn = current != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            if (wanted == isOn) return@forEach
            if (!wanted) {
                runCatching { mgr.getAppWidgetIds(component) }.getOrNull()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { WidgetMirrorUpdater.forget(app, it) }
            }
            runCatching {
                pm.setComponentEnabledSetting(
                    component,
                    if (wanted) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
        }
    }

    /** Same read as the providers': SecurePrefs, because release hashes the file name. */
    private fun decoyActive(context: Context): Boolean =
        runCatching {
            SecurePrefs.get(context, "transition_vault_prefs").contains("decoy_salt")
        }.getOrDefault(true)
}
