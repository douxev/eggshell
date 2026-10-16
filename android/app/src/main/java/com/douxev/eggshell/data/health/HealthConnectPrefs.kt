package com.douxev.eggshell.data.health

import android.content.Context
import android.content.SharedPreferences
import com.douxev.eggshell.data.SecurePrefs
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the Health Connect import is on, and when it last succeeded.
 *
 * Off-vault, like [com.douxev.eggshell.data.SportPrefs], because both are read
 * before the vault is needed — the card draws its switch, the sync decides its
 * window — and neither says anything on its own: a boolean and a timestamp.
 * What the import brings in lives in the vault.
 *
 * Not in the settings backup on purpose: the switch stands for a Health
 * Connect permission granted on this phone, and the timestamp for what this
 * phone already imported. Restored elsewhere, the first would be a switch that
 * is on with nothing allowed, the second a window that skips a month.
 */
@Singleton
class HealthConnectPrefs @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences = SecurePrefs.get(context, PREFS_NAME)

    /**
     * Off by default. Turning it on is what asks Health Connect for access,
     * and that should follow an explicit « import my workouts », never the
     * mere fact of having the Sport module.
     */
    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        _enabled.value = enabled
    }

    private val _lastSyncMs = MutableStateFlow(
        prefs.getLong(KEY_LAST_SYNC, 0L).takeIf { it > 0L }
    )

    /** The end of the last window that was read completely, or null if never. */
    val lastSyncMs: StateFlow<Long?> = _lastSyncMs.asStateFlow()

    /**
     * `commit`, not `apply`: this is the floor of the next window. Lost to a
     * kill, the next sync would only re-read what it already has — harmless,
     * the dedupe takes care of it — but there is no reason to pay that.
     */
    fun setLastSyncMs(atMs: Long) {
        prefs.edit().putLong(KEY_LAST_SYNC, atMs).commit()
        _lastSyncMs.value = atMs
    }

    /**
     * Health Connect record ids the last sync already handled, among those
     * the next sync's overlap will read again.
     *
     * Without this, a workout imported and then deleted by its owner would
     * come straight back at the next sync, for as long as it sits inside the
     * two-day overlap — the dedupe only sees what is still in the vault. Ids
     * only, which are opaque to anyone without Health Connect itself: no time,
     * no type, nothing about the workout.
     */
    fun seenRecordIds(): Set<String> = prefs.getStringSet(KEY_SEEN, null)?.toSet().orEmpty()

    fun setSeenRecordIds(ids: Set<String>) {
        prefs.edit().putStringSet(KEY_SEEN, ids).commit()
    }

    private companion object {
        const val PREFS_NAME = "transition_health_prefs"
        const val KEY_ENABLED = "import_enabled"
        const val KEY_LAST_SYNC = "last_sync_ms"
        const val KEY_SEEN = "seen_record_ids"
    }
}
