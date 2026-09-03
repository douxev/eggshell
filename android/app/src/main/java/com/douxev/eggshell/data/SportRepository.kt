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
) {

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
        }

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
    }

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
    }

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
    }

    suspend fun deleteSession(id: Long) = withContext(Dispatchers.IO) {
        vault.requireSession().deleteSportSession(id)
    }

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
    }

    /** Overwrite a day outright — the only way to correct one downward. */
    suspend fun setSteps(day: java.time.LocalDate, steps: Long) = withContext(Dispatchers.IO) {
        vault.requireSession().setSteps(dayKey(day), steps, System.currentTimeMillis())
    }

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
