import Foundation
import HealthKit
import TransitionCore

/// Reads workouts, daily steps and workout heart rate from Santé (HealthKit)
/// into the vault. Read only: nothing is ever written back to Santé.
///
/// **Where the data goes (App Review 5.1.3).** From HealthKit on this phone
/// straight into the encrypted vault on this phone, and nowhere else: no
/// iCloud, no server, no analytics, no third party, no network call of any
/// kind. Health data is not used for anything but showing the person their
/// own sessions and steps.
///
/// **Parked behind the capability.** The App Store profile fastlane match
/// installs has no HealthKit capability, so the shipping build carries no
/// HealthKit entitlement. Every entry point checks `isEnabledInBuild` (the
/// `EggshellHealthKitEnabled` Info.plist flag, false until
/// apple/HEALTHKIT-ENABLE.md has been followed), so a build without the
/// entitlement never shows or runs any of this.
///
/// **The rules are Android's** (Health Connect does the same job there), so a
/// person using both apps gets the same vault:
///
/// - sessions are written with source `"health"`;
/// - a workout is skipped when any session starts at exactly the same
///   millisecond, or a non-manual one starts within ±60 s — a manual session
///   is the user's own word and is never replaced, and a watch export or an
///   earlier Santé import of the same workout is not doubled;
/// - steps go through `recordSteps`, which keeps the larger of stored and
///   incoming, so an import can only ever raise a day;
/// - heart rate outside 20–300 bpm is dropped rather than written (the vault
///   refuses it, and a session must not fail to save over one bad sensor).
@MainActor
final class HealthKitImporter: ObservableObject {
    static let shared = HealthKitImporter()

    /// What the card in « Types d'activité et podomètre » says.
    enum Status: Equatable {
        case idle
        case syncing
        case done(sessions: Int, days: Int)
        /// Santé never asked: turning the switch off and on again asks.
        case needsAuthorization
        case failed(String)
    }

    @Published private(set) var status: Status = .idle

    /// The build carries the HealthKit entitlement. False in every build
    /// signed with the current profile — see apple/HEALTHKIT-ENABLE.md.
    static var isEnabledInBuild: Bool {
        (Bundle.main.object(forInfoDictionaryKey: "EggshellHealthKitEnabled") as? Bool) == true
    }

    /// The build allows it and the device has Santé (an iPad, for instance,
    /// may not).
    static var isAvailable: Bool { isEnabledInBuild && HKHealthStore.isHealthDataAvailable() }

    private let store = HKHealthStore()
    private var running: Task<Void, Never>?

    /// Workouts, steps, heart rate, walking/running and cycling distance.
    /// Nothing to share: the app never writes to Santé.
    private static var readTypes: Set<HKObjectType> {
        [
            HKObjectType.workoutType(),
            HKQuantityType(.stepCount),
            HKQuantityType(.heartRate),
            HKQuantityType(.distanceWalkingRunning),
            HKQuantityType(.distanceCycling),
        ]
    }

    private init() {}

    // MARK: Entry points

    /// Ask Santé for read access. Called only from the switch the user just
    /// turned on — never at unlock, never on opening a screen.
    func requestAccess() async -> Bool {
        guard Self.isAvailable else { return false }
        do {
            try await store.requestAuthorization(toShare: [], read: Self.readTypes)
            return true
        } catch {
            status = .failed(describe(error))
            return false
        }
    }

    /// The unlock hook, next to `StepCounter.syncAfterUnlock`: a real unlock
    /// only, with the vault open.
    static func syncAfterUnlock(session: VaultService) async {
        guard isAvailable, SportPrefs.healthImportIsOn() else { return }
        await shared.sync(session: session)
    }

    /// One import at a time. Unlock and the Sport screen can both ask within
    /// the same second, and two imports reading the vault before either wrote
    /// would each add the same workout; a second caller waits for the first
    /// instead.
    func sync(session: VaultService) async {
        guard Self.isAvailable else { return }
        if let running {
            await running.value
            return
        }
        let task = Task { await self.run(session: session) }
        running = task
        await task.value
        running = nil
    }

