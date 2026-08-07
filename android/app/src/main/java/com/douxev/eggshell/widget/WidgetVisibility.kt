package com.douxev.eggshell.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Enables / disables the widget receiver as a `PackageManager` component.
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
 */
@Singleton
class WidgetVisibility @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun setEnabled(enabled: Boolean) {
        val pm = context.packageManager
        val reminderWidget = ComponentName(context, EggshellWidgetProvider::class.java)
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }

        // The reminder widget and all nine module widgets move together. A
        // module widget carries no vault data, but its *existence in the
        // picker* names a module — which is the disclosure the decoy is there
        // to prevent, and one reachable without ever meeting the PIN prompt.
        val modules = moduleWidgets(context)
        (listOf(reminderWidget) + modules.map { it.first }).forEach { component ->
            runCatching {
                pm.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
            }
        }

        // Disabling a component does not repaint the widgets already sitting on
        // someone's home screen — those keep their last RemoteViews until
        // something replaces them. Push one final render so a placed card
        // blanks itself now rather than whenever the launcher next asks.
        //
        // Direct AppWidgetManager calls rather than a broadcast, for the reason
        // EggshellWidgetProvider.broadcastRefresh documents: a broadcast action
        // would have to be exported, letting any installed app trigger a render.
        if (!enabled) {
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
