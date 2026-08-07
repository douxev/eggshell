package com.douxev.eggshell.modules

import android.content.Context
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.douxev.eggshell.data.FeaturesPrefs
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Publishes one launcher shortcut per enabled module — and publishes none at
 * all while a decoy PIN is configured.
 *
 * **Why the decoy gate is the whole design.** A long-press on the app icon
 * shows its shortcuts to whoever is holding the phone, with no unlock of any
 * kind. A list reading « Médics · Analyses · Menstruations » under an icon
 * claiming to be a notes app answers, in one gesture, the exact question the
 * decoy exists to refuse. The facade would survive the PIN prompt and fall at
 * the launcher.
 *
 * So the rule is not "hide the labels" but "publish nothing": with a decoy set,
 * [ShortcutManagerCompat.removeAllDynamicShortcuts] leaves the icon with the
 * bare launcher menu any app without shortcuts has. Absence is the only state
 * that reveals nothing — a generic shortcut list is itself a tell, because a
 * plain notes app has no reason to hide what its shortcuts do.
 *
 * Dynamic rather than static (`res/xml/shortcuts.xml`) for the same reason:
 * static shortcuts are published by the system from the manifest at install
 * time, and cannot be withdrawn — only disabled, which leaves them visible and
 * greyed. There is no version of that which is safe here.
 */
@Singleton
class ModuleShortcuts @Inject constructor(
    @ApplicationContext private val context: Context,
    private val features: FeaturesPrefs,
    private val alias: com.douxev.eggshell.data.AppAliasManager,
) {

    /**
     * Rebuild the shortcut list from the current state.
     *
     * [decoyActive] is passed in rather than read here so this class never has
     * to reach into the security layer, and so the two callers that already
     * know the answer — the decoy being set, and the cold-start resync — cannot
     * disagree with it.
     */
    fun refresh(decoyActive: Boolean) {
        runCatching {
            if (decoyActive || disguised()) {
                ShortcutManagerCompat.removeAllDynamicShortcuts(context)
                return@runCatching
            }
            val max = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context)
                .coerceAtLeast(1)
            val shortcuts = features.enabledModules()
                // Launchers silently drop everything past their own limit, and
                // which ones they drop is unspecified. Taking the head of a
                // ranked list means the user loses the least-used modules
                // rather than an arbitrary handful.
                .take(max)
                .map { module ->
                    val label = context.getString(module.labelRes)
                    ShortcutInfoCompat.Builder(context, shortcutId(module))
                        .setShortLabel(label)
                        .setLongLabel(label)
                        .setIcon(IconCompat.createWithResource(context, module.iconRes))
                        .setIntent(ModuleDeepLink.openIntent(context, module))
                        .build()
                }
            // setDynamicShortcuts replaces the whole set in one call, so a
            // module switched off disappears in the same step that re-publishes
            // the rest. Adding and removing separately would leave a window
            // where a disabled module still had a live shortcut.
            ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
        }
    }

    /** Drop every shortcut, whatever the current settings say. */
    fun clear() {
        runCatching { ShortcutManagerCompat.removeAllDynamicShortcuts(context) }
    }

    /**
     * True when the launcher icon is wearing one of the masking aliases.
     *
     * Withdrawing the shortcuts here goes past the letter of "hide them under
     * decoy", but not past its reason: the point is that the launcher must not
     * disclose the module structure. Masking is the same promise made a
     * different way — an icon reading « Calculatrice » whose long-press menu
     * offers « Médics · Analyses · Menstruations » has not hidden the app, it
     * has annotated it. Publishing there would defeat a protection the user has
     * deliberately switched on.
     */
    private fun disguised(): Boolean =
        runCatching {
            alias.currentVariant() != com.douxev.eggshell.data.AppAliasManager.Variant.DEFAULT
        }.getOrDefault(false)

    private fun shortcutId(module: AppModule) = "module_${module.id}"
}
