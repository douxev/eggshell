import Foundation
import TransitionCore

// Pairs the doses a schedule *planned* with the doses actually *logged*.
//
// Straight port of the Android `data/PlannedDoses.kt`, so the two phones can
// never disagree on what "96 % · +34 min · 13 oubliées" means. Every punctuality
// figure of Médics reads from here.
//
// Two things it deliberately does not do:
//
//  1. **It infers a planned time, it never stores one.** An intake recorded
//     before punctuality existed still answered a known rhythm, so the
//     occurrence is recomputed from the schedule rather than lost. Nothing is
//     written back to the vault: improving the matching rule improves the whole
//     history, and a persisted guess could not be revised.
//  2. **It never invents an expectation.** An intake matched to an occurrence
//     projected outside the counted grid lands in `PlannedWindow.offGrid`: it
//     contributes its offset but not a planned dose, so back-dating can never
//     manufacture a missed one. What no schedule explains at all stays in
//     `withoutPlannedTime` and is disclosed rather than averaged in.

/// One occurrence a schedule expected, and the intake that answered it.
struct PlannedOccurrence {
    let scheduleId: Int64
    let medicationId: Int64
    let plannedAtMs: Int64
    /// The intake that matched, or nil when the dose was never logged.
    let event: DoseEvent?

    /// Offset from the prescribed time, in minutes. Nil when missed.
    var deltaMin: Int? {
        guard let event else { return nil }
        return Int((event.takenAtMs - plannedAtMs) / 60_000)
    }
}

struct PlannedWindow {
    let fromMs: Int64
    let toMs: Int64
    /// The grid of doses the schedules expected inside the window. This is what
    /// "prévues", "oubliées" and observance are counted from.
    let occurrences: [PlannedOccurrence]
    /// Intakes that answer no *expected* occurrence but still sit on their
    /// schedule's rhythm — typically doses logged before the schedule row
    /// itself was created. They carry a real offset, so they belong in the
    /// punctuality figures; they must not add a phantom expectation, so they
    /// are counted apart.
    let offGrid: [PlannedOccurrence]
    /// Intakes no schedule can explain: ad-hoc doses, and doses whose schedule
    /// has since been deleted. They count towards "notées", carry no offset.
    let withoutPlannedTime: [DoseEvent]

    /// Points for the punctuality chart, ordered in time.
    var points: [DosePoint] {
        (occurrences + offGrid)
            .map { DosePoint(atMs: $0.event?.takenAtMs ?? $0.plannedAtMs, deltaMin: $0.deltaMin) }
            .sorted { $0.atMs < $1.atMs }
    }

    /// Adherence counts the **grid** and only the grid: an off-grid intake has
    /// no expectation to answer, so letting it into the numerator would print
    /// « 100 % » next to a column of missed doses, and could push « notées »
    /// past « prévues ». The delay figures read every paired intake, on-grid or
    /// not — recovering their offset is the whole point of the second pass.
    var stats: PunctualityStats {
        let logged = occurrences.filter { $0.event != nil }.count
        let deltas = (occurrences + offGrid).compactMap(\.deltaMin)
        let adherence: Int
        if occurrences.isEmpty {
            adherence = 0
        } else {
            let raw = Int(((Double(logged) / Double(occurrences.count)) * 100).rounded())
            adherence = min(100, max(0, raw))
        }
        let mean = deltas.isEmpty
            ? 0
            : Int((Double(deltas.reduce(0, +)) / Double(deltas.count)).rounded())
        return PunctualityStats(
            plannedCount: occurrences.count,
            loggedCount: logged,
            missedCount: occurrences.count - logged,
            adherencePercent: adherence,
            meanDelayMin: mean)
    }

    /// How many intakes carry no offset at all — disclose, never hide.
    var unexplainedCount: Int { withoutPlannedTime.count }
}

/// One eligible (slot, intake) pairing, before the global assignment picks
/// which pairings survive.
private struct MatchCandidate {
    let slot: Int
    let event: Int
    let distance: Int64
    let exact: Bool
}

