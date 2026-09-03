package com.douxev.eggshell.modules

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.douxev.eggshell.R
import com.douxev.eggshell.data.FeaturesPrefs
import com.douxev.eggshell.data.ModuleBadgePrefs

/**
 * The modules the app exposes *outside itself* — one launcher shortcut and one
 * home-screen widget each.
 *
 * Everything that leaves the app now reads from this one table: the shortcut
 * publisher, the widget providers and the deep-link router. That matters more
 * here than it usually would, because each entry is a name that ends up
 * rendered on someone's home screen. A module added to the launcher grid but
 * forgotten in a second, hand-maintained list would either silently lack its
 * shortcut, or — far worse in the other direction — keep publishing one after
 * the module was turned off.
 *
 * [ModuleBadgePrefs.Module] stays the identity: it is already persisted per
 * module, so reusing it means the shortcut and the badge can never disagree
 * about what "the notes module" is.
 *
 * `id` is what travels in an Intent extra. It is deliberately the same opaque
 * key the badge prefs already use — these ids reach the launcher's own storage,
 * where they are readable by anyone inspecting the device, so they say no more
 * than the module label the user has already chosen to display.
 */
enum class AppModule(
    val badge: ModuleBadgePrefs.Module,
    @StringRes val labelRes: Int,
    @DrawableRes val iconRes: Int,
    /** Order the shortcuts appear in, most-used first. */
    val rank: Int,
) {
    Meds(ModuleBadgePrefs.Module.Meds, R.string.module_meds, R.drawable.ic_module_meds, 0),
    Journal(ModuleBadgePrefs.Module.Journal, R.string.module_journal, R.drawable.ic_module_journal, 1),
    Labs(ModuleBadgePrefs.Module.Labs, R.string.module_labs, R.drawable.ic_module_labs, 2),
    Appointments(
        ModuleBadgePrefs.Module.Appointments,
        R.string.module_appointments,
        R.drawable.ic_module_appointments,
        3,
    ),
    Notes(ModuleBadgePrefs.Module.Notes, R.string.module_notes, R.drawable.ic_module_notes, 4),
    Dreams(ModuleBadgePrefs.Module.Dreams, R.string.module_dreams, R.drawable.ic_module_dreams, 5),
    Weight(ModuleBadgePrefs.Module.Weight, R.string.module_weight, R.drawable.ic_module_weight, 6),
    Bleeding(ModuleBadgePrefs.Module.Bleeding, R.string.module_bleeding, R.drawable.ic_module_bleeding, 7),
    Photos(ModuleBadgePrefs.Module.Photos, R.string.module_photos, R.drawable.ic_module_photos, 8),
    Voice(ModuleBadgePrefs.Module.Voice, R.string.module_voice, R.drawable.ic_module_voice, 9),
    Sport(ModuleBadgePrefs.Module.Sport, R.string.module_sport, R.drawable.ic_module_sport, 10);

    val id: String get() = badge.key

    companion object {
        /** Reverse of [id]. Unknown ids resolve to null and are ignored. */
        fun fromId(id: String?): AppModule? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Which modules the user currently has switched on.
 *
 * Read straight from [FeaturesPrefs] rather than cached: a module turned off in
 * Réglages must stop advertising itself on the launcher, and a stale copy of
 * this answer is a module name still sitting on the home screen after the user
 * asked for it to be gone.
 */
fun FeaturesPrefs.enabledModules(): List<AppModule> = AppModule.entries.filter { module ->
    when (module) {
        AppModule.Meds -> medications.value
        AppModule.Journal -> journal.value
        AppModule.Labs -> hormones.value
        AppModule.Appointments -> appointments.value
        AppModule.Notes -> notes.value
        AppModule.Dreams -> dreams.value
        // Weight is a sub-feature of Courbes: its own toggle governs it, but it
        // cannot outlive the module that hosts its screen.
        AppModule.Weight -> hormones.value && weightTracking.value
        AppModule.Bleeding -> bleeding.value
        AppModule.Photos -> photoTab.value
        AppModule.Voice -> voiceTab.value
        AppModule.Sport -> sport.value
    }
}.sortedBy { it.rank }
