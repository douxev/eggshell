package com.douxev.eggshell.data

import com.douxev.eggshell.reminders.NextDueCalculator
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.transition.DoseSchedule

/**
 * Computes the period-over-period "résumé" shown to the user: this week/month
 * vs. the equal-length window immediately before it.
 *
 * Everything is derived on-device from the existing repositories — no network,
 * no LLM. Two honesty notes baked into the model:
 *  - There is no stored "missed dose": a dose row exists only when the user
 *    tapped Pris/Passer. So `expected` is *estimated* by replaying each active
 *    schedule's cadence across the window (clamped to when the schedule was
 *    created), and `missed = expected − taken − skipped` is an estimate, framed
 *    as such in the UI.
 *  - The current period is in progress, so we compare it against the SAME
 *    elapsed duration ending at the previous period boundary, never a full
 *    previous period — otherwise counts would look artificially low.
 */
@Singleton
class SummaryRepository @Inject constructor(
    private val journal: JournalRepository,
    private val medications: MedicationRepository,
    private val metrics: MetricsRepository,
    private val schedules: ScheduleRepository,
    private val features: FeaturesPrefs,
) {

    data class CustomMetricAvg(
        val metricId: Long,
        val label: String,
        val current: Double?,
        val previous: Double?,
    )

    data class PeriodSummary(
        val period: SummaryPeriod,
        val moodCurrent: Double?,
        val moodPrevious: Double?,
        val journalCountCurrent: Int,
        val journalCountPrevious: Int,
        val takenCurrent: Int,
        val takenPrevious: Int,
        val skippedCurrent: Int,
        val skippedPrevious: Int,
        val expectedCurrent: Int,
        val expectedPrevious: Int,
        val missedCurrent: Int,
        val missedPrevious: Int,
        val customMetrics: List<CustomMetricAvg>,
        val hasMedications: Boolean,
        val hasJournal: Boolean,
    ) {
        /** Enough signal to render a meaningful comparison. */
        val hasData: Boolean
            get() = journalCountCurrent > 0 || journalCountPrevious > 0 ||
                takenCurrent > 0 || takenPrevious > 0 ||
                expectedCurrent > 0 || expectedPrevious > 0
    }

    private data class WindowStats(
        val mood: Double?,
        val journalCount: Int,
        val taken: Int,
        val skipped: Int,
        val expected: Int,
        val customAverages: Map<Long, Double>,
    )

    suspend fun compute(period: SummaryPeriod): PeriodSummary = withContext(Dispatchers.IO) {
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val periodStart = startOfPeriod(now, period, zone)
        val elapsed = (now - periodStart).coerceAtLeast(0L)
        val prevStart = periodStart - elapsed

        val activeSchedules = runCatching { schedules.listAllActive() }.getOrDefault(emptyList())
        val customDefs = runCatching { metrics.definitions(MetricsRepository.DOMAIN_JOURNAL) }
            .getOrDefault(emptyList())
            .filter { !it.builtin }

        // Fetch the data spanning both windows ONCE, paging the journal until we
        // reach prevStart so a heavy logger (>page newest entries) doesn't lose
        // the older previous window to the page cap.
        val journalAll = journalEntriesSince(prevStart)
        val doseAll = runCatching { medications.listDoseEventsBetween(prevStart, now) }
            .getOrDefault(emptyList())

        val current = windowStats(periodStart, now, journalAll, doseAll, activeSchedules, customDefs, zone)
        val previous = windowStats(prevStart, periodStart, journalAll, doseAll, activeSchedules, customDefs, zone)

        val customMetrics = customDefs.map { def ->
            CustomMetricAvg(
                metricId = def.id,
                label = def.label,
                current = current.customAverages[def.id],
                previous = previous.customAverages[def.id],
            )
        }.filter { it.current != null || it.previous != null }

        PeriodSummary(
            period = period,
            moodCurrent = current.mood,
            moodPrevious = previous.mood,
            journalCountCurrent = current.journalCount,
            journalCountPrevious = previous.journalCount,
            takenCurrent = current.taken,
            takenPrevious = previous.taken,
            skippedCurrent = current.skipped,
            skippedPrevious = previous.skipped,
            expectedCurrent = current.expected,
            expectedPrevious = previous.expected,
            missedCurrent = (current.expected - current.taken - current.skipped).coerceAtLeast(0),
            missedPrevious = (previous.expected - previous.taken - previous.skipped).coerceAtLeast(0),
            customMetrics = customMetrics,
            hasMedications = features.medications.value,
            hasJournal = features.journal.value,
        )
    }

    private suspend fun windowStats(
        from: Long,
        to: Long,
        journalAll: List<uniffi.transition.JournalEntry>,
        doseAll: List<uniffi.transition.DoseEvent>,
        activeSchedules: List<DoseSchedule>,
        customDefs: List<uniffi.transition.MetricDefinition>,
        zone: ZoneId,
    ): WindowStats {
        val entries = journalAll.filter { it.atMs in from until to }
        val moods = entries.mapNotNull { it.mood?.toInt() }
        val mood = if (moods.isEmpty()) null else moods.average()

        // Custom slider averages — per-entry value lookups (only the window's
        // entries, typically a handful) accumulated per metric id.
        val customSums = HashMap<Long, Pair<Double, Int>>()
        if (customDefs.isNotEmpty()) {
            entries.forEach { e ->
                val values = runCatching { metrics.values(MetricsRepository.DOMAIN_JOURNAL, e.id) }
                    .getOrDefault(emptyList())
                values.forEach { v ->
                    val (sum, count) = customSums[v.metricId] ?: (0.0 to 0)
                    customSums[v.metricId] = (sum + v.value.toInt()) to (count + 1)
                }
            }
        }
        val customAverages = customSums.mapValues { (_, sc) -> sc.first / sc.second }

        val doses = doseAll.filter { it.takenAtMs in from until to }
        val taken = doses.count { it.status == "taken" }
        val skipped = doses.count { it.status == "skipped" }
        val expected = expectedDosesInWindow(activeSchedules, from, to, zone)

        return WindowStats(mood, entries.size, taken, skipped, expected, customAverages)
    }

    /**
     * Estimate how many scheduled doses *should* have occurred in [from, to] by
     * replaying each schedule's cadence. A schedule only counts from when it was
     * created, so a previous window doesn't get charged for a schedule that
     * didn't exist yet.
     */
    private fun expectedDosesInWindow(
        schedules: List<DoseSchedule>,
        from: Long,
        to: Long,
        zone: ZoneId,
    ): Int {
        var total = 0
        schedules.forEach { s ->
            val effFrom = maxOf(from, s.createdAtMs)
            if (effFrom >= to) return@forEach
            // NextDueCalculator only steps FORWARD from currentDueMs for
            // "days_interval", so the phase anchor must sit at or before the
            // window or every past/current occurrence is skipped (next_due is
            // typically in the future). Roll the live next-due back by whole
            // N-day steps to just before the window. interval/daily ignore
            // currentDueMs and compute from afterMs, so the anchor is harmless.
            val anchor = if (s.kind == "days_interval") {
                daysIntervalAnchorAtOrBefore(s.nextDueAtMs, s.intervalDays?.toInt() ?: 1, effFrom, zone)
            } else {
                s.nextDueAtMs
            }
            var cursor = effFrom - 1
            var guard = 0
            while (guard++ < MAX_REPLAY_STEPS) {
                val next = runCatching {
                    NextDueCalculator.nextDueAfter(
                        kind = s.kind,
                        intervalMinutes = s.intervalMinutes?.toInt(),
                        dailyHour = s.dailyHour?.toInt(),
                        dailyMinute = s.dailyMinute?.toInt(),
                        afterMs = cursor,
                        intervalDays = s.intervalDays?.toInt(),
                        currentDueMs = anchor,
                    )
                }.getOrNull() ?: break
                if (next <= cursor) break       // no forward progress — bail
                if (next >= to) break
                if (next >= effFrom) total++
                cursor = next
            }
        }
        return total
    }

    /** Step a days_interval occurrence back by whole N-day jumps (DST-safe via
     *  java.time) until it sits at or before [beforeMs], to seed a forward replay. */
    private fun daysIntervalAnchorAtOrBefore(
        nextDueAtMs: Long,
        intervalDays: Int,
        beforeMs: Long,
        zone: ZoneId,
    ): Long {
        val step = intervalDays.coerceAtLeast(1).toLong()
        var z = Instant.ofEpochMilli(nextDueAtMs).atZone(zone)
        var guard = 0
        while (z.toInstant().toEpochMilli() > beforeMs && guard++ < MAX_REPLAY_STEPS) {
            z = z.minusDays(step)
        }
        return z.toInstant().toEpochMilli()
    }

    /** Page the journal newest-first until we've covered everything since
     *  [sinceMs], so a heavy logger doesn't lose the older comparison window to
     *  a single page cap. */
    private suspend fun journalEntriesSince(sinceMs: Long): List<uniffi.transition.JournalEntry> {
        val out = ArrayList<uniffi.transition.JournalEntry>()
        var offset = 0L
        val page = 500L
        var guard = 0
        while (guard++ < 200) {
            val batch = runCatching { journal.list(offset, page) }.getOrDefault(emptyList())
            if (batch.isEmpty()) break
            out += batch
            // Entries are at_ms DESC; once the oldest in the batch precedes the
            // window, we've fetched everything we need.
            if (batch.size < page || batch.last().atMs < sinceMs) break
            offset += page
        }
        return out
    }

    private fun startOfPeriod(now: Long, period: SummaryPeriod, zone: ZoneId): Long {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val start = when (period) {
            SummaryPeriod.WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            SummaryPeriod.MONTH -> today.withDayOfMonth(1)
        }
        return start.atStartOfDay(zone).toInstant().toEpochMilli()
    }

    companion object {
        // Caps the replay loop; a month of hourly doses is ~720, so this is
        // a generous safety net against a degenerate schedule.
        private const val MAX_REPLAY_STEPS = 6000
    }
}