enum PlannedDoses {
    /// A month of hourly doses is ~720; a generous guard against a degenerate
    /// schedule turning the replay into a spin.
    static let maxReplaySteps = 6000

    /// Replays every **active** schedule of `medicationId` (or of every
    /// treatment when nil) across the window and matches the logged intakes
    /// to it.
    static func window(
        session: VaultService,
        fromMs: Int64,
        toMs: Int64,
        medicationId: Int64? = nil,
        calendar: Calendar = .current
    ) async -> PlannedWindow {
        let meds = ((try? await session.listMedications(includeArchived: true)) ?? [])
            .filter { medicationId == nil || $0.id == medicationId }

        var allSchedules: [DoseSchedule] = []
        for med in meds {
            let own = (try? await session.listSchedulesForMedication(med.id, includeInactive: true)) ?? []
            allSchedules.append(contentsOf: own)
        }

        let events = ((try? await session.listDoseEventsBetween(fromMs: fromMs, toMs: toMs)) ?? [])
            .filter { medicationId == nil || $0.medicationId == medicationId }
            // A skipped dose is a declared "I did not take it" — it must not be
            // counted as an intake that answered its occurrence (D2).
            .filter { $0.status != "skipped" }

        // Only an **active** schedule owes doses. Switching a reminder off is how
        // a user stops a treatment: replaying it would manufacture one
        // « oubliée » per cadence, for ever, in Médics and in the doctor PDF.
        // Inactive ones stay in `allSchedules` because their past intakes still
        // deserve an offset (see the off-grid pass below).
        var slots: [(schedule: DoseSchedule, plannedAt: Int64)] = []
        for schedule in allSchedules where schedule.active {
            for plannedAt in plannedOccurrences(schedule, fromMs: fromMs, toMs: toMs, calendar: calendar) {
                slots.append((schedule: schedule, plannedAt: plannedAt))
            }
        }

        // Matching is decided **globally**, closest pair first, not slot by slot
        // in schedule order. Walking the slots in order lets the 08:00 occurrence
        // of a twice-daily treatment claim the evening intake — it is within half
        // a cadence of both — and then the 20:00 slot reads as forgotten while
        // the morning one reads twelve hours late. Both figures wrong, from one
        // greedy step taken in the wrong order.
        //
        // An intake that names its schedule may only answer that schedule; an
        // untagged one (everything logged before punctuality existed) may answer
        // any slot of its own treatment, and the nearest wins.
        func eligible(_ event: DoseEvent, _ schedule: DoseSchedule) -> Bool {
            event.medicationId == schedule.medicationId
                && (event.scheduleId == nil || event.scheduleId == schedule.id)
        }

        var candidates: [MatchCandidate] = []
        for (slotIndex, slot) in slots.enumerated() {
            let tolerance = cadenceMs(slot.schedule) / 2
            for (eventIndex, event) in events.enumerated() {
                guard eligible(event, slot.schedule) else { continue }
                var exact = false
                if let declared = event.scheduledAtMs, declared == slot.plannedAt { exact = true }
                let gap = distance(event, to: slot.plannedAt)
                if !exact && gap > tolerance { continue }
                candidates.append(MatchCandidate(
                    slot: slotIndex, event: eventIndex, distance: gap, exact: exact))
            }
        }
        // An event that declares its planned time is not a guess — it wins over
        // any proximity match, whatever the distance. The two index tie-breaks
        // are not cosmetic: `sort` is not stable, and two pairs at the same
        // distance must resolve the same way here as they do on the other
        // platform, or the same vault prints two different reports.
        candidates.sort { a, b in
            if a.exact != b.exact { return a.exact }
            if a.distance != b.distance { return a.distance < b.distance }
            if a.slot != b.slot { return a.slot < b.slot }
            return a.event < b.event
        }

        var takenSlot = Set<Int>()
        var takenEvent = Set<Int>()
        var matchBySlot: [Int: DoseEvent] = [:]
        for candidate in candidates {
            if takenSlot.contains(candidate.slot) || takenEvent.contains(candidate.event) { continue }
            takenSlot.insert(candidate.slot)
            takenEvent.insert(candidate.event)
            matchBySlot[candidate.slot] = events[candidate.event]
        }

        var occurrences: [PlannedOccurrence] = []
        for (slotIndex, slot) in slots.enumerated() {
            occurrences.append(PlannedOccurrence(
                scheduleId: slot.schedule.id,
                medicationId: slot.schedule.medicationId,
                plannedAtMs: slot.plannedAt,
                event: matchBySlot[slotIndex]))
        }
        var unmatched = events.enumerated()
            .filter { !takenEvent.contains($0.offset) }
            .map { $0.element }

        // Second pass — recover an offset for intakes the grid could not claim.
        // The grid deliberately starts at each schedule's creation, so a user
        // who logged doses for months before adding the schedule would get no
        // punctuality at all for that stretch. The rhythm still projects
        // backwards, so measure against the nearest projected occurrence: an
        // offset without an expectation, which can never manufacture a missed
        // dose.
        let schedulesById = Dictionary(allSchedules.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        var offGrid: [PlannedOccurrence] = []
        for event in unmatched {
            let schedule: DoseSchedule?
            if let id = event.scheduleId, let own = schedulesById[id] {
                schedule = own
            } else {
                let owned = allSchedules.filter { $0.medicationId == event.medicationId }
                schedule = owned.count == 1 ? owned[0] : nil
            }
            guard let schedule,
                  let projected = DueOccurrence.nearest(schedule, atMs: event.takenAtMs, calendar: calendar),
                  abs(event.takenAtMs - projected) <= cadenceMs(schedule) / 2
            else { continue }
            offGrid.append(PlannedOccurrence(
                scheduleId: schedule.id,
                medicationId: event.medicationId,
                plannedAtMs: projected,
                event: event))
        }
        let claimed = Set(offGrid.compactMap { $0.event?.id })
        unmatched.removeAll { claimed.contains($0.id) }

        return PlannedWindow(
            fromMs: fromMs,
            toMs: toMs,
            occurrences: occurrences.sorted { $0.plannedAtMs < $1.plannedAtMs },
            offGrid: offGrid.sorted { $0.plannedAtMs < $1.plannedAtMs },
            withoutPlannedTime: unmatched.sorted { $0.takenAtMs < $1.takenAtMs })
    }

    /// Every time `schedule` expected a dose inside `[from, to)`. A schedule
    /// only counts from its creation, so a past window is never charged for a
    /// reminder that did not exist yet.
    static func plannedOccurrences(
        _ schedule: DoseSchedule,
        fromMs: Int64,
        toMs: Int64,
        calendar: Calendar = .current
    ) -> [Int64] {
        let effFrom = max(fromMs, schedule.createdAtMs)
        if effFrom >= toMs { return [] }

        // The N-day replay only steps FORWARD from the anchor, so the anchor has
        // to sit at or before the window — `nextDueAtMs` is normally in the
        // future, and every past occurrence would be skipped.
        let anchor: Int64
        if schedule.kind == "days_interval" {
            anchor = daysIntervalAnchor(
                atOrBefore: effFrom,
                nextDueAtMs: schedule.nextDueAtMs,
                intervalDays: Int(schedule.intervalDays ?? 1),
                calendar: calendar)
        } else {
            anchor = schedule.nextDueAtMs
        }

        // "interval" simply adds the cadence to the cursor, so seeding the
        // replay at the window edge would peg the grid to an arbitrary phase —
        // a 12 h schedule due at 08:00 would replay at 06:13 and every dose
        // would read as late. Step the live next-due back by whole intervals
        // instead. "daily" recomputes from the wall clock and needs no seeding.
        var cursor: Int64
        if schedule.kind == "interval" {
            cursor = intervalAnchor(
                before: effFrom,
                nextDueAtMs: schedule.nextDueAtMs,
                intervalMs: Int64(schedule.intervalMinutes ?? 0) * 60_000)
        } else {
            cursor = effFrom - 1
        }

        var out: [Int64] = []
        var steps = 0
        while steps < maxReplaySteps {
            steps += 1
            guard let next = nextDueAfter(schedule, afterMs: cursor, currentDueMs: anchor, calendar: calendar)
            else { break }
            if next <= cursor { break }          // no forward progress — bail
            if next >= toMs { break }
            if next >= effFrom { out.append(next) }
            cursor = next
        }
        return out
    }

    /// Nominal gap between two occurrences, used as the matching tolerance.
    static func cadenceMs(_ schedule: DoseSchedule) -> Int64 {
        let raw: Int64
        switch schedule.kind {
        case "interval":      raw = Int64(schedule.intervalMinutes ?? 24 * 60) * 60_000
        case "days_interval": raw = Int64(schedule.intervalDays ?? 1) * 86_400_000
        default:              raw = 86_400_000
        }
        return max(raw, 60_000)
    }

    // MARK: - Schedule math
    // `NextDueCalculator` only walks forward from "now"; the replay needs the
    // same three cadences evaluated after an arbitrary instant.

    private static func nextDueAfter(
        _ s: DoseSchedule,
        afterMs: Int64,
        currentDueMs: Int64,
        calendar: Calendar
    ) -> Int64? {
        switch s.kind {
        case "interval":
            let minutes = Int64(s.intervalMinutes ?? 0)
            guard minutes > 0 else { return nil }
            return afterMs + minutes * 60_000

        case "daily":
            guard let hour = s.dailyHour, let minute = s.dailyMinute else { return nil }
            let after = date(afterMs)
            var next = calendar.date(
                bySettingHour: Int(hour), minute: Int(minute), second: 0, of: after) ?? after
            if next <= after {
                next = calendar.date(byAdding: .day, value: 1, to: next)
                    ?? next.addingTimeInterval(86_400)
            }
            return ms(next)

        case "days_interval":
            guard let hour = s.dailyHour, let minute = s.dailyMinute,
                  let days = s.intervalDays, days > 0 else { return nil }
            let after = date(afterMs)
            let base = date(currentDueMs)
            var next = calendar.date(
                bySettingHour: Int(hour), minute: Int(minute), second: 0, of: base) ?? base
            var steps = 0
            // Stepping by whole days keeps the wall-clock HH:MM across a DST
            // change, which is what a reminder means to the person taking it.
            while next <= after && steps < maxReplaySteps {
                steps += 1
                next = calendar.date(byAdding: .day, value: Int(days), to: next)
                    ?? next.addingTimeInterval(Double(days) * 86_400)
            }
            return ms(next)

        default:
            return nil
        }
    }

    /// Step `nextDueAtMs` back by whole intervals until it sits strictly before
    /// `beforeMs`, so a forward replay reproduces the schedule's real phase.
    private static func intervalAnchor(
        before beforeMs: Int64,
        nextDueAtMs: Int64,
        intervalMs: Int64
    ) -> Int64 {
        guard intervalMs > 0 else { return beforeMs - 1 }
        if nextDueAtMs < beforeMs {
            // Already behind the window: walk forward to just before its edge.
            let steps = (beforeMs - nextDueAtMs - 1) / intervalMs
            return nextDueAtMs + steps * intervalMs
        }
        let steps = (nextDueAtMs - beforeMs) / intervalMs + 1
        return nextDueAtMs - steps * intervalMs
    }

    private static func daysIntervalAnchor(
        atOrBefore beforeMs: Int64,
        nextDueAtMs: Int64,
        intervalDays: Int,
        calendar: Calendar
    ) -> Int64 {
        let step = max(1, intervalDays)
        var at = date(nextDueAtMs)
        var steps = 0
        while ms(at) > beforeMs && steps < maxReplaySteps {
            steps += 1
            at = calendar.date(byAdding: .day, value: -step, to: at)
                ?? at.addingTimeInterval(Double(-step) * 86_400)
        }
        return ms(at)
    }

    /// How far an intake sits from a planned occurrence, for matching. A tagged
    /// event is measured against its declared due time (the replayed grid can be
    /// a few seconds off), an untagged one against the moment it was tapped.
    private static func distance(_ event: DoseEvent, to plannedAtMs: Int64) -> Int64 {
        abs((event.scheduledAtMs ?? event.takenAtMs) - plannedAtMs)
    }

    static func date(_ ms: Int64) -> Date { Date(timeIntervalSince1970: Double(ms) / 1000) }
    static func ms(_ date: Date) -> Int64 { Int64(date.timeIntervalSince1970 * 1000) }
}

/// The occurrence a *single* intake answers — what « Noter une prise » needs so
/// a hand-typed dose lands in the punctuality figures instead of leaving its
/// occurrence counted as missed. Port of the Android `DueOccurrence`.
enum DueOccurrence {
    private static let maxSteps = 4000

