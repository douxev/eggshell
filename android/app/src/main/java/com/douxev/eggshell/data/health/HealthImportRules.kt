package com.douxev.eggshell.data.health

import androidx.health.connect.client.records.ExerciseSessionRecord
import com.douxev.eggshell.data.SportRepository
import kotlin.math.abs

/**
 * The decisions behind a Health Connect import, as pure functions.
 *
 * Their own file, and free of the client and of the vault, so they can be
 * tested on a plain JVM: which activity a workout lands under, whether it is
 * already in the history, and how far back a sync reads. Each of these fails
 * silently when it is wrong — a duplicated run, a month nobody re-read, a
 * swim filed as « Sans type » — so each is pinned by a test.
 */
internal object HealthImportRules {

    /**
     * What the first sync reads. Without the history permission — which this
     * app does not ask for — Health Connect serves at most the thirty days
     * before access was first granted, so asking for more would only cost a
     * longer query.
     */
    const val FIRST_SYNC_DAYS = 30L

    /**
     * How far behind the last successful sync the next one starts. A watch
     * syncs to its phone app hours after the run, and that app writes to
     * Health Connect later still, with the run's own start time: a window
     * starting exactly where the last one ended would never see it.
     */
    const val OVERLAP_DAYS = 2L

    /** Two sessions starting this close together are the same workout. */
    const val DEDUPE_WINDOW_MS = 60_000L

    /** The heart-rate range the vault accepts (the CHECK on sport_sessions). */
    val HEART_RATE_RANGE = 20L..300L

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /**
     * Where a sync's window starts.
     *
     * The first one, [FIRST_SYNC_DAYS] back. Later ones, [OVERLAP_DAYS] before
     * the last success — never from a future one: a clock that was set
     * forward and then corrected would otherwise leave a hole nobody reads.
     */
    fun windowStart(nowMs: Long, lastSyncMs: Long?): Long {
        if (lastSyncMs == null || lastSyncMs <= 0L) return nowMs - FIRST_SYNC_DAYS * DAY_MS
        return minOf(lastSyncMs, nowMs) - OVERLAP_DAYS * DAY_MS
    }

    /** A session already in the vault, as far as the dedupe needs to know. */
    data class Existing(val startedMs: Long, val source: String)

    /**
     * Whether a workout starting at [startMs] is already in the history.
     *
     * - Any session starting at exactly the same millisecond is the same
     *   workout, whoever logged it.
     * - Any *imported* session (a watch file, an earlier Health Connect sync,
     *   the pedometer) starting within [DEDUPE_WINDOW_MS] is too: two apps
     *   relaying one watch disagree by a few seconds.
     * - A *manual* session close by is not: it is the user's word, and it is
     *   never replaced or shadowed by an import. If both exist, the user sees
     *   both and keeps the one they want.
     */
    fun isDuplicate(startMs: Long, existing: Collection<Existing>): Boolean =
        existing.any { other ->
            other.startedMs == startMs ||
                (other.source != SportRepository.SOURCE_MANUAL &&
                    abs(other.startedMs - startMs) <= DEDUPE_WINDOW_MS)
        }

    /** A heart rate the vault will accept, or nothing. */
    fun heartRateOrNull(bpm: Long?): Long? = bpm?.takeIf { it in HEART_RATE_RANGE }

    /** Whole seconds between two instants, never negative. */
    fun durationSeconds(startMs: Long, endMs: Long): Long =
        ((endMs - startMs) / 1000L).coerceAtLeast(0L)

    /**
     * The English word for a Health Connect exercise type, handed to
     * [com.douxev.eggshell.data.watch.WatchImporter.matchActivity] the way a
     * watch file's sport name is. English because that is what the matcher's
     * families are keyed on; it finds « Course à pied » from "running".
     *
     * Null for everything else: a session with no type is better than one
     * filed under the wrong one.
     */
    fun exerciseHint(type: Int): String? = when (type) {
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "running"
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> "biking"
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "walking"
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "hiking"
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL,
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "swimming"
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
        ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING,
        ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS -> "strength"
        ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> "yoga"
        ExerciseSessionRecord.EXERCISE_TYPE_PILATES -> "pilates"
        ExerciseSessionRecord.EXERCISE_TYPE_ROWING,
        ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE -> "rowing"
        ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL -> "elliptical"
        ExerciseSessionRecord.EXERCISE_TYPE_SKIING -> "skiing"
        ExerciseSessionRecord.EXERCISE_TYPE_DANCING -> "dancing"
        ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING -> "stretching"
        ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING -> "hiit"
        else -> null
    }
}
