package com.douxev.eggshell.data.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.douxev.eggshell.data.SportRepository
import com.douxev.eggshell.data.VaultRepository
import com.douxev.eggshell.data.watch.WatchImporter
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * Reads workouts and daily steps out of Health Connect into the vault.
 *
 * **Read-only, and only four kinds of record**: exercise sessions, steps,
 * heart rate and distance. No routes, no background read, no history beyond
 * what Health Connect serves by default — each of those would be a permission
 * the user has to weigh, for data this module does not need. Nothing is ever
 * written back.
 *
 * **Only with the vault open.** A sync runs after a real unlock and when the
 * Sport screen opens, both of which mean a session exists; the decoy PIN never
 * opens one, so it never reaches here, and paranoid mode works the same way
 * as every other mode. Health Connect itself only serves an app in the
 * foreground without the background permission, which those two moments
 * always are.
 *
 * **Never throws into its callers.** The unlock path must not fail because a
 * provider is missing, a permission was withdrawn or a query was rate-limited:
 * every outcome is a [Result] on [status], which the Sport settings card shows.
 *
 * What lands where:
 * - An exercise session becomes a sport session with source
 *   [SportRepository.SOURCE_HEALTH]: duration from its bounds, distance and
 *   average/maximum heart rate aggregated over them (kept only when the vault
 *   would accept them), its type matched against the user's own catalogue,
 *   its title as the note. See [HealthImportRules] for the dedupe.
 * - Steps become daily totals in the local zone, through the same
 *   keep-the-larger write the pedometer uses — Health Connect's total already
 *   counts this phone's own steps, de-duplicated across sources, so the larger
 *   of the two is the right one.
 */
