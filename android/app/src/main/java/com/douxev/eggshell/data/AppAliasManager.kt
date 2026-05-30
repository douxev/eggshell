package com.douxev.eggshell.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Switches between launcher icon + label variants by enabling exactly one
 * activity-alias from the manifest at a time.
 *
 * Only one alias must be ENABLED at any moment, otherwise the launcher
 * shows multiple shortcuts.
 */
@Singleton
class AppAliasManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    enum class Variant(val componentName: String, val labelResName: String) {
        DEFAULT("com.douxev.eggshell.MainActivity", "app_name"),
        NOTES("com.douxev.eggshell.alias.NotesAlias", "alias_notes"),
        CALCULATOR("com.douxev.eggshell.alias.CalculatorAlias", "alias_calculator"),
        WEATHER("com.douxev.eggshell.alias.WeatherAlias", "alias_weather"),
    }

    fun setVariant(target: Variant) {
        val pm = context.packageManager
        // Enforce exactly one launcher entry. DEFAULT (.MainActivity) MUST
        // be disabled whenever any alias is active — otherwise an attacker
        // with shell access can `am start -n <pkg>/.MainActivity` and see
        // the real Eggshell label/icon in Recents, defeating the alias.
        Variant.values().forEach { v ->
            val state = if (v == target) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            pm.setComponentEnabledSetting(
                ComponentName(context, v.componentName),
                state,
                PackageManager.DONT_KILL_APP,
            )
        }
    }

    /**
     * Returns the variant currently advertised in the launcher. Falls back to
     * DEFAULT when nothing is explicitly enabled.
     */
    fun currentVariant(): Variant {
        val pm = context.packageManager
        return Variant.values().firstOrNull { v ->
            val state = pm.getComponentEnabledSetting(ComponentName(context, v.componentName))
            state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } ?: Variant.DEFAULT
    }
}
