package com.douxev.eggshell.reminders

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.douxev.eggshell.data.SecurePrefs
import com.douxev.eggshell.security.MetadataObfuscator

/**
 * Off-vault mirror of the active medication schedules.
 *
 * Why mirror? The Vault DB is encrypted and requires an unlock. But alarms
 * fire while the user is away from the app, including after a reboot, and the
 * receiver must be able to:
 *   - identify which schedule fired
 *   - compute when to fire again
 *   - persist the new "next due" timestamp
 *   - know which medication to queue a locked "Pris" tap against
 * all without touching the encrypted DB.
 *
 * **At rest the whole mirror is a single sealed blob** ([MetadataObfuscator]):
 * AES-GCM in release (opaque to a forensic dump), readable passthrough in
 * debug. The file name itself is non-descriptive in release ([SecurePrefs]).
 * So even the scheduling cadence, the medication ids, and any opted-in name in
 * [Entry.displayLabel] stay unreadable on a seized device.
 *
 * Synced from the DB on vault unlock, on any schedule change, and on any
 * notification-content / alias change.
 */
class ReminderPrefs(context: Context) {

    private val prefs: SharedPreferences = SecurePrefs.get(context, PREFS_NAME)
    private val obfuscator = MetadataObfuscator()

    data class Entry(
        val scheduleId: Long,
        val medicationId: Long,
        val kind: String,
        val intervalMinutes: Int?,
        val dailyHour: Int?,
        val dailyMinute: Int?,
        val nextDueAtMs: Long,
        /** Opt-in label shown in the reminder (real name or alias). Null = the
         *  privacy-default generic copy, with nothing identifying in clear. */
        val displayLabel: String? = null,
        /** Set when kind == "days_interval": the N-day cadence. */
        val intervalDays: Int? = null,
    )

    fun all(): List<Entry> {
        val plaintext = obfuscator.open(prefs.getString(KEY_BLOB, null)) ?: return emptyList()
        return plaintext.split(ROW_SEP).mapNotNull { parseRow(it) }
    }

    fun get(scheduleId: Long): Entry? = all().firstOrNull { it.scheduleId == scheduleId }

    fun put(entry: Entry) {
        val next = all().filter { it.scheduleId != entry.scheduleId } + entry
        writeAll(next)
    }

    fun setNextDue(scheduleId: Long, nextDueAtMs: Long) {
        val updated = all().map {
            if (it.scheduleId == scheduleId) it.copy(nextDueAtMs = nextDueAtMs) else it
        }
        writeAll(updated)
    }

    fun remove(scheduleId: Long) {
        writeAll(all().filter { it.scheduleId != scheduleId })
    }

    fun wipe() {
        prefs.edit().remove(KEY_BLOB).apply()
    }

    private fun writeAll(entries: List<Entry>) {
        if (entries.isEmpty()) {
            prefs.edit().clear().apply()
            return
        }
        val serialized = entries.joinToString(ROW_SEP) { serializeRow(it) }
        // Fail soft: a (rare) seal failure leaves the previous blob in place;
        // the next schedule change retries.
        val sealed = obfuscator.seal(serialized) ?: return
        // clear() first so any legacy per-key entries (from the pre-blob
        // format, possibly carried over by the SecurePrefs file migration)
        // don't linger as orphan plaintext alongside the sealed blob.
        prefs.edit().clear().putString(KEY_BLOB, sealed).apply()
    }

    private fun serializeRow(e: Entry): String = listOf(
        e.scheduleId.toString(),
        e.medicationId.toString(),
        e.kind,
        e.intervalMinutes?.toString().orEmpty(),
        e.dailyHour?.toString().orEmpty(),
        e.dailyMinute?.toString().orEmpty(),
        e.nextDueAtMs.toString(),
        // Base64 so an arbitrary label can't break the comma/row delimiters.
        e.displayLabel?.let { Base64.encodeToString(it.toByteArray(Charsets.UTF_8), Base64.NO_WRAP) }.orEmpty(),
        // Appended last so older 8-field rows still parse (intervalDays = null).
        e.intervalDays?.toString().orEmpty(),
    ).joinToString(FIELD_SEP)

    private fun parseRow(row: String): Entry? {
        val f = row.split(FIELD_SEP)
        if (f.size < 8) return null
        val scheduleId = f[0].toLongOrNull() ?: return null
        val medicationId = f[1].toLongOrNull() ?: return null
        val kind = f[2].ifEmpty { return null }
        val nextDue = f[6].toLongOrNull() ?: return null
        val label = f[7].takeIf { it.isNotEmpty() }
            ?.let { runCatching { String(Base64.decode(it, Base64.NO_WRAP), Charsets.UTF_8) }.getOrNull() }
        return Entry(
            scheduleId = scheduleId,
            medicationId = medicationId,
            kind = kind,
            intervalMinutes = f[3].toIntOrNull(),
            dailyHour = f[4].toIntOrNull(),
            dailyMinute = f[5].toIntOrNull(),
            nextDueAtMs = nextDue,
            displayLabel = label,
            intervalDays = f.getOrNull(8)?.toIntOrNull(),
        )
    }

    companion object {
        private const val PREFS_NAME = "transition_reminder_prefs"
        private const val KEY_BLOB = "m"
        private const val ROW_SEP = "\n"
        private const val FIELD_SEP = ","
    }
}
