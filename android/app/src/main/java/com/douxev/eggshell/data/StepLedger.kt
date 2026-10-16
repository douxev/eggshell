package com.douxev.eggshell.data

import android.content.Context
import android.content.SharedPreferences
import com.douxev.eggshell.security.MetadataObfuscator
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Where the pedometer keeps what it has counted until the vault can take it.
 *
 * # Why this exists
 *
 * The hardware counter has to be read in the background — every quarter of an
 * hour, just before midnight, just before the phone shuts down — or the steps
 * of a day the app was not opened end up credited to the day it next is. But
 * the background is exactly where the vault is shut: its key only exists after
 * an unlock. So the readings land here first, per local day, and
 * [StepSync.flush] pours them into the vault on the next real unlock — the
 * same shape as the "Pris"-while-locked dose queue, and for the same reason.
 *
 * # What is stored
 *
 * One sealed blob ([MetadataObfuscator], nondescript file and key names): the
 * last raw counter value and the boot it belonged to, when it was read, and a
 * running total per local day. Sealed rather than plain because a list of
 * "this many steps on this day" is a record of someone's movements; the raw
 * counter on its own says nothing, but it travels in the same blob so that
 * "advance the baseline" and "credit the day" are one atomic write — two
 * separate files could tear on a crash and either lose a batch of steps or
 * count it twice.
 *
 * # Totals, not deltas
 *
 * Each day holds the whole number this counter has credited to it, not the
 * increments since the last flush. That makes the flush idempotent: the vault
 * keeps the larger of stored and incoming, so pouring the same total twice —
 * after a crash between the vault write and marking it flushed — changes
 * nothing. With deltas, the same accident would count the steps twice.
 */
@Singleton
class StepLedger @Inject constructor(
    @ApplicationContext context: Context,
    private val obfuscator: MetadataObfuscator,
) {
    private val prefs: SharedPreferences = SecurePrefs.get(context, PREFS_NAME)

    /**
     * The baseline the previous build kept in plain prefs. Read once, to carry
     * the baseline over on upgrade, then dropped.
     */
    private val legacy: SharedPreferences = SecurePrefs.get(context, LEGACY_PREFS_NAME)

    /**
     * One lock for every reader and writer of the blob — the periodic worker,
     * the midnight alarm, the shutdown broadcast and the UI can all land at
     * once, and each does a read-modify-write of the whole thing.
     */
    private val mutex = Mutex()

    /** Run [block] with exclusive access; whatever it returns is persisted. */
    suspend fun <T> update(block: (StepState) -> Pair<StepState, T>): T? = mutex.withLock {
        val (next, result) = block(load())
        if (save(next)) result else null
    }

    /**
     * Exclusive access across a suspending section — the flush, which writes
     * to the vault between reading the ledger and marking it flushed, and must
     * not let a reading slip in between.
     */
    suspend fun <T> locked(block: suspend (current: StepState, commit: (StepState) -> Boolean) -> T): T =
        mutex.withLock { block(load()) { save(it) } }

    /** A snapshot, for display. Never write back what this returns. */
    suspend fun snapshot(): StepState = mutex.withLock { load() }

    /**
     * Today's total, read without the lock — for a widget drawing on the main
     * thread. Safe because every write replaces the whole blob in one commit:
     * a reader sees the old state or the new one, never half of each.
     */
    fun peekTodayUnlocked(): Long? = load().days[LocalDate.now()]?.steps

    /** Erase everything — the wipe path. Synchronous: a wipe must not linger. */
    fun wipe() {
        prefs.edit().clear().commit()
        legacy.edit().clear().commit()
    }

    private fun load(): StepState {
        val opened = obfuscator.open(prefs.getString(KEY_BLOB, null))
        if (opened != null) {
            StepCodec.decode(opened)?.let { return it }
        }
        // No ledger yet: an install that has never counted, or the first run
        // after the upgrade that introduced this file. In the second case the
        // old baseline is carried over, so the steps walked since the old
        // build last looked are not silently zeroed by a fresh first read.
        val legacyRaw = legacy.getLong(LEGACY_KEY_LAST_RAW, -1L).takeIf { it >= 0 }
        return StepState(lastRaw = legacyRaw)
    }

    /**
     * Synchronous commit, never apply(): the shutdown broadcast writes here as
     * the process is about to die, and an apply() still queued at that point
     * is a reading lost.
     */
    private fun save(state: StepState): Boolean {
        val sealed = obfuscator.seal(StepCodec.encode(state)) ?: return false
        val ok = prefs.edit().putString(KEY_BLOB, sealed).commit()
        if (ok && legacy.contains(LEGACY_KEY_LAST_RAW)) {
            legacy.edit().remove(LEGACY_KEY_LAST_RAW).commit()
        }
        return ok
    }

    companion object {
        /**
         * For the Sport widget, which draws outside the Hilt graph. Only ever
         * reached for a widget the user opted into showing content, and never
         * in paranoid mode — the provider does not draw rows there at all.
         */
        fun peekToday(context: Context): Long? =
            StepLedger(context, MetadataObfuscator()).peekTodayUnlocked()

        // Nondescript on purpose, like the dose queue: nothing here names steps.
        private const val PREFS_NAME = "androidx_cache_meta"
        private const val KEY_BLOB = "idx"
        private const val LEGACY_PREFS_NAME = "transition_step_counter"
        private const val LEGACY_KEY_LAST_RAW = "last_raw"
    }
}

