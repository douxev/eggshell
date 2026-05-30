package com.douxev.eggshell.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Voice clip metadata mirror.
 *
 * The audio bytes themselves are encrypted via the Vault (same as photos) and
 * written to the app sandbox as `voice/<uuid>.bin`. The metadata stored here
 * is only what we need to render the list without unlocking the vault for
 * each row: id, recorded-at timestamp, duration, on-disk file path.
 *
 * Privacy: this prefs file leaks the *existence* of voice clips and their
 * durations, nothing else. Same trade-off the photo metadata makes by
 * keeping rows in the encrypted DB and the binary in the sandbox.
 *
 * We don't run pitch detection in this MVP — the trend shown on the Voice
 * screen is computed from the number of clips per week as a stand-in. A
 * real F0 estimator (e.g. via TarsosDSP) can populate the `pitchHz` field
 * later without changing the storage shape.
 */
class VoiceClipPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class Entry(
        val id: String,
        val atMs: Long,
        val durationMs: Long,
        val filePath: String,
        val pitchHz: Int?,
    )

    fun all(): List<Entry> {
        val ids = prefs.getString(KEY_IDS, null).orEmpty().split(',')
            .map { it.trim() }.filter { it.isNotEmpty() }
        return ids.mapNotNull { id ->
            val path = prefs.getString(key("path", id), null) ?: return@mapNotNull null
            Entry(
                id = id,
                atMs = prefs.getLong(key("at", id), 0L),
                durationMs = prefs.getLong(key("dur", id), 0L),
                filePath = path,
                pitchHz = if (prefs.contains(key("pitch", id)))
                    prefs.getInt(key("pitch", id), 0) else null,
            )
        }.sortedByDescending { it.atMs }
    }

    fun put(entry: Entry) {
        val ids = (all().map { it.id } + entry.id).distinct()
        prefs.edit().apply {
            putString(KEY_IDS, ids.joinToString(","))
            putLong(key("at", entry.id), entry.atMs)
            putLong(key("dur", entry.id), entry.durationMs)
            putString(key("path", entry.id), entry.filePath)
            entry.pitchHz?.let { putInt(key("pitch", entry.id), it) }
                ?: run { remove(key("pitch", entry.id)) }
        }.apply()
    }

    fun remove(id: String) {
        val ids = all().map { it.id }.filter { it != id }
        prefs.edit().apply {
            putString(KEY_IDS, ids.joinToString(","))
            remove(key("at", id))
            remove(key("dur", id))
            remove(key("path", id))
            remove(key("pitch", id))
        }.apply()
    }

    /**
     * True if any legacy voice clip metadata is still in plain prefs and
     * needs to be migrated into the encrypted vault. Used at unlock time
     * by [VoiceRepository.migrateLegacyMetadataIfNeeded].
     */
    fun hasAny(): Boolean = prefs.contains(KEY_IDS) &&
        prefs.getString(KEY_IDS, null).orEmpty().any { it != ',' && it != ' ' }

    /** Erase the plain-prefs mirror — call after successful migration. */
    fun wipe() {
        prefs.edit().clear().apply()
    }

    private fun key(name: String, id: String) = "${name}_$id"

    companion object {
        private const val PREFS_NAME = "transition_voice_prefs"
        private const val KEY_IDS = "ids"
    }
}