    // MARK: The import

    private func run(session: VaultService) async {
        // Never prompt from here: if Santé has not been asked yet, say so on
        // the card and wait for the user.
        if let request = try? await store.statusForAuthorizationRequest(toShare: [], read: Self.readTypes),
           request == .shouldRequest {
            status = .needsAuthorization
            return
        }
        status = .syncing
        let now = Date()
        let from = Self.windowStart(lastSync: SportPrefs.healthLastSync(), now: now)

        // Fail-soft, each half on its own: a failed workout read must not cost
        // the steps, and the reverse. The window only moves on when both
        // landed, so whatever failed is read again next time.
        var problems: [String] = []
        var sessions = 0
        var days = 0
        do { sessions = try await importWorkouts(from: from, to: now, session: session) }
        catch { problems.append(describe(error)) }
        do { days = try await importSteps(from: from, to: now, session: session) }
        catch { problems.append(describe(error)) }

        if problems.isEmpty {
            SportPrefs.setHealthLastSync(now)
            status = .done(sessions: sessions, days: days)
        } else {
            status = .failed(problems.joined(separator: " · "))
        }
    }

    /// Thirty days the first time; afterwards from two days before the last
    /// import, because Santé fills in late — a watch syncs hours after the
    /// run — and a window starting at the last import would miss it.
    static func windowStart(lastSync: Date?, now: Date, calendar: Calendar = .current) -> Date {
        let start: Date
        if let lastSync {
            start = calendar.date(byAdding: .day, value: -2, to: lastSync) ?? lastSync
        } else {
            start = calendar.date(byAdding: .day, value: -30, to: now) ?? now
        }
        return calendar.startOfDay(for: start)
    }

    // MARK: Workouts

    private func importWorkouts(from: Date, to: Date, session: VaultService) async throws -> Int {
        let predicate = HKQuery.predicateForSamples(withStart: from, end: to, options: .strictStartDate)
        let descriptor = HKSampleQueryDescriptor(
            predicates: [.workout(predicate)],
            sortDescriptors: [SortDescriptor(\.startDate)])
        let workouts = try await descriptor.result(for: store)
        guard let first = workouts.first, let last = workouts.last else { return 0 }

        let activities = await SportStore.activities(session)
        var existing = try await session.listSportSessionsBetween(
            fromMs: Self.ms(first.startDate) - Self.dedupeWindowMs,
            toMs: Self.ms(last.startDate) + Self.dedupeWindowMs + 1)

        var written = 0
        for workout in workouts {
            let startMs = Self.ms(workout.startDate)
            if Self.isDuplicate(startMs: startMs, among: existing) { continue }
            let heart = await heartRate(during: workout)
            let new = NewSportSession(
                activityId: WatchImporter.matchActivity(
                    hint: Self.hint(for: workout.workoutActivityType), activities: activities)?.id,
                startedMs: startMs,
                durationS: Int64(max(0, workout.duration).rounded()),
                freeText: nil,
                distanceM: Self.distance(of: workout),
                avgHr: heart.average,
                maxHr: heart.peak,
                source: SportStore.sourceHealth)
            // One refused workout must not stop the others.
            if let saved = try? await session.addSportSession(new) {
                existing.append(saved)
                written += 1
            }
        }
        return written
    }

    static let dedupeWindowMs: Int64 = 60_000

    /// The dedupe rule shared with Android: the exact same start is always a
    /// duplicate; a start within ±60 s is one unless the session there was
    /// typed by hand.
    static func isDuplicate(startMs: Int64, among sessions: [SportSession]) -> Bool {
        sessions.contains { s in
            s.startedMs == startMs
                || (s.source != SportStore.sourceManual && abs(s.startedMs - startMs) <= dedupeWindowMs)
        }
    }

