package com.douxev.eggshell.data

import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.douxev.eggshell.punctuality.DosePoint
import com.douxev.eggshell.punctuality.PunctualityStats
import com.douxev.eggshell.reminders.NextDueCalculator
import uniffi.transition.DoseEvent
import uniffi.transition.DoseSchedule

/**
 * Pairs the doses a schedule *planned* with the doses actually *logged*, over
 * any range. This is the single source of truth for the punctuality figures of
 * the refonte — the Médics « Régularité » card, the home dose ring and §3 of
 * the doctor PDF all read from here, so the three can never disagree.
 *
 * Two things it deliberately does not do:
 *
 * 1. **It infers a planned time, it never stores one.** `DoseEvent.scheduled_at_ms`
 *    only started being written by this release, so historical intakes have
 *    none — but the cadence they answered is still known, so the occurrence is
 *    recomputed from the schedule rather than lost. The inference is never
 *    written back to the vault: improving the matching rule improves the whole
 *    history, and a persisted guess could not be revised.
 * 2. **It never invents an expectation.** An intake matched to a projected
 *    occurrence outside the counted grid lands in [Window.offGrid]: it
 *    contributes its offset but not a planned dose, so back-dating can never
 *    manufacture a missed one. What no schedule can explain at all stays in
 *    [Window.withoutPlannedTime] and is disclosed rather than averaged in.
 */
