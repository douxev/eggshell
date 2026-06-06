package com.douxev.eggshell.reminders

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import com.douxev.eggshell.security.MetadataObfuscator

/**
 * A queue of "I took this dose" taps that landed while the vault was locked.
 *
 * The dose log lives in the encrypted DB, whose key is only available after
 * unlock — so a "Pris" tap from the notification shade (or a paired watch)
 * can't write it directly, regardless of the security mode in use. Instead we
 * append the bare facts — which schedule, which medication, and when — and
 * [com.douxev.eggshell.data.ScheduleRepository.flushPendingDoses] drains the
 * queue into the vault on the next real unlock.
 *
 * At rest the whole queue is a single AES-GCM blob sealed by
 * [MetadataObfuscator] under a deliberately nondescript file/key name. A
 * forensic dump of the device sees an opaque base64 string under a generic
 * key, not a list of "took medication at 8am" rows — and it stays opaque even
 * in Paranoid / passphrase modes where the device may be powered off when
 * seized. Decoy unlocks never open the real session, so the flush never runs
 * under a decoy PIN.
 */
@Singleton
class PendingDosePrefs @Inject constructor(
    @ApplicationContext context: Context,
    private val obfuscator: MetadataObfuscator,
) {
    data class Pending(
        val scheduleId: Long,
        val medicationId: Long,
        val takenAtMs: Long,
        /** "taken" or "skipped" — what the user tapped while the vault was locked. */
        val status: String = "taken",
    )

    private val prefs: SharedPreferences =
        com.douxev.eggshell.data.SecurePrefs.get(context, PREFS_NAME)

    /**
     * @return true if the dose was persisted; false if sealing failed (rare —
     * a Keystore hiccup) so the caller can fall back to asking the user to
     * record it after unlock instead of silently dropping it.
     */
    @Synchronized
    fun add(pending: Pending): Boolean {
        val rows = all() + pending
        val sealed = obfuscator.seal(serialize(rows)) ?: return false
        prefs.edit().putString(KEY_BLOB, sealed).apply()
        return true
    }

    fun all(): List<Pending> {
        val plaintext = obfuscator.open(prefs.getString(KEY_BLOB, null)) ?: return emptyList()
        return plaintext.split(SEP).mapNotNull { row ->
            val parts = row.split(",")
            // 3 fields = legacy "taken" row; 4 fields adds the status tag.
            if (parts.size < 3) return@mapNotNull null
            val scheduleId = parts[0].toLongOrNull() ?: return@mapNotNull null
            val medicationId = parts[1].toLongOrNull() ?: return@mapNotNull null
            val takenAtMs = parts[2].toLongOrNull() ?: return@mapNotNull null
            val status = parts.getOrNull(3)?.takeIf { it.isNotBlank() } ?: "taken"
            Pending(scheduleId, medicationId, takenAtMs, status)
        }
    }

    private fun serialize(rows: List<Pending>): String = rows.joinToString(SEP) {
        "${it.scheduleId},${it.medicationId},${it.takenAtMs},${it.status}"
    }

    @Synchronized
    fun clear() {
        prefs.edit().remove(KEY_BLOB).apply()
    }

    /**
     * Drop any queued taps for a schedule that's being deleted, so a stale
     * locked "Pris" doesn't get logged on the next unlock for a reminder that
     * no longer exists. Re-seals the remaining rows (fail-soft: a sealing
     * hiccup leaves the prior blob untouched).
     */
    @Synchronized
    fun removeForSchedule(scheduleId: Long) {
        val remaining = all().filter { it.scheduleId != scheduleId }
        if (remaining.isEmpty()) {
            prefs.edit().remove(KEY_BLOB).apply()
            return
        }
        val sealed = obfuscator.seal(serialize(remaining)) ?: return
        prefs.edit().putString(KEY_BLOB, sealed).apply()
    }

    companion object {
        // Nondescript on purpose: nothing here names doses, reminders or the app.
        private const val PREFS_NAME = "androidx_cache_index"
        private const val KEY_BLOB = "seq"
        private const val SEP = ";"
    }
}
