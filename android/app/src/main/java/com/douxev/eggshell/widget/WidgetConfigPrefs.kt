package com.douxev.eggshell.widget

import android.content.Context
import android.content.SharedPreferences
import com.douxev.eggshell.data.SecurePrefs
import com.douxev.eggshell.security.MetadataObfuscator

/**
 * What each placed widget instance was configured to show, keyed by
 * `appWidgetId`.
 *
 * Per instance, not per module: two widgets of the same module have to be able
 * to point at different things — one Notes widget on a folder, another on a
 * single note — which is the whole reason the launcher hands out a distinct id
 * for each placement.
 *
 * **Sealed at rest** ([MetadataObfuscator]), like the reminder mirror and the
 * dose queue, because the contents are identifying even though they are only
 * ids: "this widget watches medication 3" and "this widget watches note folder
 * 7" are statements about someone\'s treatment and their private notes. Debug
 * builds pass through readably; release builds are AES-GCM under a Keystore key.
 *
 * Rows are dropped in [android.appwidget.AppWidgetProvider.onDeleted]. A
 * launcher reuses widget ids after a while, so a stale row is not merely litter
 * — it is one widget silently inheriting another\'s target.
 */
class WidgetConfigPrefs(context: Context) {

    private val prefs: SharedPreferences = SecurePrefs.get(context, PREFS_NAME)
    private val obfuscator = MetadataObfuscator()

    /**
     * One widget\'s settings.
     *
     * [showsContent] is the opt-in gate from the module-widget contract: false
     * means the widget stays a door and displays nothing from the vault. It
     * defaults to false, and [revokeAllContent] forces it back to false for
     * every instance if the vault ever moves to paranoid mode.
     *
     * The other fields are interpreted per module; a module ignores what it has
     * no use for. That is deliberately looser than one shape per module: this
     * blob is parsed by every widget provider on every render, and a format
     * that grows a new case per module is one that eventually fails to read a
     * row an older build wrote.
     */
    data class Config(
        val showsContent: Boolean = false,
        /** How many rows of content to draw. Each layout clamps to what it has. */
        val rows: Int = DEFAULT_ROWS,
        /** Module-specific target selector, e.g. "folder" or "note". */
        val targetKind: String? = null,
        /** The id [targetKind] refers to. Null means "everything". */
        val targetId: Long? = null,
    )

    fun get(widgetId: Int): Config = all()[widgetId] ?: Config()

    fun put(widgetId: Int, config: Config) {
        synchronized(LOCK) { writeAll(all() + (widgetId to config)) }
    }

    fun remove(widgetIds: IntArray) {
        if (widgetIds.isEmpty()) return
        val gone = widgetIds.toSet()
        synchronized(LOCK) {
            val current = all()
            if (current.keys.none { it in gone }) return
            writeAll(current.filterKeys { it !in gone })
        }
    }

    /**
     * Drop every row whose widget is no longer placed. Returns how many went.
     *
     * [onDeleted] is the normal way a row goes, but it is not the only way a
     * widget disappears: a provider disabled by a module switch or a decoy, or
     * removed by an update, takes its widgets with it and is never told. A
     * launcher can then hand the same id to a new widget, which would inherit a
     * consent it was never given. The caller passes the ids the system still
     * knows about, and only when it could read all of them.
     */
    fun retainOnly(liveIds: Set<Int>): Int = synchronized(LOCK) {
        val current = all()
        val kept = current.filterKeys { it in liveIds }
        if (kept.size == current.size) return 0
        writeAll(kept)
        current.size - kept.size
    }

    /**
     * Turn every instance back into a door.
     *
     * Called when the vault moves to paranoid mode and when a decoy PIN is
     * set: the opt-in was given under a different promise, and silently
     * honouring it afterwards would leave content on the home screen that the
     * mode the user just chose says cannot be there.
     */
    fun revokeAllContent() {
        synchronized(LOCK) { writeAll(all().mapValues { (_, c) -> c.copy(showsContent = false) }) }
    }

    /** Forget every row. For a vault wipe: the widgets\' consents go with it. */
    fun clearAll() {
        synchronized(LOCK) { prefs.edit().remove(KEY_BLOB).commit() }
    }

    fun all(): Map<Int, Config> {
        val plaintext = obfuscator.open(prefs.getString(KEY_BLOB, null)) ?: return emptyMap()
        return WidgetConfigCodec.decode(plaintext)
    }

    private fun writeAll(configs: Map<Int, Config>) {
        if (configs.isEmpty()) {
            prefs.edit().remove(KEY_BLOB).apply()
            return
        }
        // Fail soft, as the reminder mirror does: a seal failure leaves the
        // previous blob in place rather than dropping every widget's target.
        val sealed = obfuscator.seal(WidgetConfigCodec.encode(configs)) ?: return
        prefs.edit().putString(KEY_BLOB, sealed).apply()
    }

    companion object {
        const val DEFAULT_ROWS = 3
        private const val PREFS_NAME = "transition_widget_config"
        private const val KEY_BLOB = "cfg"

        /** Every read-modify-write of the blob, in every instance, holds this. */
        private val LOCK = Any()
    }
}

/**
 * The on-disk shape of [WidgetConfigPrefs], as pure functions.
 *
 * Separated from the store so the one property that matters can be tested
 * without a Keystore or a Context: **a build must read what every previous
 * build wrote.** These rows survive app updates, and a parser that quietly got
 * stricter would not throw — it would return an empty map, and every configured
 * widget on the home screen would revert to its defaults with nothing said.
 *
 * The rules that keeps true: fields are only ever *appended*, a row shorter
 * than the current format is read with defaults for what is missing, and an
 * unreadable row is dropped on its own rather than taking the file with it.
 */
internal object WidgetConfigCodec {

    fun encode(configs: Map<Int, WidgetConfigPrefs.Config>): String =
        configs.entries.joinToString(ROW_SEP) { (id, c) ->
            listOf(
                id.toString(),
                if (c.showsContent) "1" else "0",
                c.rows.toString(),
                c.targetKind.orEmpty(),
                c.targetId?.toString().orEmpty(),
            ).joinToString(FIELD_SEP)
        }

    fun decode(plaintext: String): Map<Int, WidgetConfigPrefs.Config> =
        plaintext.split(ROW_SEP).mapNotNull(::decodeRow).toMap()

    private fun decodeRow(row: String): Pair<Int, WidgetConfigPrefs.Config>? {
        val parts = row.split(FIELD_SEP)
        // Three fields is the original format. Shorter is corruption, not an
        // old row, and is dropped.
        if (parts.size < 3) return null
        val id = parts[0].toIntOrNull() ?: return null
        return id to WidgetConfigPrefs.Config(
            showsContent = parts[1] == "1",
            rows = parts[2].toIntOrNull() ?: WidgetConfigPrefs.DEFAULT_ROWS,
            targetKind = parts.getOrNull(3)?.takeIf { it.isNotEmpty() },
            targetId = parts.getOrNull(4)?.toLongOrNull(),
        )
    }

    private const val ROW_SEP = "\n"
    private const val FIELD_SEP = ","
}
