package com.douxev.eggshell.security

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-device throttle for PIN entry attempts at the lock screen.
 *
 * Failed attempts (plain PIN typos — not decoy hits, those are intentional)
 * accumulate; after [FREE_ATTEMPTS] failures we start backing off with an
 * exponential delay that the UI must observe before accepting another
 * input. After [WIPE_THRESHOLD] failures we wipe the vault entirely so an
 * attacker who got hold of the phone cannot continue brute-forcing the PIN
 * even if they reset the app's process state.
 *
 * Stored in plain SharedPreferences because:
 *  - the counter itself isn't sensitive,
 *  - the wipe trigger must work even when the vault is locked (so it can't
 *    live inside the encrypted DB),
 *  - SharedPreferences survives the process restart that an attacker would
 *    cause by force-stopping the app.
 *
 * The state is reset to zero on every successful PIN match.
 */
@Singleton
class PinRateLimiter @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences =
        com.douxev.eggshell.data.SecurePrefs.get(context, PREFS_NAME)

    /** True if the user must wait before the next attempt is accepted. */
    fun lockedOutMs(): Long {
        val until = prefs.getLong(KEY_LOCK_UNTIL, 0L)
        val now = System.currentTimeMillis()
        return (until - now).coerceAtLeast(0L)
    }

    /** Call after a failed PIN attempt. Returns the new failure count. */
    fun recordFailure(): Int {
        val count = prefs.getInt(KEY_FAILURES, 0) + 1
        val ed = prefs.edit().putInt(KEY_FAILURES, count)
        if (count > FREE_ATTEMPTS) {
            val backoff = computeBackoffMs(count - FREE_ATTEMPTS)
            ed.putLong(KEY_LOCK_UNTIL, System.currentTimeMillis() + backoff)
        }
        ed.apply()
        return count
    }

    /** True iff the failure count has reached the wipe threshold. */
    fun shouldWipe(): Boolean = prefs.getInt(KEY_FAILURES, 0) >= WIPE_THRESHOLD

    /** Reset counters — call on every successful PIN match. */
    fun reset() {
        prefs.edit().remove(KEY_FAILURES).remove(KEY_LOCK_UNTIL).apply()
    }

    private fun computeBackoffMs(extra: Int): Long = when (extra) {
        1 -> 5_000L         //  5 s after the 4th failure
        2 -> 30_000L        // 30 s after the 5th
        3 -> 2 * 60_000L    //  2 min after the 6th
        4 -> 10 * 60_000L   // 10 min after the 7th
        else -> 60 * 60_000L // 1 h cap
    }

    companion object {
        const val FREE_ATTEMPTS = 3
        const val WIPE_THRESHOLD = 12
        private const val PREFS_NAME = "eggshell_pin_throttle"
        private const val KEY_FAILURES = "failures"
        private const val KEY_LOCK_UNTIL = "lock_until_ms"
    }
}