/**
 * One local day as the ledger knows it.
 *
 * [flushed] says the vault has been given [steps]. A day is only ever dropped
 * once it is flushed and old, so a vault left locked for a month loses nothing.
 */
data class StepDayEntry(val steps: Long, val flushed: Boolean)

/** Everything the ledger holds, as one immutable value. */
data class StepState(
    /** The last raw counter value, or null before the first reading. */
    val lastRaw: Long? = null,
    /** `Settings.Global.BOOT_COUNT` at that reading, when the device has it. */
    val lastBoot: Int? = null,
    /** Wall-clock time of that reading. */
    val lastReadMs: Long? = null,
    /**
     * Whether the one-time upgrade seeding has run — see [StepSync.flush].
     * False on a ledger that has never met an unlocked vault.
     */
    val seeded: Boolean = false,
    val days: Map<LocalDate, StepDayEntry> = emptyMap(),
    /**
     * Days the user set by hand. An import that also reports steps (Health
     * Connect) must leave these alone, or the next sync would put back the
     * figure the user just corrected away.
     */
    val corrected: Set<LocalDate> = emptySet(),
)

/**
 * The pedometer's arithmetic, as pure functions over plain values.
 *
 * Everything here is a place to be silently wrong — a reboot misread, a
 * midnight misplaced, a day counted twice — and none of it throws or looks
 * wrong on screen. So none of it touches a sensor, a clock or a file, and all
 * of it is unit-tested.
 */
object StepMath {

    /**
     * How many steps a raw reading adds to the previous one.
     *
     * The counter counts since boot and restarts at zero on every reboot, so a
     * raw value only means something relative to the boot it came from:
     *
     * - no baseline yet → 0. Claiming the whole counter would credit someone
     *   with every step since their phone last booted, possibly weeks of them.
     * - a different boot than the baseline's → the whole reading: everything
     *   it reports happened since that boot. Comparing boot counts is what
     *   catches a reboot followed by more walking than the old baseline — a
     *   counter that went 12 000 → reboot → 15 000 looks like +3 000 to a
     *   rule that only watches for the number going down.
     * - same boot (or no boot count on this device) and lower → the sensor
     *   restarted under us; same reasoning, the whole reading.
     * - otherwise the difference.
     */
    fun delta(previousRaw: Long?, previousBoot: Int?, raw: Long, boot: Int?): Long = when {
        raw < 0 -> 0L
        previousRaw == null -> 0L
        boot != null && previousBoot != null && boot != previousBoot -> raw
        raw < previousRaw -> raw
        else -> raw - previousRaw
    }