    /// The occurrence of `schedule` closest to `atMs`, or nil when the cadence
    /// is unusable.
    static func nearest(
        _ schedule: DoseSchedule,
        atMs: Int64,
        calendar: Calendar = .current
    ) -> Int64? {
        switch schedule.kind {
        case "interval":
            let cadence = Int64(schedule.intervalMinutes ?? 0) * 60_000
            guard cadence > 0 else { return nil }
            // Pure arithmetic: an "every N hours" schedule has no wall-clock
            // anchor to preserve across a DST change.
            let anchor = schedule.nextDueAtMs
            let steps = Int64((Double(atMs - anchor) / Double(cadence)).rounded())
            return anchor + steps * cadence

        case "daily", "days_interval":
            guard let hour = schedule.dailyHour, let minute = schedule.dailyMinute else { return nil }
            let step = schedule.kind == "days_interval" ? Int(schedule.intervalDays ?? 0) : 1
            guard step > 0 else { return nil }
            let base = PlannedDoses.date(schedule.nextDueAtMs)
            var at = calendar.date(bySettingHour: Int(hour), minute: Int(minute), second: 0, of: base) ?? base
            var steps = 0
            while PlannedDoses.ms(at) > atMs && steps < maxSteps {
                steps += 1
                at = calendar.date(byAdding: .day, value: -step, to: at)
                    ?? at.addingTimeInterval(Double(-step) * 86_400)
            }
            while steps < maxSteps {
                let forward = calendar.date(byAdding: .day, value: step, to: at)
                    ?? at.addingTimeInterval(Double(step) * 86_400)
                if PlannedDoses.ms(forward) > atMs { break }
                steps += 1
                at = forward
            }
            let previous = PlannedDoses.ms(at)
            let nextDate = calendar.date(byAdding: .day, value: step, to: at)
                ?? at.addingTimeInterval(Double(step) * 86_400)
            let next = PlannedDoses.ms(nextDate)
            return (atMs - previous) <= (next - atMs) ? previous : next

        default:
            return nil
        }
    }

    /// Half a cadence — outside it, an intake answers no occurrence at all.
    static func toleranceMs(_ schedule: DoseSchedule) -> Int64 {
        PlannedDoses.cadenceMs(schedule) / 2
    }

    /// The `(scheduleId, scheduledAtMs)` pair an intake logged at `atMs`
    /// deserves, across every schedule of the treatment. Outside half a cadence
    /// nothing is attached — an ad-hoc dose is not late, it is unplanned (D2).
    static func linkage(
        for atMs: Int64,
        schedules: [DoseSchedule],
        calendar: Calendar = .current
    ) -> (scheduleId: Int64?, scheduledAtMs: Int64?) {
        var bestScheduleId: Int64?
        var bestPlanned: Int64?
        var bestDistance = Int64.max
        for schedule in schedules {
            guard let planned = nearest(schedule, atMs: atMs, calendar: calendar) else { continue }
            // A reminder cannot have prescribed anything before it existed.
            if planned < schedule.createdAtMs { continue }
            let gap = abs(atMs - planned)
            if gap <= toleranceMs(schedule) && gap < bestDistance {
                bestScheduleId = schedule.id
                bestPlanned = planned
                bestDistance = gap
            }
        }
        return (bestScheduleId, bestPlanned)
    }
}