@Singleton
class HealthConnectSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vault: VaultRepository,
    private val sport: SportRepository,
    private val prefs: HealthConnectPrefs,
    private val steps: com.douxev.eggshell.data.StepSync,
) {

    /** Whether there is a Health Connect to talk to on this phone. */
    enum class Availability {
        AVAILABLE,

        /** Not installed, or too old: worth a link to the store. */
        NEEDS_PROVIDER,

        /** Not on this phone at all (Android 8, some builds): nothing to offer. */
        UNAVAILABLE,
    }

    /** What the last sync did, for the card to say. */
    sealed interface Result {
        data class Imported(val sessions: Int, val stepDays: Int) : Result
        /** Health Connect granted none of the four read permissions. */
        data object NotPermitted : Result
        data object Unavailable : Result
        data class Failed(val reason: String) : Result
    }

    data class Status(val running: Boolean = false, val last: Result? = null)

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    /** One sync at a time; a second request while one runs is simply dropped. */
    private val running = Mutex()

    /** Outlives the screen that asked: a sync cut short leaves no window recorded. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun availability(): Availability =
        when (runCatching { HealthConnectClient.getSdkStatus(context, PROVIDER_PACKAGE) }
            .getOrDefault(HealthConnectClient.SDK_UNAVAILABLE)) {
            HealthConnectClient.SDK_AVAILABLE -> Availability.AVAILABLE
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> Availability.NEEDS_PROVIDER
            else -> Availability.UNAVAILABLE
        }

    /** The read permissions currently granted, among the four asked for. Empty when unavailable. */
    suspend fun grantedPermissions(): Set<String> {
        if (availability() != Availability.AVAILABLE) return emptySet()
        return try {
            client().permissionController.getGrantedPermissions().intersect(PERMISSIONS)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            emptySet()
        }
    }

    /**
     * Hand every Health Connect permission back. For the switch going off: an
     * app that no longer imports has no reason to keep the right to read.
     * Best effort — a provider that is gone has nothing to revoke.
     */
    suspend fun revokeAccess() {
        if (availability() != Availability.AVAILABLE) return
        try {
            client().permissionController.revokeAllPermissions()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            android.util.Log.w(TAG, "could not revoke health connect access", failure)
        }
    }

    /**
     * Start a sync on this class's own scope and return at once. For the
     * unlock path and for a screen opening: neither should wait on Health
     * Connect, and neither should be able to cancel a sync half-way.
     *
     * [force] skips the "synced a few minutes ago" check; the card's
     * « Synchroniser maintenant » uses it, the automatic triggers do not.
     */
    fun syncInBackground(force: Boolean = false) {
        scope.launch {
            try {
                syncIfEnabled(force)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                android.util.Log.e(TAG, "health connect sync failed", failure)
            }
        }
    }

    /**
     * Sync now if the import is on, the vault is open and nothing else is
     * syncing. Returns what happened, or null when it did not run at all.
     */
    suspend fun syncIfEnabled(force: Boolean = false): Result? {
        if (!prefs.enabled.value || !vault.isUnlocked) return null
        val last = prefs.lastSyncMs.value
        if (!force && last != null && System.currentTimeMillis() - last in 0 until MIN_INTERVAL_MS) {
            return null
        }
        if (!running.tryLock()) return null
        _status.value = _status.value.copy(running = true)
        val result = try {
            withContext(Dispatchers.IO) { sync() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (denied: SecurityException) {
            // Either access was withdrawn between the check and the read, or
            // Health Connect refused a read from an app that just went to the
            // background. Only the first is worth « allow access » on the card.
            if (grantedPermissions().isEmpty()) {
                Result.NotPermitted
            } else {
                Result.Failed(denied.javaClass.simpleName + (denied.message?.let { ": $it" }.orEmpty()))
            }
        } catch (failure: Exception) {
            android.util.Log.e(TAG, "health connect sync failed", failure)
            Result.Failed(failure.javaClass.simpleName + (failure.message?.let { ": $it" }.orEmpty()))
        } finally {
            running.unlock()
        }
        _status.value = Status(running = false, last = result)
        return result
    }

    private suspend fun sync(): Result {
        if (availability() != Availability.AVAILABLE) return Result.Unavailable
        val client = client()
        val granted = client.permissionController.getGrantedPermissions()
        if (granted.intersect(PERMISSIONS).isEmpty()) return Result.NotPermitted

        val now = System.currentTimeMillis()
        val from = HealthImportRules.windowStart(now, prefs.lastSyncMs.value)
        val seen = prefs.seenRecordIds()

        var imported = 0
        val readSessions = mutableListOf<ExerciseSessionRecord>()
        if (READ_EXERCISE in granted) {
            readSessions += readSessions(client, from, now)
            imported = importSessions(client, granted, readSessions, seen, from, now)
        }

        var stepDays = 0
        if (READ_STEPS in granted) {
            stepDays = importSteps(client, from)
        }

        // Only after everything above landed: a sync that failed half-way
        // leaves the window where it was, and the next one reads it again —
        // the dedupe makes that harmless.
        prefs.setLastSyncMs(now)
        // The next window starts where windowStart says it will for a last
        // sync of `now`; only the records inside it need remembering.
        val nextFrom = HealthImportRules.windowStart(now, now)
        prefs.setSeenRecordIds(
            readSessions
                .filter { it.startTime.toEpochMilli() >= nextFrom }
                .map { it.metadata.id }
                .toSet()
        )
        return Result.Imported(sessions = imported, stepDays = stepDays)
    }

    private suspend fun readSessions(
        client: HealthConnectClient,
        from: Long,
        to: Long,
    ): List<ExerciseSessionRecord> {
        val out = mutableListOf<ExerciseSessionRecord>()
        var token: String? = null
        do {
            val page = client.readRecords(
                ReadRecordsRequest(
                    recordType = ExerciseSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(
                        Instant.ofEpochMilli(from), Instant.ofEpochMilli(to),
                    ),
                    pageToken = token,
                )
            )
            out += page.records
            token = page.pageToken
        } while (token != null && out.size < MAX_SESSIONS)
        return out
    }

    private suspend fun importSessions(
        client: HealthConnectClient,
        granted: Set<String>,
        records: List<ExerciseSessionRecord>,
        seen: Set<String>,
        from: Long,
        to: Long,
    ): Int {
        if (records.isEmpty()) return 0
        val window = HealthImportRules.DEDUPE_WINDOW_MS
        val existing = sport.sessionsBetween(from - window, to + window)
            .map { HealthImportRules.Existing(it.startedMs, it.source) }
            .toMutableList()
        val activities = sport.activities(includeArchived = true)
        val metrics = buildSet {
            if (READ_HEART_RATE in granted) {
                add(HeartRateRecord.BPM_AVG)
                add(HeartRateRecord.BPM_MAX)
            }
            if (READ_DISTANCE in granted) add(DistanceRecord.DISTANCE_TOTAL)
        }

        var imported = 0
        for (record in records.sortedBy { it.startTime }) {
            // Handled by the previous sync, imported or deliberately skipped —
            // and possibly deleted by the user since, which must stay deleted.
            if (record.metadata.id in seen) continue
            val startMs = record.startTime.toEpochMilli()
            if (HealthImportRules.isDuplicate(startMs, existing)) continue

            // A session with no length has nothing to aggregate over, and
            // Health Connect refuses an empty time range outright.
            val totals = if (metrics.isEmpty() || !record.endTime.isAfter(record.startTime)) {
                null
            } else {
                client.aggregate(
                    AggregateRequest(
                        metrics = metrics,
                        timeRangeFilter = TimeRangeFilter.between(record.startTime, record.endTime),
                    )
                )
            }
            val distance = totals?.get(DistanceRecord.DISTANCE_TOTAL)?.inMeters?.takeIf { it > 0.0 }

            sport.addSession(
                activityId = WatchImporter.matchActivity(
                    HealthImportRules.exerciseHint(record.exerciseType), activities,
                )?.id,
                startedMs = startMs,
                durationS = HealthImportRules.durationSeconds(startMs, record.endTime.toEpochMilli()),
                note = record.title?.trim()?.takeIf { it.isNotEmpty() },
                distanceM = distance,
                avgHr = HealthImportRules.heartRateOrNull(totals?.get(HeartRateRecord.BPM_AVG)),
                maxHr = HealthImportRules.heartRateOrNull(totals?.get(HeartRateRecord.BPM_MAX)),
                source = SportRepository.SOURCE_HEALTH,
            )
            existing += HealthImportRules.Existing(startMs, SportRepository.SOURCE_HEALTH)
            imported++
        }
        return imported
    }

    /**
     * Daily step totals from the first day of the window to today, in the
     * phone's zone — the same local days the pedometer and the calendar use.
     */
    private suspend fun importSteps(client: HealthConnectClient, from: Long): Int {
        val zone = ZoneId.systemDefault()
        val firstDay = Instant.ofEpochMilli(from).atZone(zone).toLocalDate()
        val today = LocalDate.now(zone)
        val groups = client.aggregateGroupByPeriod(
            AggregateGroupByPeriodRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(
                    firstDay.atStartOfDay(), today.plusDays(1).atStartOfDay(),
                ),
                timeRangeSlicer = Period.ofDays(1),
            )
        )
        // A day the user set by hand is theirs: Health Connect's total for it
        // is the very figure the correction replaced, and writing it back
        // (the vault keeps the larger) would quietly undo the correction.
        val corrected = runCatching { steps.correctedDays() }.getOrDefault(emptySet())
        val days = groups.mapNotNull { group ->
            val count = group.result[StepsRecord.COUNT_TOTAL]?.takeIf { it > 0L }
                ?: return@mapNotNull null
            group.startTime.toLocalDate() to count
        }.toMap().filterKeys { it !in corrected }
        if (days.isNotEmpty()) sport.recordStepDays(days)
        return days.size
    }

    private fun client(): HealthConnectClient =
        HealthConnectClient.getOrCreate(context, PROVIDER_PACKAGE)

    companion object {
        private const val TAG = "HealthConnect"

        /** The Health Connect app on Android 13 and older; the system module from 14. */
        const val PROVIDER_PACKAGE = "com.google.android.apps.healthdata"

        val READ_EXERCISE: String = HealthPermission.getReadPermission(ExerciseSessionRecord::class)
        val READ_STEPS: String = HealthPermission.getReadPermission(StepsRecord::class)
        val READ_HEART_RATE: String = HealthPermission.getReadPermission(HeartRateRecord::class)
        val READ_DISTANCE: String = HealthPermission.getReadPermission(DistanceRecord::class)

        /**
         * Everything this app asks Health Connect for. Read-only, four record
         * types, matching the four `<uses-permission>` in the manifest — a
         * permission requested here and not declared there is silently refused.
         */
        val PERMISSIONS: Set<String> = setOf(READ_EXERCISE, READ_STEPS, READ_HEART_RATE, READ_DISTANCE)

        /**
         * The automatic triggers (unlock, the Sport screen) skip a sync this
         * soon after the last one: they fire often, and Health Connect rate
         * limits its readers.
         */
        private const val MIN_INTERVAL_MS = 10L * 60 * 1000

        /** A cap on one window's sessions, far above anyone's month. */
        private const val MAX_SESSIONS = 2_000

    }
}
