package com.douxev.eggshell.data

import java.time.LocalDate
import java.time.format.DateTimeParseException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The vault's own key/value store.
 *
 * Rows live in `app_settings` *inside* the SQLCipher database, which is the
 * whole reason this sits next to the `SharedPreferences` helpers rather than
 * among them: those keep display choices, this keeps what must never lie in
 * clear on the disk of a phone that also ships a decoy mode. A value written
 * here is encrypted with everything else and vanishes when the vault is wiped.
 *
 * Absence and emptiness are not the same thing. A key that was never written
 * reads `null`; the empty string is a *value*. A field is therefore cleared by
 * deleting its key — never by writing `""` — and every setter here does that on
 * the caller's behalf so no call site has to remember it.
 *
 * Keys are spelled out once, in this file, and are shared with the iOS app: one
 * vault opened by either side has to agree on what it is reading.
 */
@Singleton
class SettingsRepository @Inject constructor(
    private val vault: VaultRepository,
) {
    // -- the doctor report's identity block (§7.4.2) --------------------------

    /** The name printed as « PERSONNE SUIVIE ». Free text, never parsed. */
    suspend fun reportPersonName(): String? =
        get(KEY_REPORT_PERSON)?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * The date printed as « NÉE LE ». Stored ISO-8601, so what reaches the disk
     * never depends on the locale that wrote it — the display form is rebuilt
     * at every read instead.
     */
    suspend fun reportBirthDate(): LocalDate? {
        val raw = get(KEY_REPORT_BIRTH)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        // A row we cannot read is treated as absent rather than surfaced as an
        // error: the identity block is optional, and refusing to draw the whole
        // report over one unparseable date would be the wrong trade.
        return try {
            LocalDate.parse(raw)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    /** Blank erases the key: an empty name is not a name. */
    suspend fun setReportPersonName(name: String?) {
        val trimmed = name?.trim().orEmpty()
        if (trimmed.isEmpty()) delete(KEY_REPORT_PERSON) else put(KEY_REPORT_PERSON, trimmed)
    }

    /** `null` erases the key. */
    suspend fun setReportBirthDate(date: LocalDate?) {
        if (date == null) delete(KEY_REPORT_BIRTH) else put(KEY_REPORT_BIRTH, date.toString())
    }

    /** Both keys gone. The report then prints no identity box at all. */
    suspend fun clearReportIdentity() {
        delete(KEY_REPORT_PERSON)
        delete(KEY_REPORT_BIRTH)
    }

    // -- plumbing ------------------------------------------------------------

    private suspend fun get(key: String): String? =
        withContext(Dispatchers.IO) { vault.requireSession().getSetting(key) }

    private suspend fun put(key: String, value: String) =
        withContext(Dispatchers.IO) { vault.requireSession().setSetting(key, value) }

    private suspend fun delete(key: String) =
        withContext(Dispatchers.IO) { vault.requireSession().deleteSetting(key) }

    private companion object {
        const val KEY_REPORT_PERSON = "report.identity.person"
        const val KEY_REPORT_BIRTH = "report.identity.birth"
    }
}
