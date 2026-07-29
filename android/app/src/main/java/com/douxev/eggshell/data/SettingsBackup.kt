package com.douxev.eggshell.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONArray
import org.json.JSONObject
import com.douxev.eggshell.reminders.LabReminderPrefs

/**
 * Snapshot and restore the app settings that live *outside* the vault.
 *
 * A backup used to carry the database and the media and nothing else, so a
 * restore returned every measurement and left the person to rebuild their
 * theme, units, notification preferences and lab reminders by hand.
 *
 * The list of what travels is an explicit **allow**-list. A deny-list would
 * mean that every preference store added later is backed up by default, and
 * some of these stores must never leave the device — getting that wrong is not
 * a lost setting, it is a security hole.
 */
@Singleton
class SettingsBackup @Inject constructor(
    @ApplicationContext private val context: Context,
    private val alarms: com.douxev.eggshell.reminders.AlarmScheduler,
) {

    fun snapshot(): ByteArray {
        val root = JSONObject()
        root.put("version", FORMAT_VERSION)

        val stores = JSONObject()
        for (name in PORTABLE_STORES) {
            val prefs = SecurePrefs.get(context, name)
            val entries = JSONObject()
            for ((key, value) in prefs.all) {
                encode(value)?.let { entries.put(key, it) }
            }
            if (entries.length() > 0) stores.put(name, entries)
        }
        root.put("stores", stores)

        // Lab reminders go through their own API rather than as raw bytes:
        // their labels are sealed with a Keystore key, so the stored form is
        // device-bound and would restore as undecryptable garbage. Read them
        // open, write them back sealed on the far side.
        val labs = JSONArray()
        for (e in LabReminderPrefs(context).all()) {
            labs.put(
                JSONObject()
                    .put("id", e.id)
                    .put("label", e.label)
                    .put("kind", e.kind)
                    .put("intervalDays", e.intervalDays ?: JSONObject.NULL)
                    .put("dailyHour", e.dailyHour ?: JSONObject.NULL)
                    .put("dailyMinute", e.dailyMinute ?: JSONObject.NULL)
                    .put("nextDueAtMs", e.nextDueAtMs)
                    .put("category", e.category)
            )
        }
        root.put("labReminders", labs)

        return root.toString().toByteArray(Charsets.UTF_8)
    }

    fun restore(json: ByteArray) {
        val root = JSONObject(String(json, Charsets.UTF_8))
        // Unknown future versions are ignored rather than half-applied: a
        // partially restored settings set is harder to reason about than none.
        if (root.optInt("version", 0) != FORMAT_VERSION) return

        val stores = root.optJSONObject("stores") ?: JSONObject()
        for (name in PORTABLE_STORES) {
            val entries = stores.optJSONObject(name) ?: continue
            val editor = SecurePrefs.get(context, name).edit()
            for (key in entries.keys()) {
                val cell = entries.optJSONObject(key) ?: continue
                decodeInto(editor, key, cell)
            }
            editor.commit()
        }

        val labPrefs = LabReminderPrefs(context)
        val labs = root.optJSONArray("labReminders") ?: JSONArray()
        for (i in 0 until labs.length()) {
            val o = labs.optJSONObject(i) ?: continue
            labPrefs.put(
                LabReminderPrefs.Entry(
                    id = o.optLong("id"),
                    label = o.optString("label"),
                    kind = o.optString("kind"),
                    intervalDays = o.optIntOrNull("intervalDays"),
                    dailyHour = o.optIntOrNull("dailyHour"),
                    dailyMinute = o.optIntOrNull("dailyMinute"),
                    nextDueAtMs = o.optLong("nextDueAtMs"),
                    category = o.optString("category"),
                )
            )
        }
        // Re-arm them. The entry alone is inert: alarms live in AlarmManager,
        // which knows nothing about a restore, so without this the reminders
        // would reappear in the list and then never fire again.
        val now = System.currentTimeMillis()
        for (e in labPrefs.all()) {
            if (e.nextDueAtMs > now) runCatching { alarms.scheduleLab(e.id, e.nextDueAtMs) }
        }
    }

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (isNull(key)) null else optInt(key)

    /** SharedPreferences is untyped at rest, so the type travels with the value. */
    private fun encode(value: Any?): JSONObject? = when (value) {
        is String -> JSONObject().put("t", "s").put("v", value)
        is Int -> JSONObject().put("t", "i").put("v", value)
        is Long -> JSONObject().put("t", "l").put("v", value)
        is Float -> JSONObject().put("t", "f").put("v", value.toDouble())
        is Boolean -> JSONObject().put("t", "b").put("v", value)
        is Set<*> -> JSONObject().put("t", "ss").put("v", JSONArray(value.map { it.toString() }))
        else -> null
    }

    private fun decodeInto(
        editor: android.content.SharedPreferences.Editor,
        key: String,
        cell: JSONObject,
    ) {
        when (cell.optString("t")) {
            "s" -> editor.putString(key, cell.optString("v"))
            "i" -> editor.putInt(key, cell.optInt("v"))
            "l" -> editor.putLong(key, cell.optLong("v"))
            "f" -> editor.putFloat(key, cell.optDouble("v").toFloat())
            "b" -> editor.putBoolean(key, cell.optBoolean("v"))
            "ss" -> {
                val arr = cell.optJSONArray("v") ?: return
                editor.putStringSet(key, (0 until arr.length()).map { arr.optString(it) }.toSet())
            }
        }
    }

    private companion object {
        const val FORMAT_VERSION = 1

        /**
         * Everything that is a genuine user preference and means the same thing
         * on another device.
         */
        val PORTABLE_STORES = listOf(
            "transition_theme_prefs",
            "transition_security_prefs",
            "transition_hormone_units",
            "transition_notif_content_prefs",
            "transition_reminder_priority",
            "transition_nav_tabs",
            "transition_summary",
            "transition_voice_prefs",
            "transition_med_alias_prefs",
        )

        // Deliberately absent, and why:
        //
        //  transition_vault_prefs      the Keystore-wrapped master key, the KDF
        //                              material, the recovery wrap and the
        //                              access/decoy PIN hashes. The wrapped key
        //                              is bound to a Keystore that does not
        //                              exist on the target device, and
        //                              restoring PIN hashes would import
        //                              someone else's decoy configuration.
        //  eggshell_pin_throttle       brute-force counters. Restoring an old
        //                              backup would reset an active lockout.
        //  transition_reminder_prefs   a mirror of dose_schedules; syncFromDb
        //                              rebuilds it at the first unlock, and a
        //                              stale copy would fight that.
        //  pending doses               a transient queue of taps not yet
        //                              written to the vault.
        //  whats_new / module_seen /   purely local UI bookkeeping.
        //  report_export
    }
}
