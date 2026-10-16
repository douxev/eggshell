package com.douxev.eggshell.data

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.transition.NewSportActivity
import uniffi.transition.NewSportSession
import uniffi.transition.SportActivity
import uniffi.transition.SportSession
import uniffi.transition.StepDay

/**
 * Sport sessions, the activity catalogue, and daily step totals.
 *
 * Thin, like the other module repositories: the rules live in the Rust core
 * (`transition-core/src/sport.rs`), and this is the layer that puts them on a
 * background thread and turns local dates into the `YYYY-MM-DD` keys the core
 * stores steps under. That conversion is the one thing this side genuinely
 * owns, because the device timezone is not visible from Rust.
 */
@Singleton
class SportRepository @Inject constructor(
    private val vault: VaultRepository,
    private val mirrors: com.douxev.eggshell.widget.WidgetMirrorUpdater,
) {
    /**
     * Kept in step after every write. A widget configured to show content
     * reads a mirror, not the vault — so an entry saved and not mirrored is a
     * home screen quietly showing the previous state. Cheap when nobody opted
     * in, which is the default, and never allowed to turn a successful write
     * into a failure.
     */
    private suspend fun mirrorChanged() {
        runCatching { mirrors.refresh() }
    }


    // -- Activities ----------------------------------------------------------

    suspend fun activities(includeArchived: Boolean = false): List<SportActivity> =
        withContext(Dispatchers.IO) {
            vault.requireSession().listSportActivities(includeArchived)
        }

    suspend fun addActivity(name: String, kind: String, color: Long?): SportActivity =
        withContext(Dispatchers.IO) {
            vault.requireSession().addSportActivity(
                NewSportActivity(name = name, kind = kind, color = color),
                System.currentTimeMillis(),
            )
        }

    suspend fun updateActivity(id: Long, name: String, kind: String, color: Long?) =
        withContext(Dispatchers.IO) {
            vault.requireSession().updateSportActivity(
                id, NewSportActivity(name = name, kind = kind, color = color),
            )
        }.also { mirrorChanged() }

    suspend fun setActivityArchived(id: Long, archived: Boolean) = withContext(Dispatchers.IO) {
        vault.requireSession().setSportActivityArchived(id, archived)
    }

    /**
     * Delete an activity type. Its sessions survive, with no type.
     *
     * Archiving is the gentler option and what the UI offers first; this exists
     * for someone who genuinely wants the name gone. The core enforces the
     * distinction — `ON DELETE SET NULL`, never a cascade — so a mis-tap here
     * cannot take a training history with it.
     */
    suspend fun deleteActivity(id: Long) = withContext(Dispatchers.IO) {
        vault.requireSession().deleteSportActivity(id)
    }.also { mirrorChanged() }

    // -- Sessions ------------------------------------------------------------

    suspend fun sessions(offset: Long = 0, limit: Long = 100): List<SportSession> =
        withContext(Dispatchers.IO) { vault.requireSession().listSportSessions(offset, limit) }

    /** Half-open `[fromMs, toMs)`, so adjacent windows never double-count. */
    suspend fun sessionsBetween(fromMs: Long, toMs: Long): List<SportSession> =
        withContext(Dispatchers.IO) {
            vault.requireSession().listSportSessionsBetween(fromMs, toMs)
        }

    suspend fun session(id: Long): SportSession? =
        withContext(Dispatchers.IO) { vault.requireSession().getSportSession(id) }

    suspend fun addSession(
        activityId: Long?,
        startedMs: Long,
        durationS: Long,
        note: String?,
        distanceM: Double? = null,
        avgHr: Long? = null,
        maxHr: Long? = null,
        source: String = SOURCE_MANUAL,
    ): SportSession = withContext(Dispatchers.IO) {
        vault.requireSession().addSportSession(
            NewSportSession(
                activityId = activityId,
                startedMs = startedMs,
                durationS = durationS,
                freeText = note?.takeIf { it.isNotBlank() },
                distanceM = distanceM,
                avgHr = avgHr,
                maxHr = maxHr,
                source = source,
            )
        )
    }.also { mirrorChanged() }

    suspend fun updateSession(
        id: Long,
        activityId: Long?,
        startedMs: Long,
        durationS: Long,
        note: String?,
        distanceM: Double? = null,
        avgHr: Long? = null,
        maxHr: Long? = null,
        source: String = SOURCE_MANUAL,
    ): SportSession = withContext(Dispatchers.IO) {
        vault.requireSession().updateSportSession(
            id,
            NewSportSession(
                activityId = activityId,
                startedMs = startedMs,
                durationS = durationS,
                freeText = note?.takeIf { it.isNotBlank() },
                distanceM = distanceM,
                avgHr = avgHr,
                maxHr = maxHr,
                source = source,
            ),
        )
    }.also { mirrorChanged() }

    suspend fun deleteSession(id: Long) = withContext(Dispatchers.IO) {
        vault.requireSession().deleteSportSession(id)
    }.also { mirrorChanged() }

    // -- Steps ---------------------------------------------------------------

    /**
     * Record a running total for a day, keeping the larger of stored and given.
     *
     * The MAX lives in the core, not here, because it is a correctness rule
     * rather than a UI choice: the hardware counter resets on reboot, and the
     * first read afterwards is a small number that would otherwise erase the
     * day's walking.
     */
    suspend fun recordSteps(day: java.time.LocalDate, steps: Long) = withContext(Dispatchers.IO) {
        vault.requireSession().recordSteps(dayKey(day), steps, System.currentTimeMillis())
    }.also { mirrorChanged() }

    /**
     * [recordSteps] for several days in one go, with one mirror refresh at the
     * end rather than one per day — the pedometer flushes up to a fortnight of
     * totals at a time.
     */
    suspend fun recordStepDays(days: Map<java.time.LocalDate, Long>) = withContext(Dispatchers.IO) {
        val session = vault.requireSession()
        val now = System.currentTimeMillis()
        days.forEach { (day, steps) -> session.recordSteps(dayKey(day), steps, now) }
    }.also { mirrorChanged() }

    /** Overwrite a day outright — the only way to correct one downward. */
    suspend fun setSteps(day: java.time.LocalDate, steps: Long) = withContext(Dispatchers.IO) {
        vault.requireSession().setSteps(dayKey(day), steps, System.currentTimeMillis())
    }.also { mirrorChanged() }

    suspend fun stepDays(from: java.time.LocalDate, to: java.time.LocalDate): List<StepDay> =
        withContext(Dispatchers.IO) {
            vault.requireSession().listStepDays(dayKey(from), dayKey(to))
        }

    suspend fun stepDay(day: java.time.LocalDate): StepDay? =
        withContext(Dispatchers.IO) { vault.requireSession().getStepDay(dayKey(day)) }

    companion object {
        const val SOURCE_MANUAL = "manual"
        const val SOURCE_PEDOMETER = "pedometer"

        /** A session read out of a file a watch exported. */
        const val SOURCE_WATCH = "watch"

        /** A session imported from Health Connect (data/health). */
        const val SOURCE_HEALTH = "health"

        /**
         * The `YYYY-MM-DD` key the core stores a day under.
         *
         * ISO_LOCAL_DATE explicitly, not a localised format: the key is
         * compared lexicographically by the range query, and only this layout
         * makes string order match date order. A locale that renders
         * "03/09/2026" would sort September before February.
         */
        fun dayKey(day: java.time.LocalDate): String =
            day.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
    }
}