    /**
     * Spread [delta] steps over the local days between two readings.
     *
     * Almost always one day: readings come every quarter of an hour and once
     * just before midnight. When a gap does straddle midnight — the phone was
     * off at 23:59, the app was force-stopped — the steps are shared in
     * proportion to how much of the gap fell on each day. That is an estimate
     * and the code says so; the alternative, crediting everything to the day
     * of the reading, is the bug this replaces: a week of walking landing on
     * the morning the app was next opened.
     *
     * The shares are computed from cumulative boundaries, so they always add up
     * to exactly [delta] — rounding never creates or loses a step.
     */
    fun split(delta: Long, fromMs: Long?, toMs: Long, zone: ZoneId): Map<LocalDate, Long> {
        if (delta <= 0) return emptyMap()
        val toDay = Instant.ofEpochMilli(toMs).atZone(zone).toLocalDate()
        // A missing, future or absurdly old baseline time (a clock set back, a
        // corrupt value) cannot be split over meaningfully: today gets it.
        if (fromMs == null || fromMs >= toMs || toMs - fromMs > MAX_SPLIT_MS) {
            return mapOf(toDay to delta)
        }
        val fromDay = Instant.ofEpochMilli(fromMs).atZone(zone).toLocalDate()
        if (fromDay == toDay) return mapOf(toDay to delta)

        val segments = mutableListOf<Pair<LocalDate, Long>>()
        var day = fromDay
        while (!day.isAfter(toDay)) {
            val start = maxOf(fromMs, day.atStartOfDay(zone).toInstant().toEpochMilli())
            val end = minOf(toMs, day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
            if (end > start) segments += day to (end - start)
            day = day.plusDays(1)
        }
        val total = segments.sumOf { it.second }.toDouble()
        val out = LinkedHashMap<LocalDate, Long>()
        var cumulative = 0L
        var given = 0L
        segments.forEachIndexed { i, (d, weight) ->
            cumulative += weight
            val upTo = if (i == segments.lastIndex) delta else (delta * (cumulative / total)).toLong()
            val share = upTo - given
            given = upTo
            if (share > 0) out[d] = (out[d] ?: 0L) + share
        }
        return out
    }

    /**
     * Credit a reading to [state] and move the baseline forward.
     *
     * New steps on a day reopen it (flushed = false); days nobody walked on
     * are left untouched.
     */
    fun record(
        state: StepState,
        raw: Long,
        boot: Int?,
        nowMs: Long,
        zone: ZoneId,
        /** Wall-clock time the device booted, when known. */
        bootMs: Long? = null,
    ): Pair<StepState, Long> {
        val delta = delta(state.lastRaw, state.lastBoot, raw, boot)
        // After a reboot every counted step happened since the boot, so the
        // split starts there — not at the last reading, which may be the
        // evening before: spreading this morning's walk over last night would
        // hand part of it to yesterday.
        val previous = state.lastRaw
        val rebooted = previous != null &&
            ((boot != null && state.lastBoot != null && boot != state.lastBoot) || raw < previous)
        val from = if (rebooted && bootMs != null) maxOf(state.lastReadMs ?: bootMs, bootMs) else state.lastReadMs
        // A baseline with no time — the one carried over from the build before
        // the ledger — says how many steps the counter held, not when. The
        // growth since could be a week of walking, and crediting all of it to
        // today is exactly how a 1 800-step day read 32 000. Only what provably
        // happened today counts (a boot this morning bounds it); otherwise the
        // reading just becomes the baseline. A lost estimate beats a false day.
        val credit = when {
            from != null -> delta
            bootMs != null && bootMs >= startOfDay(nowMs, zone) -> delta
            else -> 0L
        }
        val shares = split(credit, from ?: bootMs, nowMs, zone)
        val days = state.days.toMutableMap()
        shares.forEach { (day, steps) ->
            val current = days[day]?.steps ?: 0L
            days[day] = StepDayEntry(current + steps, flushed = false)
        }
        return state.copy(lastRaw = raw, lastBoot = boot, lastReadMs = nowMs, days = days) to credit
    }

    private fun startOfDay(atMs: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(atMs).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

    /**
     * Forget days the vault already has and nobody can credit any more.
     *
     * Kept: anything not yet flushed, whatever its age; today; the day of the
     * last reading (the next one may still split into it); and the last
     * [RETENTION_DAYS] — so a late reading lands on a running total rather
     * than on a fresh zero the vault's MAX would ignore.
     */
    fun prune(state: StepState, today: LocalDate, zone: ZoneId): StepState {
        val lastReadDay = state.lastReadMs?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
        val horizon = today.minusDays(RETENTION_DAYS)
        val kept = state.days.filter { (day, entry) ->
            !entry.flushed || day == today || day == lastReadDay || !day.isBefore(horizon)
        }
        // Corrections only matter while an import can still reach the day;
        // Health Connect reads at most 30 days back.
        val correctionHorizon = today.minusDays(CORRECTION_DAYS)
        return state.copy(days = kept, corrected = state.corrected.filterTo(mutableSetOf()) { !it.isBefore(correctionHorizon) })
    }

    /** How long flushed days are kept; see [prune]. */
    const val RETENTION_DAYS = 14L

    /** How long a hand correction shields its day from imports. */
    const val CORRECTION_DAYS = 40L

    /** Past this gap a split would be invention, not estimation. */
    private const val MAX_SPLIT_MS = 400L * 24 * 60 * 60 * 1000
}

/**
 * Text form of [StepState], inside the sealed blob.
 *
 * `2|lastRaw|lastBoot|lastReadMs|seeded|day:steps:flushed,…` — blank fields for
 * nulls. Versioned so a later format can still read this one; an unreadable
 * blob decodes to null and the ledger starts over rather than crashing a
 * broadcast receiver, which costs at most the steps since the last flush.
 */
object StepCodec {
    private const val VERSION = "2"

    fun encode(state: StepState): String = listOf(
        VERSION,
        state.lastRaw?.toString().orEmpty(),
        state.lastBoot?.toString().orEmpty(),
        state.lastReadMs?.toString().orEmpty(),
        if (state.seeded) "1" else "0",
        state.days.entries
            .sortedBy { it.key }
            .joinToString(",") { (day, e) -> "$day:${e.steps}:${if (e.flushed) 1 else 0}" },
        // Appended last, so a ledger written before it existed still decodes.
        state.corrected.sorted().joinToString(","),
    ).joinToString("|")

    fun decode(text: String): StepState? {
        val parts = text.split("|")
        if (parts.size < 6 || parts[0] != VERSION) return null
        val days = parts[5].split(",").filter { it.isNotBlank() }.mapNotNull { row ->
            val f = row.split(":")
            if (f.size < 3) return@mapNotNull null
            val day = runCatching { LocalDate.parse(f[0]) }.getOrNull() ?: return@mapNotNull null
            val steps = f[1].toLongOrNull() ?: return@mapNotNull null
            day to StepDayEntry(steps, f[2] == "1")
        }.toMap()
        val corrected = parts.getOrNull(6).orEmpty().split(",").filter { it.isNotBlank() }
            .mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
            .toSet()
        return StepState(
            lastRaw = parts[1].toLongOrNull(),
            lastBoot = parts[2].toIntOrNull(),
            lastReadMs = parts[3].toLongOrNull(),
            seeded = parts[4] == "1",
            days = days,
            corrected = corrected,
        )
    }
}
