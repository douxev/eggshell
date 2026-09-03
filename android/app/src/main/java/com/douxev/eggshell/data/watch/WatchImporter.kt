package com.douxev.eggshell.data.watch

import android.content.Context
import android.net.Uri
import com.douxev.eggshell.data.SportRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.transition.SportActivity

/**
 * Turns a file a watch exported into sport sessions.
 *
 * No account, no network, no background service, no vendor SDK: the user
 * exports a file from whatever app already talks to their watch, and hands it
 * over. That works with Garmin, Polar, Suunto, Coros, Amazfit, Samsung and
 * anything else that writes GPX or TCX; it keeps working when a vendor retires
 * an API; and it is the only shape of watch integration that involves no third
 * party at all, which is the constraint this app is built around.
 */
@Singleton
class WatchImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sport: SportRepository,
) {

    /** What an import would do, shown before anything is written. */
    data class Preview(
        val workouts: List<ImportedWorkout>,
        /** Workouts whose start time already has a session. */
        val duplicates: Set<Long>,
    ) {
        val importable: List<ImportedWorkout>
            get() = workouts.filter { it.startedMs !in duplicates }
    }

    /**
     * Read a file and say what is in it, without writing anything.
     *
     * A preview rather than a straight import, because the user is the one who
     * knows whether they already logged this ride by hand, and because a file
     * picked by mistake should cost a glance rather than a cleanup.
     */
    suspend fun preview(uri: Uri): Preview = withContext(Dispatchers.IO) {
        val workouts = context.contentResolver.openInputStream(uri)?.use {
            WorkoutFileParser.parse(it)
        }.orEmpty()
        Preview(workouts = workouts, duplicates = alreadyPresent(workouts))
    }

    /**
     * Write the workouts, matching each to one of the user's own activity types.
     *
     * Returns how many were written. Types are matched, never created: inventing
     * "Running" in someone's catalogue because their watch used that word would
     * be the app deciding how they name their own training.
     */
    suspend fun import(
        workouts: List<ImportedWorkout>,
        activities: List<SportActivity>,
    ): Int = withContext(Dispatchers.IO) {
        var written = 0
        workouts.forEach { workout ->
            val activityId = matchActivity(workout.activityHint, activities)?.id
            runCatching {
                sport.addSession(
                    activityId = activityId,
                    startedMs = workout.startedMs,
                    durationS = workout.durationS,
                    note = workout.title,
                    distanceM = workout.distanceM,
                    source = SportRepository.SOURCE_WATCH,
                )
            }.onSuccess { written++ }
        }
        written
    }

    /**
     * Start times that already have a session.
     *
     * Exact, not fuzzy. A watch reports the same start instant for the same
     * activity every time it is exported, so re-importing the same file is
     * caught — while two genuinely different sessions that happen to fall near
     * each other are left alone, which a tolerance window would silently merge.
     */
    private suspend fun alreadyPresent(workouts: List<ImportedWorkout>): Set<Long> {
        if (workouts.isEmpty()) return emptySet()
        val from = workouts.minOf { it.startedMs }
        val to = workouts.maxOf { it.startedMs } + 1
        val existing = runCatching { sport.sessionsBetween(from, to) }
            .getOrDefault(emptyList())
            .map { it.startedMs }
            .toSet()
        return workouts.map { it.startedMs }.filter { it in existing }.toSet()
    }

    companion object {
        /**
         * Which of the user's activity types a workout belongs to, if any.
         *
         * In the companion, and free of everything this class is injected with,
         * so the rule can be tested without standing up a context and a
         * repository to exercise four lines of string comparison.
         *
         * Case-insensitive, and containment in both directions, because
         * exporters are inconsistent: "Running", "running", "Trail Running" and
         * "Course a pied" all turn up for the same thing. No match yields null
         * and the session is filed with no type — honest, visible, and one tap
         * to fix, where a wrong guess is silent and has to be noticed first.
         *
         * Archived types are never matched: the user put those away, and a
         * fresh import should not quietly bring one back into their history.
         */
        internal fun matchActivity(
            hint: String?,
            activities: List<SportActivity>,
        ): SportActivity? {
            val needle = hint?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            val live = activities.filterNot { it.archived }
            return live.firstOrNull { it.name.lowercase() == needle }
                ?: live.firstOrNull { needle.contains(it.name.lowercase()) }
                ?: live.firstOrNull { it.name.lowercase().contains(needle) }
        }
    }
}
