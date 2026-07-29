package com.douxev.eggshell.data

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest
import com.douxev.eggshell.BuildConfig

/**
 * Resolves SharedPreferences by a *logical* name, mapping it to a
 * non-descriptive on-disk file name in release builds so a forensic dump of
 * `/data/data/<pkg>/shared_prefs/` shows opaque files (e.g. `sp_3f9a2c…`)
 * instead of a row of `transition_*.xml` that spell out the app's purpose.
 *
 * Build-conditional so development stays easy:
 *  - **debug**: the file keeps its readable logical name — inspect/edit it as
 *    usual via Device Explorer.
 *  - **release**: the file is a deterministic hash of the logical name. The
 *    mapping lives only in this method (and the source), never on disk.
 *
 * On first release access we migrate any legacy `logical`-named file into the
 * hashed one (value-for-value) and delete the legacy file, so existing installs
 * keep their data (vault wrapped-key, schedules, …) and stop leaking the old
 * descriptive filename. Debug builds never migrate.
 */
object SecurePrefs {

    fun get(context: Context, logicalName: String): SharedPreferences {
        val app = context.applicationContext
        if (BuildConfig.DEBUG) {
            return app.getSharedPreferences(logicalName, Context.MODE_PRIVATE)
        }
        val opaqueName = opaque(logicalName)
        val target = app.getSharedPreferences(opaqueName, Context.MODE_PRIVATE)
        // Migrate once: only when the target is empty but a legacy file has data.
        if (target.all.isEmpty()) {
            val legacy = app.getSharedPreferences(logicalName, Context.MODE_PRIVATE)
            if (legacy.all.isNotEmpty()) {
                // Only delete the source once the copy is provably on disk.
                // copyInto used to end in apply(), which returns before the
                // write lands — and the delete right after it is immediate. A
                // process death in that window destroyed the vault's wrapped
                // master key, permanently, on the very first release launch
                // after an upgrade.
                if (copyInto(legacy, target)) {
                    app.deleteSharedPreferences(logicalName)
                }
            }
        }
        return target
    }

    private fun copyInto(from: SharedPreferences, to: SharedPreferences): Boolean {
        val editor = to.edit()
        for ((key, value) in from.all) {
            when (value) {
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Set<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    editor.putStringSet(key, value as Set<String>)
                }
            }
        }
        return editor.commit()
    }

    private fun opaque(logicalName: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(logicalName.toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(24)
        for (i in 0 until 12) hex.append("%02x".format(digest[i]))
        // A neutral, library-looking prefix; nothing names this app's intent.
        return "sp_$hex"
    }
}
