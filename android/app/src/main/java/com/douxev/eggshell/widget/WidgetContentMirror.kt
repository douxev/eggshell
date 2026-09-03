package com.douxev.eggshell.widget

import android.content.Context
import android.content.SharedPreferences
import com.douxev.eggshell.data.SecurePrefs
import com.douxev.eggshell.security.MetadataObfuscator

/**
 * The only place vault content is allowed to exist outside the vault.
 *
 * A widget is drawn by the launcher, in the launcher\'s process, on its own
 * schedule — long after the app has locked. So a widget that shows a note title
 * is a note title stored somewhere the passphrase does not gate. There is no
 * clever way around that, and pretending otherwise would be the dishonest kind
 * of feature: the widget bought at the price of the thing being widgeted.
 *
 * What this file does instead is make the trade explicit and bounded:
 *
 * - **Opt-in, per widget instance.** Nothing lands here unless the user turned
 *   it on for that specific widget ([WidgetConfigPrefs.Config.showsContent],
 *   false by default).
 * - **Never in paranoid mode.** [writable] refuses, and the config screen
 *   does not offer the switch. Paranoid\'s whole promise is that nothing usable
 *   survives without the passphrase; this store is openable with the Keystore
 *   key alone, so it cannot exist there.
 * - **Sealed at rest** ([MetadataObfuscator]), like the reminder mirror and the
 *   dose queue: AES-GCM under a Keystore key in release, readable passthrough
 *   in debug.
 * - **Truncated.** Only what a widget row can physically show
 *   ([TITLE_LIMIT] / [SUBTITLE_LIMIT] characters). A mirror is not a copy, and
 *   the body of a note never comes here.
 * - **Dropped on lock.** [clear] runs when the session closes, so the window in
 *   which this file has anything in it is the window in which the app was open.
 *
 * Honest limit, stated rather than buried: between an opt-in and a lock, an
 * attacker with the unlocked device and the Keystore can read these rows
 * without ever meeting the passphrase. That is the disclosure the user agreed
 * to, and it is why the default is off.
 */
class WidgetContentMirror(context: Context) {

    private val prefs: SharedPreferences = SecurePrefs.get(context, PREFS_NAME)
    private val obfuscator = MetadataObfuscator()

    /** One line of a widget. [targetId] is what a tap on it opens. */
    data class Row(
        val title: String,
        val subtitle: String,
        val targetId: Long,
    )

    fun rows(widgetId: Int): List<Row> = all()[widgetId].orEmpty()

    fun put(widgetId: Int, rows: List<Row>) {
        writeAll(all() + (widgetId to rows.map(::truncate)))
    }

    fun remove(widgetIds: IntArray) {
        val gone = widgetIds.toSet()
        writeAll(all().filterKeys { it !in gone })
    }

    /**
     * Forget everything. Called when the vault locks, when the user revokes an
     * opt-in, and when the mode moves to paranoid.
     *
     * `commit`, not `apply`: the lock path is followed by the process being
     * backgrounded and possibly killed, and an async erase the kill outran
     * would leave exactly the content the lock was meant to withdraw.
     */
    fun clear() {
        prefs.edit().remove(KEY_BLOB).commit()
    }

    fun all(): Map<Int, List<Row>> {
        val plaintext = obfuscator.open(prefs.getString(KEY_BLOB, null)) ?: return emptyMap()
        return WidgetMirrorCodec.decode(plaintext)
    }

    private fun writeAll(mirror: Map<Int, List<Row>>) {
        val pruned = mirror.filterValues { it.isNotEmpty() }
        if (pruned.isEmpty()) {
            clear()
            return
        }
        // Fail soft, like the reminder mirror: a seal failure leaves the
        // previous blob rather than blanking every widget.
        val sealed = obfuscator.seal(WidgetMirrorCodec.encode(pruned)) ?: return
        prefs.edit().putString(KEY_BLOB, sealed).apply()
    }

    private fun truncate(row: Row) = row.copy(
        title = row.title.take(TITLE_LIMIT),
        subtitle = row.subtitle.take(SUBTITLE_LIMIT),
    )

    companion object {
        const val TITLE_LIMIT = 60
        const val SUBTITLE_LIMIT = 90
        private const val PREFS_NAME = "transition_widget_mirror"
        private const val KEY_BLOB = "m"

        /**
         * Whether content may be mirrored at all, given the vault\'s mode.
         *
         * Checked at write time as well as in the configuration screen. The
         * screen is where the user is told; this is what makes it true even for
         * an instance configured before a switch to paranoid, or by a build
         * that did not yet know to ask.
         */
        fun writable(mode: com.douxev.eggshell.security.VaultPrefs.Mode?): Boolean =
            mode != null && mode != com.douxev.eggshell.security.VaultPrefs.Mode.PARANOID
    }
}

/**
 * The on-disk shape of [WidgetContentMirror], as pure functions so the format
 * can be tested without a Keystore.
 *
 * Values are Base64\'d before being joined. Note titles are arbitrary user text
 * and will contain commas and newlines — the separators — and a title
 * containing one would otherwise be read back as two rows, or shift every field
 * after it by one. Encoding is not a nicety here: it is what stops someone\'s
 * note from silently becoming a different note.
 */
internal object WidgetMirrorCodec {

    fun encode(mirror: Map<Int, List<WidgetContentMirror.Row>>): String =
        mirror.entries.joinToString(ENTRY_SEP) { (id, rows) ->
            (listOf(id.toString()) + rows.map { row ->
                listOf(b64(row.title), b64(row.subtitle), row.targetId.toString())
                    .joinToString(FIELD_SEP)
            }).joinToString(ROW_SEP)
        }

    fun decode(plaintext: String): Map<Int, List<WidgetContentMirror.Row>> =
        plaintext.split(ENTRY_SEP).mapNotNull(::decodeEntry).toMap()

    private fun decodeEntry(entry: String): Pair<Int, List<WidgetContentMirror.Row>>? {
        val parts = entry.split(ROW_SEP)
        val id = parts.firstOrNull()?.toIntOrNull() ?: return null
        return id to parts.drop(1).mapNotNull(::decodeRow)
    }

    private fun decodeRow(row: String): WidgetContentMirror.Row? {
        val f = row.split(FIELD_SEP)
        if (f.size < 3) return null
        return WidgetContentMirror.Row(
            title = unb64(f[0]) ?: return null,
            subtitle = unb64(f[1]) ?: return null,
            targetId = f[2].toLongOrNull() ?: return null,
        )
    }

    // java.util.Base64, not android.util.Base64. minSdk is 26, so both are
    // available — but only this one exists on a plain JVM, which is what lets
    // the format be tested without a device. The format itself is identical.
    private fun b64(s: String): String =
        java.util.Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))

    private fun unb64(s: String): String? = runCatching {
        String(java.util.Base64.getDecoder().decode(s), Charsets.UTF_8)
    }.getOrNull()

    private const val ENTRY_SEP = "\u0000"
    private const val ROW_SEP = "\n"
    private const val FIELD_SEP = ","
}
