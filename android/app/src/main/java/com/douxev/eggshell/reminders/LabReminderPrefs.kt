package com.douxev.eggshell.reminders

import android.content.Context
import android.content.SharedPreferences

/**
 * Lab reminders (blood tests, follow-ups) live outside the encrypted vault
 * because they must fire while the app is locked and have no medication
 * identity. Same privacy trade-off as [ReminderPrefs]: label + schedule are
 * visible in plain prefs; nothing else.
 */
class LabReminderPrefs(context: Context) {

    private val prefs: SharedPreferences =
        com.douxev.eggshell.data.SecurePrefs.get(context, PREFS_NAME)

    /** Free-form category tag: "lab" (default), "photo", "voice". The category
     *  is what lets the Reminders screen group entries into separate sections
     *  with the right icon and notification copy. */
    data class Entry(
        val id: Long,
        val label: String,
        val kind: String, // "interval" (every N days) | "daily" (HH:MM each day)
        val intervalDays: Int?,
        val dailyHour: Int?,
        val dailyMinute: Int?,
        val nextDueAtMs: Long,
        val category: String = CATEGORY_LAB,
    )

    fun all(): List<Entry> {
        val ids = prefs.getString(KEY_IDS, null).orEmpty().split(',')
            .mapNotNull { it.trim().toLongOrNull() }
        return ids.mapNotNull { id ->
            val label = prefs.getString(key("label", id), null) ?: return@mapNotNull null
            val kind = prefs.getString(key("kind", id), null) ?: return@mapNotNull null
            Entry(
                id = id,
                label = label,
                kind = kind,
                intervalDays = if (prefs.contains(key("interval", id)))
                    prefs.getInt(key("interval", id), 0) else null,
                dailyHour = if (prefs.contains(key("hour", id)))
                    prefs.getInt(key("hour", id), 0) else null,
                dailyMinute = if (prefs.contains(key("minute", id)))
                    prefs.getInt(key("minute", id), 0) else null,
                nextDueAtMs = prefs.getLong(key("next", id), 0L),
                category = prefs.getString(key("cat", id), null) ?: CATEGORY_LAB,
            )
        }
    }

    fun get(id: Long): Entry? = all().firstOrNull { it.id == id }

    fun put(entry: Entry) {
        val ids = (all().map { it.id } + entry.id).distinct()
        prefs.edit().apply {
            putString(KEY_IDS, ids.joinToString(","))
            putString(key("label", entry.id), entry.label)
            putString(key("kind", entry.id), entry.kind)
            entry.intervalDays?.let { putInt(key("interval", entry.id), it) }
                ?: run { remove(key("interval", entry.id)) }
            entry.dailyHour?.let { putInt(key("hour", entry.id), it) }
                ?: run { remove(key("hour", entry.id)) }
            entry.dailyMinute?.let { putInt(key("minute", entry.id), it) }
                ?: run { remove(key("minute", entry.id)) }
            putLong(key("next", entry.id), entry.nextDueAtMs)
            putString(key("cat", entry.id), entry.category)
        }.apply()
    }

    fun setNextDue(id: Long, nextDueAtMs: Long) {
        prefs.edit().putLong(key("next", id), nextDueAtMs).apply()
    }

    fun remove(id: Long) {
        val ids = all().map { it.id }.filter { it != id }
        prefs.edit().apply {
            putString(KEY_IDS, ids.joinToString(","))
            remove(key("label", id))
            remove(key("kind", id))
            remove(key("interval", id))
            remove(key("hour", id))
            remove(key("minute", id))
            remove(key("next", id))
            remove(key("cat", id))
        }.apply()
    }

    fun nextId(): Long = (all().maxOfOrNull { it.id } ?: 0L) + 1L

    private fun key(name: String, id: Long) = "${name}_$id"

    companion object {
        const val CATEGORY_LAB = "lab"
        const val CATEGORY_PHOTO = "photo"
        const val CATEGORY_VOICE = "voice"

        private const val PREFS_NAME = "transition_lab_reminder_prefs"
        private const val KEY_IDS = "ids"
    }
}
