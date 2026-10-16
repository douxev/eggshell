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
 * - **Truncated.** Only what a widget can physically show ([TITLE_LIMIT] /
 *   [SUBTITLE_LIMIT] characters). A mirror is not a copy. The one row that
 *   carries more than a line is a Notes widget pointed at a single note,
 *   whose owner asked for exactly that; it still stops at [SUBTITLE_LIMIT],
 *   which is about what a 4x3 card can display.
 * - **Kept across a lock, erased on withdrawal.** A widget is looked at from
 *   the home screen, which is to say while the app is locked — a mirror that
 *   emptied itself at every lock would show « open the app » every single time
 *   anyone looked, and the opt-in would buy nothing. So a lock only repaints.
 *   What erases rows is the user taking the consent back or the promise
 *   changing under it: the opt-out of one widget, the widget being removed,
 *   the move to paranoid mode, a decoy PIN, a vault wipe.
 *
 * Honest limit, stated rather than buried: for as long as a widget is opted in,
 * someone holding the device and its Keystore can read these rows without ever
 * meeting the passphrase — exactly what the home screen shows anyway. That is
 * the disclosure the user agreed to, and it is why the default is off.
 *
 * **Concurrency.** Every write is a read-modify-write of one sealed blob, and
 * the writers run on different threads (a background rebuild, a launcher
 * deleting a widget, a decoy being set). All of them go through [LOCK]. Every
 * erase also bumps [epoch], which is how a rebuild that read the vault before
 * the erase knows its rows are no longer wanted: it commits through
 * [commitIf], which refuses once the epoch has moved.
 */
class WidgetContentMirror(context: Context) {

    private val prefs: SharedPreferences = SecurePrefs.get(context, PREFS_NAME)
    private val obfuscator = MetadataObfuscator()

    /**
     * One line of a widget. [targetId] is what a tap on it opens.
     *
     * The fields after it are how a row gets drawn rather than what it says,
     * and each provider reads the ones its layout has a place for:
     * - [badge] is the leading tile of a content row — a date as "12\nSEPT.",
     *   a cycle day as "J4", a count. Empty means no tile.
     * - [progress] / [max] fill a gauge; -1 means the row has none.
     * - [accent] is the colour role the gauge is drawn in, as a
     *   [WidgetPalette.Role] ordinal.
     *
     * All of them default, and the codec reads a row without them, so a
     * mirror written by an earlier build still decodes.
     */
    data class Row(
        val title: String,
        val subtitle: String,
        val targetId: Long,
        val badge: String = "",
        val progress: Int = -1,
        val max: Int = 0,
        val accent: Int = 0,
    )

    fun rows(widgetId: Int): List<Row> = all()[widgetId].orEmpty()

    /**
     * Forget these widgets\' rows. An erase: bumps [epoch] when anything goes.
     *
     * Nothing to remove is not an erase — a widget that never had rows, or a
     * Traitements widget, which has none — and must not throw away a rebuild
     * in flight for every other widget. That rebuild cannot resurrect these
     * ids anyway: it re-reads each widget\'s consent under the same lock.
     */
    fun remove(widgetIds: IntArray) {
        if (widgetIds.isEmpty()) return
        val gone = widgetIds.toSet()
        synchronized(LOCK) {
            val current = all()
            if (current.keys.none { it in gone }) return
            EPOCH.incrementAndGet()
            writeAll(current.filterKeys { it !in gone })
        }
    }

    /**
     * Keep only the rows of [widgetIds]; true when anything was dropped.
     *
     * How a rebuild that cannot read the vault (it is locked) still honours an
     * opt-out or a removed widget: nothing is added, but nothing that lost its
     * consent stays either.
     */
    fun retainOnly(widgetIds: Set<Int>): Boolean = synchronized(LOCK) {
        val current = all()
        val kept = current.filterKeys { it in widgetIds }
        if (kept.size == current.size) return false
        EPOCH.incrementAndGet()
        writeAll(kept)
        true
    }

    /**
     * Forget everything. Called on the move to paranoid mode, when a decoy
     * PIN is set, when the vault is wiped or replaced by a restore, and when
     * the last opt-in goes. Not on lock — see the class doc.
     *
     * `commit`, not `apply`: these are withdrawals, and an async erase that a
     * process kill outran would leave exactly the content being withdrawn.
     */
    fun clear() {
        synchronized(LOCK) {
            EPOCH.incrementAndGet()
            prefs.edit().remove(KEY_BLOB).commit()
        }
    }