    /// Average and peak over the workout, from every heart-rate sample Santé
    /// holds for that interval (a watch's, a strap's). Nil when there are
    /// none, or when the value is outside what the vault accepts.
    private func heartRate(during workout: HKWorkout) async -> (average: Int64?, peak: Int64?) {
        let predicate = HKQuery.predicateForSamples(
            withStart: workout.startDate, end: workout.endDate, options: [])
        let descriptor = HKStatisticsQueryDescriptor(
            predicate: .quantitySample(type: HKQuantityType(.heartRate), predicate: predicate),
            options: [.discreteAverage, .discreteMax])
        guard let stats = try? await descriptor.result(for: store) else { return (nil, nil) }
        let bpm = HKUnit.count().unitDivided(by: .minute())
        let average = stats.averageQuantity().map { Int64($0.doubleValue(for: bpm).rounded()) }
        let peak = stats.maximumQuantity().map { Int64($0.doubleValue(for: bpm).rounded()) }
        return (Self.plausibleBpm(average), Self.plausibleBpm(peak))
    }

    /// The vault's own bounds (`avg_hr`/`max_hr` CHECK 20–300).
    static func plausibleBpm(_ value: Int64?) -> Int64? {
        guard let value, (20...300).contains(value) else { return nil }
        return value
    }

    /// Metres, from the workout's own statistics: walking/running first, then
    /// cycling. Nil for a workout with no distance (strength, yoga…).
    private static func distance(of workout: HKWorkout) -> Double? {
        for id in [HKQuantityTypeIdentifier.distanceWalkingRunning, .distanceCycling] {
            if let metres = workout.statistics(for: HKQuantityType(id))?.sumQuantity()?.doubleValue(for: .meter()),
               metres > 0 {
                return metres
            }
        }
        return nil
    }

    /// The English word `WatchImporter.matchActivity` compares against the
    /// user's own activity names (it knows the French ones too). Nil for
    /// anything else: the session is filed with no type rather than a guess.
    static func hint(for type: HKWorkoutActivityType) -> String? {
        switch type {
        case .running: return "running"
        case .cycling: return "biking"
        case .walking: return "walking"
        case .swimming: return "swimming"
        case .hiking: return "hiking"
        case .traditionalStrengthTraining, .functionalStrengthTraining: return "strength"
        case .yoga: return "yoga"
        case .rowing: return "rowing"
        case .elliptical: return "elliptical"
        case .downhillSkiing, .crossCountrySkiing: return "skiing"
        default: return nil
        }
    }

    // MARK: Steps

    /// Daily sums, each day from local midnight. Santé already merges the
    /// iPhone's and the Apple Watch's counts without double counting, which
    /// is the whole reason to prefer it over adding the two up here.
    ///
    /// A day the user corrected by hand in the last week (see
    /// `StepCounter.correct`) is left alone: the import keeps the larger
    /// value, and would otherwise undo the one deliberate way to bring a day
    /// down.
    private func importSteps(from: Date, to: Date, session: VaultService) async throws -> Int {
        let calendar = Calendar.current
        let start = calendar.startOfDay(for: from)
        let predicate = HKQuery.predicateForSamples(withStart: start, end: to, options: [])
        let descriptor = HKStatisticsCollectionQueryDescriptor(
            predicate: .quantitySample(type: HKQuantityType(.stepCount), predicate: predicate),
            options: .cumulativeSum,
            anchorDate: start,
            intervalComponents: DateComponents(day: 1))
        let collection = try await descriptor.result(for: store)

        var totals: [(key: String, steps: Int64)] = []
        collection.enumerateStatistics(from: start, to: to) { stats, _ in
            guard let sum = stats.sumQuantity() else { return }
            let steps = Int64(sum.doubleValue(for: .count()).rounded())
            guard steps > 0 else { return }
            totals.append((SportFormat.dayKey(stats.startDate, calendar: calendar), steps))
        }

        let corrected = SportPrefs.stepOffsets()
        var written = 0
        for day in totals where corrected[day.key] == nil {
            try await session.recordSteps(dayKey: day.key, steps: day.steps, nowMs: Time.nowMs())
            written += 1
        }
        return written
    }

    private static func ms(_ date: Date) -> Int64 { Int64(date.timeIntervalSince1970 * 1000) }
}
