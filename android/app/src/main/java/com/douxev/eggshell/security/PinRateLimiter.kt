package com.douxev.eggshell.security

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-device throttle for credential entry at the lock screen.
 *
 * Two independent ladders share this class:
 *
 *  - **PIN** ([recordFailure]): failed attempts accumulate; after
 *    [FREE_ATTEMPTS] we back off exponentially, and after [WIPE_THRESHOLD]
 *    the vault is wiped so an attacker who got hold of the phone cannot keep
 *    brute-forcing even across a force-stop.
 *  - **Recovery key** ([recordRecoveryFailure]): the same backoff, and
 *    deliberately **no wipe**. That surface is what a locked-out owner reaches
 *    for; destroying their vault because they mistyped the thing they only
 *    ever use in an emergency would invert its entire purpose. Slowing an
 *    attacker down is the goal, and the ladder alone does that.
 *
 * The counters are kept apart so a few fumbled recovery attempts can never
 * push the PIN counter towards the wipe threshold.
 *
 * Stored in plain SharedPreferences because:
 *  - the counters themselves aren't sensitive,
 *  - the wipe trigger must work even when the vault is locked (so it can't
 *    live inside the encrypted DB),
 *  - SharedPreferences survives the process restart that an attacker would
 *    cause by force-stopping the app.
 */
@Singleton
class PinRateLimiter @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences =
        com.douxev.eggshell.data.SecurePrefs.get(context, PREFS_NAME)

    /** True if the user must wait before the next PIN attempt is accepted. */
    fun lockedOutMs(): Long = remainingFor(KEY_LOCK_UNTIL, KEY_LOCK_UNTIL_ELAPSED, KEY_WRITTEN_AT_ELAPSED)

    /** Call after a failed PIN attempt. Returns the new failure count. */
    fun recordFailure(): Int =
        record(KEY_FAILURES, KEY_LOCK_UNTIL, KEY_LOCK_UNTIL_ELAPSED, KEY_WRITTEN_AT_ELAPSED)

    /** True iff the PIN failure count has reached the wipe threshold. */
    fun shouldWipe(): Boolean = prefs.getInt(KEY_FAILURES, 0) >= WIPE_THRESHOLD

    /** Reset the PIN counters — call on every successful PIN match. */
    fun reset() {
        prefs.edit()
            .remove(KEY_FAILURES).remove(KEY_LOCK_UNTIL)
            .remove(KEY_LOCK_UNTIL_ELAPSED).remove(KEY_WRITTEN_AT_ELAPSED)
            .commit()
    }

    // -- recovery-key ladder (same backoff, never wipes) ----------------------

    fun recoveryLockedOutMs(): Long =
        remainingFor(KEY_REC_LOCK_UNTIL, KEY_REC_LOCK_UNTIL_ELAPSED, KEY_REC_WRITTEN_AT_ELAPSED)

    fun recordRecoveryFailure(): Int =
        record(KEY_REC_FAILURES, KEY_REC_LOCK_UNTIL, KEY_REC_LOCK_UNTIL_ELAPSED, KEY_REC_WRITTEN_AT_ELAPSED)

    fun resetRecovery() {
        prefs.edit()
            .remove(KEY_REC_FAILURES).remove(KEY_REC_LOCK_UNTIL)
            .remove(KEY_REC_LOCK_UNTIL_ELAPSED).remove(KEY_REC_WRITTEN_AT_ELAPSED)
            .commit()
    }

    // -- internals ------------------------------------------------------------

    /**
     * Remaining lockout, resistant to the phone's clock being moved.
     *
     * A wall-clock deadline alone is defeated by Settings → Date & time, which
     * anyone holding the phone can reach — and holding the phone is the whole
     * premise of the threat model. A monotonic deadline alone is defeated by a
     * reboot, which resets [SystemClock.elapsedRealtime] to zero.
     *
     * So both are stored and the longer wait wins. Moving the clock forward
     * leaves the monotonic deadline standing; rebooting leaves the wall-clock
     * one standing; moving the clock backwards only ever over-punishes, which
     * is the safe direction to be wrong in. The write-time elapsed stamp is
     * what lets us notice a reboot and stop trusting the monotonic value.
     */
    private fun remainingFor(wallKey: String, elapsedKey: String, writtenAtKey: String): Long {
        val wallRemaining =
            (prefs.getLong(wallKey, 0L) - System.currentTimeMillis()).coerceAtLeast(0L)
        val nowElapsed = SystemClock.elapsedRealtime()
        val writtenAtElapsed = prefs.getLong(writtenAtKey, 0L)
        val elapsedRemaining = if (nowElapsed >= writtenAtElapsed) {
            (prefs.getLong(elapsedKey, 0L) - nowElapsed).coerceAtLeast(0L)
        } else {
            0L // the monotonic clock restarted: the device rebooted, distrust it
        }
        return maxOf(wallRemaining, elapsedRemaining)
    }

    /**
     * `commit`, not `apply`. The class documents that its state "survives the
     * process restart that an attacker would cause by force-stopping the app"
     * — an async write is exactly what a force-stop drops, which would hand
     * back the attempt it was meant to have cost.
     */
    private fun record(
        countKey: String,
        wallKey: String,
        elapsedKey: String,
        writtenAtKey: String,
    ): Int {
        val count = prefs.getInt(countKey, 0) + 1
        val ed = prefs.edit().putInt(countKey, count)
        if (count > FREE_ATTEMPTS) {
            val backoff = computeBackoffMs(count - FREE_ATTEMPTS)
            val nowElapsed = SystemClock.elapsedRealtime()
            ed.putLong(wallKey, System.currentTimeMillis() + backoff)
                .putLong(elapsedKey, nowElapsed + backoff)
                .putLong(writtenAtKey, nowElapsed)
        }
        ed.commit()
        return count
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
        private const val KEY_LOCK_UNTIL_ELAPSED = "lock_until_elapsed"
        private const val KEY_WRITTEN_AT_ELAPSED = "written_at_elapsed"

        private const val KEY_REC_FAILURES = "recovery_failures"
        private const val KEY_REC_LOCK_UNTIL = "recovery_lock_until_ms"
        private const val KEY_REC_LOCK_UNTIL_ELAPSED = "recovery_lock_until_elapsed"
        private const val KEY_REC_WRITTEN_AT_ELAPSED = "recovery_written_at_elapsed"
    }
}
