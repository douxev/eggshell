package com.douxev.eggshell.reminders

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The medication reminders currently gathered into one notification, when
 * « Grouper les rappels de traitement » is on ([NotifContentPrefs.grouped]).
 *
 * Every schedule still has its own alarm — nothing about scheduling changes.
 * What changes is the notification: the first alarm opens a group, and any
 * other medication alarm firing within [WINDOW_MS] of it joins that group and
 * redraws the same notification ("3 traitements à prendre") instead of posting
 * one of its own. Someone who takes four things at 8 a.m. gets one buzz and
 * one « Tout pris », not four of each.
 *
 * A group closes when it is answered, when it is swiped away, or simply when
 * the window has passed: a reminder at noon does not join the morning's
 * unanswered one — it gets a notification of its own, because « Tout pris » at
 * noon must not mark the morning's doses as taken at noon.
 *
 * Only schedule ids and a timestamp are kept, in plain prefs: the same ids
 * already sit in [ReminderPrefs], and they name nothing.
 */
@Singleton
class MedReminderGroup @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences =
        com.douxev.eggshell.data.SecurePrefs.get(context, PREFS_NAME)

    /** A group: its notification slot, when it opened, and who is in it. */
    data class Group(val key: Int, val openedAtMs: Long, val members: List<Long>)

    /** Add [scheduleId] to the open group, or open a new one. */
    @Synchronized
    fun join(scheduleId: Long, nowMs: Long): Group {
        val next = GroupMath.join(open(), lastKey(), scheduleId, nowMs)
        prefs.edit()
            .putString(KEY_OPEN, GroupMath.encode(next))
            .putInt(KEY_LAST, next.key)
            .commit()
        return next
    }

    /** The group [key] was answered or dismissed: the next alarm starts afresh. */
    @Synchronized
    fun close(key: Int) {
        if (open()?.key == key) prefs.edit().remove(KEY_OPEN).commit()
    }

    /** The wipe path. */
    @Synchronized
    fun wipe() {
        prefs.edit().clear().commit()
    }

    private fun open(): Group? = GroupMath.decode(prefs.getString(KEY_OPEN, null))

    private fun lastKey(): Int = prefs.getInt(KEY_LAST, -1)

    companion object {
        /**
         * Alarms for the same minute land a few seconds apart; a reminder a
         * quarter of an hour later is still "the same round of pills".
         */
        const val WINDOW_MS = 15L * 60L * 1000L
        private const val PREFS_NAME = "transition_med_reminder_group"
        private const val KEY_OPEN = "open"
        private const val KEY_LAST = "last_key"
    }
}

/** The grouping rules, pure and tested. */
object GroupMath {

    /** Notification slots are 16-bit, like every other reminder id here. */
    private const val KEY_MASK = 0xFFFF

    fun join(open: MedReminderGroup.Group?, lastKey: Int, scheduleId: Long, nowMs: Long): MedReminderGroup.Group {
        val stillOpen = open != null &&
            nowMs >= open.openedAtMs &&
            nowMs - open.openedAtMs <= MedReminderGroup.WINDOW_MS
        if (stillOpen) {
            // A snoozed reminder firing again is already in: no duplicate line.
            return if (scheduleId in open!!.members) open else open.copy(members = open.members + scheduleId)
        }
        return MedReminderGroup.Group(
            key = (lastKey + 1) and KEY_MASK,
            openedAtMs = nowMs,
            members = listOf(scheduleId),
        )
    }

    fun encode(group: MedReminderGroup.Group): String =
        "${group.key}|${group.openedAtMs}|${group.members.joinToString(",")}"

    fun decode(raw: String?): MedReminderGroup.Group? {
        val parts = raw?.split("|") ?: return null
        if (parts.size != 3) return null
        val key = parts[0].toIntOrNull() ?: return null
        val opened = parts[1].toLongOrNull() ?: return null
        val members = parts[2].split(",").mapNotNull { it.toLongOrNull() }
        if (members.isEmpty()) return null
        return MedReminderGroup.Group(key, opened, members)
    }
}
