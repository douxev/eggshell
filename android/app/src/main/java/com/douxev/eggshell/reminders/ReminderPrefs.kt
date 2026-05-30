package com.douxev.eggshell.reminders

import android.content.Context
import android.content.SharedPreferences

/**
 * Tiny plain-SharedPreferences mirror of the active schedules.
 *
 * Why mirror? The Vault DB is encrypted and requires an unlock. But alarms
 * fire while the user is away from the app, including after a reboot, and
 * the receiver must be able to:
 *   - identify which schedule fired
 *   - compute when to fire again
 *   - persist the new "next due" timestamp
 * all without touching the encrypted DB.
 *
 * The mirror keeps the **bare scheduling metadata only** — kind / interval /
 * HH:MM / next due. The actual medication identity (name, dose) stays inside
 * SQLCipher. So an attacker who inspects this file sees "reminder at 8am
 * daily" but not for which medication.
 *
 * Synced from the DB on:
 *  - vault unlock
 *  - any schedule add / activate / deactivate / next-due update
 */
class ReminderPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class Entry(
        val scheduleId: Long,
        val kind: String,
        val intervalMinutes: Int?,
        val dailyHour: Int?,
        val dailyMinute: Int?,
        val nextDueAtMs: Long,
    )

    fun all(): List<Entry> {
        val ids = prefs.getString(KEY_IDS, null).orEmpty().split(',')
            .mapNotNull { it.trim().toLongOrNull() }
        return ids.mapNotNull { id ->
            val kind = prefs.getString(key("kind", id), null) ?: return@mapNotNull null
            Entry(
                scheduleId = id,
                kind = kind,
                intervalMinutes = if (prefs.contains(key("interval", id)))
                    prefs.getInt(key("interval", id), 0) else null,
                dailyHour = if (prefs.contains(key("hour", id)))
                    prefs.getInt(key("hour", id), 0) else null,
                dailyMinute = if (prefs.contains(key("minute", id)))
                    prefs.getInt(key("minute", id), 0) else null,
                nextDueAtMs = prefs.getLong(key("next", id), 0L),
            )
        }
    }

    fun get(scheduleId: Long): Entry? = all().firstOrNull { it.scheduleId == scheduleId }

    fun put(entry: Entry) {
        val ids = (all().map { it.scheduleId } + entry.scheduleId).distinct()
        prefs.edit().apply {
            putString(KEY_IDS, ids.joinToString(","))
            putString(key("kind", entry.scheduleId), entry.kind)
            entry.intervalMinutes?.let { putInt(key("interval", entry.scheduleId), it) }
                ?: run { remove(key("interval", entry.scheduleId)) }
            entry.dailyHour?.let { putInt(key("hour", entry.scheduleId), it) }
                ?: run { remove(key("hour", entry.scheduleId)) }
            entry.dailyMinute?.let { putInt(key("minute", entry.scheduleId), it) }
                ?: run { remove(key("minute", entry.scheduleId)) }
            putLong(key("next", entry.scheduleId), entry.nextDueAtMs)
        }.apply()
    }

    fun setNextDue(scheduleId: Long, nextDueAtMs: Long) {
        prefs.edit().putLong(key("next", scheduleId), nextDueAtMs).apply()
    }

    fun remove(scheduleId: Long) {
        val ids = all().map { it.scheduleId }.filter { it != scheduleId }
        prefs.edit().apply {
            putString(KEY_IDS, ids.joinToString(","))
            remove(key("kind", scheduleId))
            remove(key("interval", scheduleId))
            remove(key("hour", scheduleId))
            remove(key("minute", scheduleId))
            remove(key("next", scheduleId))
        }.apply()
    }

    fun wipe() {
        prefs.edit().clear().apply()
    }

    private fun key(name: String, id: Long) = "${name}_$id"

    companion object {
        private const val PREFS_NAME = "transition_reminder_prefs"
        private const val KEY_IDS = "ids"
    }
}