    /**
     * Replace the whole mirror with what [build] returns, unless an erase or a
     * session change happened since [expectedEpoch] — or [build] itself
     * declines by returning null.
     *
     * [build] runs under the store\'s lock, so the checks it makes (is the vault
     * still open, is this widget still opted in) cannot be overtaken by an erase
     * between the check and the write. Returns true when it wrote.
     */
    fun commitIf(
        expectedEpoch: Long,
        build: (current: Map<Int, List<Row>>) -> Map<Int, List<Row>>?,
    ): Boolean = synchronized(LOCK) {
        if (EPOCH.get() != expectedEpoch) return false
        val current = all()
        val next = build(current)
            ?.mapValues { (_, rows) -> rows.map(::truncate) }
            ?.filterValues { it.isNotEmpty() }
            ?: return false
        // Unchanged is not a write, and the caller skips the repaint.
        if (next == current) return false
        writeAll(next)
        true
    }

    fun all(): Map<Int, List<Row>> {
        val plaintext = obfuscator.open(prefs.getString(KEY_BLOB, null)) ?: return emptyMap()
        return WidgetMirrorCodec.decode(plaintext)
    }

    private fun writeAll(mirror: Map<Int, List<Row>>) {
        val pruned = mirror.filterValues { it.isNotEmpty() }
        if (pruned.isEmpty()) {
            prefs.edit().remove(KEY_BLOB).commit()
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
        badge = row.badge.take(BADGE_LIMIT),
    )

    companion object {
        const val TITLE_LIMIT = 60
        /**
         * Long enough for a single note\'s opening, which is the only row that
         * ever needs more than a line. List previews are cut to one line at
         * the source ([WidgetMirrorUpdater.PREVIEW_LIMIT]) before they get here.
         */
        const val SUBTITLE_LIMIT = 600
        const val BADGE_LIMIT = 12
        private const val PREFS_NAME = "transition_widget_mirror"
        private const val KEY_BLOB = "m"

        /** Every read-modify-write of the blob, in every instance, holds this. */
        private val LOCK = Any()

        /**
         * Bumped by every erase and every vault session change. A rebuild
         * captures it before reading the vault and commits only if it has not
         * moved — see [commitIf].
         */
        private val EPOCH = java.util.concurrent.atomic.AtomicLong(0L)

        fun epoch(): Long = EPOCH.get()

        /**
         * The vault opened or closed. Nothing is erased — the mirror outlives a
         * lock on purpose — but a rebuild still reading the session that just
         * ended must not commit what it read: a wipe or a restore that follows
         * would otherwise have its predecessor\'s rows written back over it.
         */
        fun sessionChanged() {
            EPOCH.incrementAndGet()
        }

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
                listOf(
                    b64(row.title),
                    b64(row.subtitle),
                    row.targetId.toString(),
                    b64(row.badge),
                    row.progress.toString(),
                    row.max.toString(),
                    row.accent.toString(),
                ).joinToString(FIELD_SEP)
            }).joinToString(ROW_SEP)
        }

    fun decode(plaintext: String): Map<Int, List<WidgetContentMirror.Row>> =
        plaintext.split(ENTRY_SEP).mapNotNull(::decodeEntry).toMap()

    private fun decodeEntry(entry: String): Pair<Int, List<WidgetContentMirror.Row>>? {
        val parts = entry.split(ROW_SEP)
        val id = parts.firstOrNull()?.toIntOrNull() ?: return null
        return id to parts.drop(1).mapNotNull(::decodeRow)
    }

    /**
     * The first three fields are the original format and are required; the
     * rest were appended later and default when absent, so a row written by
     * an older build reads as a plain line rather than being dropped.
     */
    private fun decodeRow(row: String): WidgetContentMirror.Row? {
        val f = row.split(FIELD_SEP)
        if (f.size < 3) return null
        return WidgetContentMirror.Row(
            title = unb64(f[0]) ?: return null,
            subtitle = unb64(f[1]) ?: return null,
            targetId = f[2].toLongOrNull() ?: return null,
            badge = f.getOrNull(3)?.let(::unb64).orEmpty(),
            progress = f.getOrNull(4)?.toIntOrNull() ?: -1,
            max = f.getOrNull(5)?.toIntOrNull() ?: 0,
            accent = f.getOrNull(6)?.toIntOrNull() ?: 0,
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
