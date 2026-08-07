package com.douxev.eggshell.data

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * When each launcher module was last opened.
 *
 * The home launcher shows a plain dot on a module that holds content the user
 * has never seen (a freshly imported lab result, say). The rule is "a badge
 * clears as soon as the module is opened", so all we need to persist is the
 * timestamp of the last visit and compare it against the newest item.
 *
 * Timestamps only — no content, nothing that would say anything about the app
 * to a forensic reader of the prefs directory.
 */
@Singleton
class ModuleBadgePrefs @Inject constructor(
    @ApplicationContext context: Context,
) {
    enum class Module(val key: String) {
        Labs("labs"),
        Weight("weight"),
        Photos("photos"),
        Voice("voice"),
        Journal("journal"),
        Bleeding("bleeding"),
        Appointments("appointments"),
        Meds("meds"),
        Notes("notes"),
        Dreams("dreams"),
    }

    private val prefs: SharedPreferences = SecurePrefs.get(context, PREFS_NAME)

    private val _seen = MutableStateFlow(readAll())
    /** Last-opened epoch-ms per module; missing means "never opened". */
    val seen: StateFlow<Map<Module, Long>> = _seen.asStateFlow()

    fun markOpened(module: Module, atMs: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(module.key, atMs).apply()
        _seen.value = _seen.value + (module to atMs)
    }

    fun lastOpened(module: Module): Long = _seen.value[module] ?: 0L

    private fun readAll(): Map<Module, Long> =
        Module.entries.associateWith { prefs.getLong(it.key, 0L) }

    private companion object {
        const val PREFS_NAME = "eggshell_module_seen"
    }
}
