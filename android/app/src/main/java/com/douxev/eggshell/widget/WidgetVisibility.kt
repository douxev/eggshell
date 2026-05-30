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
        val component = ComponentName(context, EggshellWidgetProvider::class.java)
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        pm.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)

        // If we're disabling, also wipe any currently-installed widgets so
        // they don't keep displaying stale data until the launcher notices.
        if (!enabled) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = runCatching { mgr.getAppWidgetIds(component) }.getOrDefault(IntArray(0))
            if (ids.isNotEmpty()) {
                // We can't actually remove the user's widget instance, but we
                // can re-broadcast UPDATE so the receiver gets called; it'll
                // soon receive an enabled=false state and stop responding.
                EggshellWidgetProvider.broadcastRefresh(context)
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