@Singleton
class PlannedDoses @Inject constructor(
    private val schedules: ScheduleRepository,
    private val medications: MedicationRepository,
) {

    /** One occurrence a schedule expected, and the intake that answered it. */
    data class Occurrence(
        val scheduleId: Long,
        val medicationId: Long,
        val plannedAtMs: Long,
        /** The intake that matched, or null when the dose was never logged. */
        val event: DoseEvent?,
    ) {
        /** Offset from the prescribed time, in minutes. Null when missed. */
        val deltaMin: Int?
            get() = event?.let { ((it.takenAtMs - plannedAtMs) / 60_000L).toInt() }
    }

    data class Window(
        val fromMs: Long,
        val toMs: Long,
        /**
         * The grid of doses the schedules expected inside the window. This is
         * what "prévues", "oubliées" and observance are counted from.
         */
        val occurrences: List<Occurrence>,
        /**
         * Intakes that answer no *expected* occurrence but still sit on their
         * schedule's rhythm — typically doses logged before the schedule row
         * itself was created. They carry a real offset, so they belong in the
         * punctuality figures; they must not add a phantom expectation, so they
         * are counted apart.
         */
        val offGrid: List<Occurrence>,
        /**
         * Intakes no schedule can explain: ad-hoc doses, and doses whose
         * schedule has since been deleted. They count towards "notées" and
         * carry no offset.
         */
        val withoutPlannedTime: List<DoseEvent>,
    ) {
        /** Points for the punctuality chart, ordered in time. */
        val points: List<DosePoint>
            get() = (occurrences + offGrid)
                .map { DosePoint(atMs = it.event?.takenAtMs ?: it.plannedAtMs, deltaMin = it.deltaMin) }
                .sortedBy { it.atMs }

        /**
         * Adherence counts the **grid** and only the grid: an off-grid intake
         * has no expectation to answer, so letting it into the numerator would
         * print « 100 % » next to a column of missed doses, and could push the
         * ratio past 100. The delay figures read every paired intake, on-grid
         * or not — that is the whole point of recovering them.
         */
        val stats: PunctualityStats
            get() {
                val logged = occurrences.count { it.event != null }
                val deltas = (occurrences + offGrid).mapNotNull { it.deltaMin }
                return PunctualityStats(
                    plannedCount = occurrences.size,
                    loggedCount = logged,
                    missedCount = occurrences.size - logged,
                    adherencePercent = if (occurrences.isEmpty()) 0 else {
                        ((logged.toDouble() / occurrences.size) * 100).roundToInt().coerceIn(0, 100)
                    },
                    meanDelayMin = if (deltas.isEmpty()) 0 else deltas.average().roundToInt(),
                )
            }

        /** How many intakes carry no offset at all — disclose, never hide. */
        val unexplainedCount: Int get() = withoutPlannedTime.size
    }

    /**
     * Replays every schedule of [medicationId] (or of every medication when
     * null) across the window and matches the logged intakes to it.
     */
    suspend fun window(
        fromMs: Long,
        toMs: Long,
        medicationId: Long? = null,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Window = withContext(Dispatchers.IO) {
        val meds = runCatching { medications.list(includeArchived = true) }
            .getOrDefault(emptyList())
            .filter { medicationId == null || it.id == medicationId }
        val allSchedules = meds.flatMap { med ->
            runCatching { schedules.listForMedication(med.id, includeInactive = true) }
                .getOrDefault(emptyList())
        }
        val events = runCatching { medications.listDoseEventsBetween(fromMs, toMs) }
            .getOrDefault(emptyList())
            .filter { medicationId == null || it.medicationId == medicationId }
            // A skipped dose is a declared "I did not take it" — it must not be
            // counted as an intake that answered its occurrence.
            .filter { it.status != "skipped" }

        // Only an **active** schedule owes doses. Switching a reminder off is
        // how a user stops a treatment: replaying it would manufacture one
        // "oubliée" per cadence, for ever, in Médics and in the doctor PDF.
        // Inactive ones stay in `allSchedules` because their past intakes still
        // deserve an offset (see the off-grid pass below).
        val slots = allSchedules.filter { it.active }.flatMap { schedule ->
            plannedOccurrences(schedule, fromMs, toMs, zone).map { schedule to it }
        }

        // Matching is decided **globally**, closest pair first, not slot by slot
        // in schedule order. Walking the slots in order lets the 08:00 occurrence
        // of a twice-daily treatment claim the evening intake — it is within half
        // a cadence of both — and then the 20:00 slot reads as forgotten while
        // the morning one reads twelve hours late. Both figures wrong, from one
        // greedy step taken in the wrong order.
        //
        // An intake that names its schedule may only answer that schedule; an
        // untagged one (everything logged before this release) may answer any
        // slot of its own medication, and the nearest wins.
        fun eligible(event: DoseEvent, schedule: DoseSchedule): Boolean =
            event.medicationId == schedule.medicationId &&
                (event.scheduleId == null || event.scheduleId == schedule.id)

        data class Pair2(val slot: Int, val event: Int, val distance: Long, val exact: Boolean)
        val candidates = ArrayList<Pair2>()
        slots.forEachIndexed { slotIndex, (schedule, plannedAt) ->
            val tolerance = cadenceMs(schedule, zone) / 2
            events.forEachIndexed { eventIndex, event ->
                if (!eligible(event, schedule)) return@forEachIndexed
                val exact = event.scheduledAtMs == plannedAt
                val distance = distanceTo(event, plannedAt)
                if (!exact && distance > tolerance) return@forEachIndexed
                candidates.add(Pair2(slotIndex, eventIndex, distance, exact))
            }
        }
        // An event that declares its planned time is not a guess — it wins over
        // any proximity match, whatever the distance.
        candidates.sortWith(compareByDescending<Pair2> { it.exact }.thenBy { it.distance })

        val takenSlot = HashSet<Int>()
        val takenEvent = HashSet<Int>()
        val matchBySlot = HashMap<Int, DoseEvent>()
        candidates.forEach { c ->
            if (c.slot in takenSlot || c.event in takenEvent) return@forEach
            takenSlot.add(c.slot)
            takenEvent.add(c.event)
            matchBySlot[c.slot] = events[c.event]
        }

        val occurrences = ArrayList<Occurrence>(slots.size)
        slots.forEachIndexed { slotIndex, (schedule, plannedAt) ->
            occurrences.add(
                Occurrence(
                    scheduleId = schedule.id,
                    medicationId = schedule.medicationId,
                    plannedAtMs = plannedAt,
                    event = matchBySlot[slotIndex],
                )
            )
        }
        val unmatched = events.filterIndexed { index, _ -> index !in takenEvent }.toMutableList()

        // Second pass — recover an offset for intakes the grid could not claim.
        // The grid deliberately starts at each schedule's creation, so a user
        // who logged doses for months before adding the schedule would get no
        // punctuality at all for that stretch. The rhythm still projects
        // backwards, so measure against the nearest projected occurrence. This
        // yields an offset without inventing an expectation: these do not count
        // towards "prévues", so they can never manufacture a missed dose.
        val schedulesById = allSchedules.associateBy { it.id }
        val offGrid = ArrayList<Occurrence>()
        unmatched.toList().forEach { event ->
            val schedule = event.scheduleId?.let { schedulesById[it] }
                ?: allSchedules.singleOrNull { it.medicationId == event.medicationId }
                ?: return@forEach
            val projected = nearestOccurrence(schedule, event.takenAtMs, zone) ?: return@forEach
            if (abs(event.takenAtMs - projected) > cadenceMs(schedule, zone) / 2) return@forEach
            unmatched.remove(event)
            offGrid.add(
                Occurrence(
                    scheduleId = schedule.id,
                    medicationId = event.medicationId,
                    plannedAtMs = projected,
                    event = event,
                )
            )
        }

        Window(
            fromMs = fromMs,
            toMs = toMs,
            occurrences = occurrences.sortedBy { it.plannedAtMs },
            offGrid = offGrid.sortedBy { it.plannedAtMs },
            withoutPlannedTime = unmatched.sortedBy { it.takenAtMs },
        )
    }

    /**
     * The occurrence of [schedule] closest to [atMs], projected freely in both
     * directions — unlike [plannedOccurrences] this ignores the schedule's
     * creation date, because here we are dating an intake that certainly
     * happened, not deciding whether a dose was owed.
     *
     * Returns null when the cadence is unusable.
     */
    private fun nearestOccurrence(schedule: DoseSchedule, atMs: Long, zone: ZoneId): Long? {
        val candidates: List<Long> = when (schedule.kind) {
            "interval" -> {
                val step = (schedule.intervalMinutes?.toLong() ?: 0L) * 60_000L
                if (step <= 0L) return null
                val before = intervalAnchorBefore(schedule.nextDueAtMs, step, atMs)
                listOf(before, before + step)
            }
            "days_interval" -> {
                val step = (schedule.intervalDays?.toLong() ?: 1L).coerceAtLeast(1L)
                val before = daysIntervalAnchorAtOrBefore(
                    schedule.nextDueAtMs,
                    step.toInt(),
                    atMs,
                    zone,
                )
                listOf(
                    before,
                    Instant.ofEpochMilli(before).atZone(zone).plusDays(step).toInstant().toEpochMilli(),
                )
            }
            else -> {
                // Daily: the same wall-clock time, on the day of the intake and
                // its neighbours — a dose taken at 00:20 answers yesterday's
                // 23:00 slot, not today's.
                val hour = schedule.dailyHour?.toInt() ?: return null
                val minute = schedule.dailyMinute?.toInt() ?: 0
                val day = Instant.ofEpochMilli(atMs).atZone(zone).toLocalDate()
                (-1L..1L).map { offset ->
                    day.plusDays(offset)
                        .atTime(hour, minute)
                        .atZone(zone)
                        .toInstant()
                        .toEpochMilli()
                }
            }
        }
        return candidates.minByOrNull { abs(it - atMs) }
    }

    /**
     * Every time [schedule] expected a dose inside `[from, to)`.
     *
     * Generalises the window replay of [SummaryRepository]: a schedule only
     * counts from its creation, so a past window is never charged for a
     * schedule that did not exist yet.
     */
    fun plannedOccurrences(
        schedule: DoseSchedule,
        fromMs: Long,
        toMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<Long> {
        val effFrom = maxOf(fromMs, schedule.createdAtMs)
        if (effFrom >= toMs) return emptyList()
        // NextDueCalculator only steps FORWARD from currentDueMs for
        // "days_interval", so the phase anchor must sit at or before the window
        // or every past occurrence is skipped — next_due is normally in the
        // future.
        val anchor = if (schedule.kind == "days_interval") {
            daysIntervalAnchorAtOrBefore(
                schedule.nextDueAtMs,
                schedule.intervalDays?.toInt() ?: 1,
                effFrom,
                zone,
            )
        } else {
            schedule.nextDueAtMs
        }
        val out = ArrayList<Long>()
        // "interval" ignores the anchor and simply adds the cadence to
        // `afterMs`, so seeding the replay at the window edge would peg the
        // whole grid to an arbitrary phase — a 12 h schedule due at 08:00 would
        // be replayed at 06:13 and every dose would read as late. Step the live
        // next-due back by whole intervals instead, so the replayed grid keeps
        // the schedule's real phase. "daily" recomputes from the wall clock and
        // needs no seeding.
        var cursor = if (schedule.kind == "interval") {
            intervalAnchorBefore(
                schedule.nextDueAtMs,
                (schedule.intervalMinutes?.toLong() ?: 0L) * 60_000L,
                effFrom,
            )
        } else {
            effFrom - 1
        }
        var guard = 0
        while (guard++ < MAX_REPLAY_STEPS) {
            val next = runCatching {
                NextDueCalculator.nextDueAfter(
                    kind = schedule.kind,
                    intervalMinutes = schedule.intervalMinutes?.toInt(),
                    dailyHour = schedule.dailyHour?.toInt(),
                    dailyMinute = schedule.dailyMinute?.toInt(),
                    afterMs = cursor,
                    intervalDays = schedule.intervalDays?.toInt(),
                    currentDueMs = anchor,
                )
            }.getOrNull() ?: break
            if (next <= cursor) break        // no forward progress — bail
            if (next >= toMs) break
            if (next >= effFrom) out.add(next)
            cursor = next
        }
        return out
    }

    /** How far an intake sits from a planned occurrence, for matching. */
    private fun distanceTo(event: DoseEvent, plannedAtMs: Long): Long =
        event.scheduledAtMs?.let { abs(it - plannedAtMs) } ?: abs(event.takenAtMs - plannedAtMs)

    /** Nominal gap between two occurrences, used as the matching tolerance. */
    private fun cadenceMs(schedule: DoseSchedule, zone: ZoneId): Long = when (schedule.kind) {
        "interval" -> (schedule.intervalMinutes?.toLong() ?: 24L * 60L) * 60_000L
        "days_interval" -> (schedule.intervalDays?.toLong() ?: 1L) * 86_400_000L
        else -> 86_400_000L
    }.coerceAtLeast(60_000L)

    /**
     * Step [nextDueAtMs] back by whole [intervalMs] jumps until it sits
     * strictly before [beforeMs], so a forward replay reproduces the
     * schedule's real phase. Returns `beforeMs - 1` when the cadence is
     * unusable, which degrades to the old window-edge behaviour rather than
     * looping.
     */
    private fun intervalAnchorBefore(nextDueAtMs: Long, intervalMs: Long, beforeMs: Long): Long {
        if (intervalMs <= 0L) return beforeMs - 1
        if (nextDueAtMs < beforeMs) {
            // Already behind the window: walk forward to just before its edge.
            val steps = (beforeMs - nextDueAtMs - 1) / intervalMs
            return nextDueAtMs + steps * intervalMs
        }
        val steps = (nextDueAtMs - beforeMs) / intervalMs + 1
        return nextDueAtMs - steps * intervalMs
    }

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

    private companion object {
        /** A month of hourly doses is ~720; a generous guard against a
         *  degenerate schedule. Mirrors [SummaryRepository]. */
        const val MAX_REPLAY_STEPS = 6000
    }
}
