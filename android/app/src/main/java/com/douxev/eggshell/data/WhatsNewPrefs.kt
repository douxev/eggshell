package com.douxev.eggshell.data

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks the highest version code the user has "seen" via the what's-new
 * sheet. Used to decide whether to surface the highlights overlay on the
 * next cold start.
 *
 * First-install behaviour: the lastSeen field is 0 by default. To avoid
 * popping the sheet on a brand-new install (where the onboarding flow has
 * already explained the basics), [shouldShow] silently marks the current
 * version as seen the very first time it's queried.
 */
@Singleton
class WhatsNewPrefs @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun lastSeen(): Int = prefs.getInt(KEY_LAST_SEEN, 0)

    fun shouldShow(currentVersion: Int): Boolean {
        val seen = lastSeen()
        if (seen == 0) {
            // First-ever launch — onboarding does the welcoming.
            markSeen(currentVersion)
            return false
        }
        return seen < currentVersion
    }

    fun markSeen(version: Int) {
        prefs.edit().putInt(KEY_LAST_SEEN, version).apply()
    }

    companion object {
        private const val PREFS_NAME = "transition_whats_new"
        private const val KEY_LAST_SEEN = "last_seen_version"
    }
}
