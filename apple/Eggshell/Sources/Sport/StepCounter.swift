import CoreMotion
import Foundation
import TransitionCore

/// Daily step totals from the phone's own motion co-processor.
///
/// The iOS analogue of Android's `StepCounter`, and a simpler one: Core Motion
/// keeps seven days of history and answers "how many steps between these two
/// instants" directly, so there is no cumulative-since-boot counter to
/// baseline and no reboot rule to get subtly wrong. The app asks for each of
/// the last seven local days and hands the totals to the vault, which keeps
/// the larger of stored and incoming — so even a stale answer cannot erase a
/// day's walking.
///
/// No background delivery, no live updates: the numbers are read on every real
/// unlock (`syncAfterUnlock`) and whenever the Sport module opens. Seven days
/// of history make that enough — the previous build only read from the Sport
/// screen, so a week without opening the module lost the oldest day for good.
/// Motion access is requested by the system the first time this asks, which
/// only happens after the user turned the pedometer on.
@MainActor
final class StepCounter {

    private let pedometer = CMPedometer()

    /// Whether this device can count steps at all.
    static var isAvailable: Bool { CMPedometer.isStepCountingAvailable() }

    /// True once the user refused Motion access; the switch says so instead
    /// of sitting "on" and counting nothing.
    static var isDenied: Bool { CMPedometer.authorizationStatus() == .denied }

    /// Steps for one local day, or nil when the sensor did not answer.
    ///
    /// Nil is not zero, and callers must not treat it as one: "the sensor said
    /// nothing" and "you did not move" are different facts, and writing the
    /// second when the first is true is how a day gets flattened.
    func steps(on day: Date, calendar: Calendar = .current) async -> Int64? {
        guard Self.isAvailable else { return nil }
        let start = calendar.startOfDay(for: day)
        guard let end = calendar.date(byAdding: .day, value: 1, to: start) else { return nil }
        let clampedEnd = min(end, Date())
        guard clampedEnd > start else { return 0 }
        return await withCheckedContinuation { continuation in
            pedometer.queryPedometerData(from: start, to: clampedEnd) { data, error in
                guard error == nil, let data else {
                    continuation.resume(returning: nil)
                    return
                }
                continuation.resume(returning: data.numberOfSteps.int64Value)
            }
        }
    }

    /// Read the last [days] local days and record each total in the vault.
    ///
    /// Only ever raises a day: the core keeps the max of stored and incoming. A
    /// day the user corrected is read through its correction — see `correct`.
    func sync(into session: VaultService, days: Int = 7, calendar: Calendar = .current) async {
        guard Self.isAvailable else { return }
        let offsets = SportPrefs.stepOffsets()
        let today = calendar.startOfDay(for: Date())
        for offset in 0..<max(1, days) {
            guard let day = calendar.date(byAdding: .day, value: -offset, to: today) else { continue }
            guard let count = await steps(on: day, calendar: calendar), count > 0 else { continue }
            let key = SportFormat.dayKey(day, calendar: calendar)
            let value = max(0, count - (offsets[key] ?? 0))
            guard value > 0 else { continue }
            try? await session.recordSteps(dayKey: key, steps: value, nowMs: Time.nowMs())
        }
    }

    /// Set a day to exactly `value` — the only way to bring one down.
    ///
    /// Core Motion will keep reporting its own figure for that day for a week,
    /// and the next sync would put it straight back. So the gap between the
    /// sensor and the correction is remembered for the day, and later reads
    /// are taken through it: walking that evening still adds to the corrected
    /// figure instead of being swallowed by it.
    func correct(day: Date, to value: Int64, session: VaultService, calendar: Calendar = .current) async throws {
        let key = SportFormat.dayKey(day, calendar: calendar)
        try await session.setSteps(dayKey: key, steps: max(0, value), nowMs: Time.nowMs())
        if let sensor = await steps(on: day, calendar: calendar) {
            SportPrefs.setStepOffset(sensor - max(0, value), forDayKey: key)
        }
    }

    /// The unlock hook: pour the last week into the vault as soon as it opens,
    /// whether or not the Sport module is visited. Called on a real unlock only
    /// — the decoy never reaches it.
    static func syncAfterUnlock(session: VaultService) async {
        guard SportPrefs.pedometerIsOn() else { return }
        await StepCounter().sync(into: session)
    }
}
