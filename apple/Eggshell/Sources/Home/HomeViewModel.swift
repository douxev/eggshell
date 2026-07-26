import Foundation
import UserNotifications
import TransitionCore

/// State of the launcher home — the only root screen of the refonte.
///
/// It answers three questions at a glance: what is the next dose, how do you
/// feel today, and what is waiting for you in each module. Everything the user
/// does daily happens here, without navigating.
@MainActor
final class HomeViewModel: ObservableObject {

    /// One dose expected today.
    struct DoseItem: Identifiable, Equatable {
        /// One *occurrence*, not one schedule: a twice-daily treatment yields two
        /// items sharing `scheduleId`, so the identity has to carry the planned
        /// time too.
        var id: String { "\(scheduleId)-\(scheduledAtMs)" }
        let scheduleId: Int64
        let medication: Medication
        let scheduledAtMs: Int64
        let done: Bool

        static func == (lhs: DoseItem, rhs: DoseItem) -> Bool {
            lhs.scheduleId == rhs.scheduleId
                && lhs.scheduledAtMs == rhs.scheduledAtMs
                && lhs.done == rhs.done
        }
    }

    /// The single reminder line under the dose card, plus how many follow.
    struct ReminderLine: Equatable {
        let text: String
        let dueAtMs: Int64
        let othersCount: Int
    }

    /// A launcher badge. Counters win over dots, and at most two are shown.
    enum Badge: Equatable {
        case counter(Int)
        case news
    }

    @Published private(set) var loading = true
    @Published private(set) var error: String?
    @Published private(set) var doses: [DoseItem] = []
    @Published private(set) var hasMedications = false
    @Published private(set) var hasInjectable = false
    /// 1…5, or nil when nothing was recorded today.
    @Published private(set) var moodFace: Int?
    @Published private(set) var reminder: ReminderLine?
    @Published private(set) var badges: [LauncherModule: Badge] = [:]
    /// Bumped on every successful save, so the view can fire one haptic.
    @Published private(set) var savedTick = 0

    var takenCount: Int { doses.filter(\.done).count }
    var plannedCount: Int { doses.count }
    var nextDose: DoseItem? { doses.first { !$0.done } }
    var allTaken: Bool { !doses.isEmpty && nextDose == nil }

    /// At most two badges on screen, counters before dots — more turns the home
    /// into a wall of red.
    private static let maxBadges = 2

    // MARK: - Load

    func load(session: VaultService, features: FeaturesStore, labReminders: [LabReminder]) async {
        let cal = Calendar.current
        let startOfDay = cal.startOfDay(for: Date())
        let startOfTomorrow = cal.date(byAdding: .day, value: 1, to: startOfDay) ?? startOfDay
        let startMs = Int64(startOfDay.timeIntervalSince1970 * 1000)
        let tomorrowMs = Int64(startOfTomorrow.timeIntervalSince1970 * 1000)
        let nowMs = Time.nowMs()

        var todayDoses: [DoseItem] = []
        var futureMeds: [(String, Int64)] = []
        var meds: [Medication] = []

        do {
            if features.medications {
                meds = try await session.listMedications()
                let medById = Dictionary(uniqueKeysWithValues: meds.map { ($0.id, $0) })

                // The day's doses come from the same occurrence grid Médics and
                // the doctor PDF read, so the three can never disagree. Two
                // things this buys us that reading `nextDueAtMs` could not:
                //  - « pris » is resolved per occurrence, so a twice-daily
                //    treatment keeps its evening dose after the morning one is
                //    ticked;
                //  - ticking a dose advances the schedule to tomorrow, which
                //    used to make it leave today's window and collapse the card
                //    to « 0/0 · aucune prise programmée » right after a success.
                let grid = await PlannedDoses.window(
                    session: session, fromMs: startMs, toMs: tomorrowMs, calendar: cal)
                todayDoses = grid.occurrences
                    .sorted { $0.plannedAtMs < $1.plannedAtMs }
                    .compactMap { occurrence -> DoseItem? in
                        guard let med = medById[occurrence.medicationId] else { return nil }
                        return DoseItem(
                            scheduleId: occurrence.scheduleId,
                            medication: med,
                            scheduledAtMs: occurrence.plannedAtMs,
                            done: occurrence.event != nil)
                    }

                let active = try await session.listActiveSchedules()
                for s in active where s.nextDueAtMs >= tomorrowMs {
                    guard let med = medById[s.medicationId] else { continue }
                    futureMeds.append((med.name, s.nextDueAtMs))
                }
            }

            var face: Int?
            if features.journal,
               let entry = try await todayEntry(session, from: startMs, to: tomorrowMs),
               let mood = entry.mood {
                face = Self.face(forMood: Int(mood))
            }

            let newBadges = try await computeBadges(
                session: session, features: features, doses: todayDoses)

            self.hasMedications = !meds.isEmpty
            self.hasInjectable = meds.contains { MedCatalog.isInjection($0.route) }
            self.doses = todayDoses
            self.moodFace = face
            self.badges = newBadges
            self.error = nil
        } catch {
            self.error = describe(error)
        }

        // Reminder line: the nearest future medication schedule or lab/photo/
        // voice reminder, plus a count of the others.
        var upcoming = futureMeds
        for r in labReminders where r.enabled && r.nextDueMs >= nowMs {
            if r.kind == LabReminderKind.photo && !features.photos { continue }
            if r.kind == LabReminderKind.voice && !features.voice { continue }
            if r.kind == LabReminderKind.journal && !features.journal { continue }
            upcoming.append((r.label, r.nextDueMs))
        }
        upcoming.sort { $0.1 < $1.1 }
        reminder = upcoming.first.map {
            ReminderLine(text: $0.0, dueAtMs: $0.1, othersCount: upcoming.count - 1)
        }

        loading = false
    }

    // MARK: - Actions

    /// Marks the next dose as taken now. The planned time travels with the
    /// event — that is what makes the offset ("+1 h 47") computable later; the
    /// offset itself is never stored.
    func markTakenNow(_ item: DoseItem, session: VaultService) async -> Bool {
        do {
            let med = item.medication
            _ = try await session.logDose(NewDoseEvent(
                medicationId: med.id,
                takenAtMs: Time.nowMs(),
                dose: med.defaultDose,
                doseUnit: med.defaultDoseUnit,
                route: med.route,
                injectionSite: nil,
                notes: nil,
                status: "taken",
                scheduledAtMs: item.scheduledAtMs,
                scheduleId: item.scheduleId))
            let active = try await session.listActiveSchedules()
            if let sched = active.first(where: { $0.id == item.scheduleId }) {
                try await session.setScheduleNextDue(
                    item.scheduleId, NextDueCalculator.advance(sched))
            }
            savedTick += 1
            return true
        } catch {
            self.error = describe(error)
            return false
        }
    }

    /// « Décaler » — re-show the reminder in half an hour without moving the
    /// schedule itself. The copy stays the privacy default whatever the user
    /// picked for scheduled reminders: a snooze is not a reason to start
    /// naming a treatment on the lock screen.
    func snooze(_ item: DoseItem, minutes: Int = 30) {
        let content = UNMutableNotificationContent()
        content.title = "Rappel"
        content.body = "C'est l'heure de votre prise."
        content.sound = .default
        content.categoryIdentifier = NotificationCoordinator.categoryId
        content.userInfo = [
            "scheduleId": NSNumber(value: item.scheduleId),
            "medId": NSNumber(value: item.medication.id),
        ]
        content.interruptionLevel = NotifPrefs.highPriority ? .timeSensitive : .passive
        let request = UNNotificationRequest(
            identifier: "snooze-\(item.scheduleId)",
            content: content,
            trigger: UNTimeIntervalNotificationTrigger(
                timeInterval: Double(minutes) * 60, repeats: false))
        Task { try? await UNUserNotificationCenter.current().add(request) }
    }

    /// One tap on a face records the day's mood immediately — no validation
    /// step. Tapping again corrects the same entry rather than stacking a
    /// second one.
    func setMoodFace(_ face: Int, session: VaultService) async -> Bool {
        let cal = Calendar.current
        let startOfDay = cal.startOfDay(for: Date())
        let startOfTomorrow = cal.date(byAdding: .day, value: 1, to: startOfDay) ?? startOfDay
        let startMs = Int64(startOfDay.timeIntervalSince1970 * 1000)
        let tomorrowMs = Int64(startOfTomorrow.timeIntervalSince1970 * 1000)
        let mood = UInt32(Self.mood(forFace: face))

        do {
            if let existing = try await todayEntry(session, from: startMs, to: tomorrowMs) {
                _ = try await session.updateJournalEntry(existing.id, NewJournalEntry(
                    atMs: existing.atMs,
                    mood: mood,
                    dysphoria: existing.dysphoria,
                    euphoria: existing.euphoria,
                    libido: existing.libido,
                    energy: existing.energy,
                    freeText: existing.freeText,
                    sideEffects: existing.sideEffects))
            } else {
                _ = try await session.addJournalEntry(NewJournalEntry(
                    atMs: Time.nowMs(),
                    mood: mood,
                    dysphoria: nil,
                    euphoria: nil,
                    libido: nil,
                    energy: nil,
                    freeText: nil,
                    sideEffects: nil))
            }
            moodFace = face
            savedTick += 1
            return true
        } catch {
            self.error = describe(error)
            return false
        }
    }

    func markModuleOpened(_ module: LauncherModule) {
        ModuleBadgePrefs.markOpened(module)
        badges.removeValue(forKey: module)
    }

    // MARK: - Helpers

    private func todayEntry(
        _ session: VaultService,
        from startMs: Int64,
        to endMs: Int64
    ) async throws -> JournalEntry? {
        let recent = try await session.listJournalEntries(offset: 0, limit: 20)
        return recent.first { $0.atMs >= startMs && $0.atMs < endMs }
    }

    private func computeBadges(
        session: VaultService,
        features: FeaturesStore,
        doses: [DoseItem]
    ) async throws -> [LauncherModule: Badge] {
        var counters: [(LauncherModule, Badge)] = []
        if features.medications {
            let pending = doses.filter { !$0.done }.count
            if pending > 0 { counters.append((.meds, .counter(pending))) }
        }

        var dots: [(LauncherModule, Badge)] = []
        if features.hormones {
            // There is no "list every measurement" call, and the per-analyte
            // list is ascending, so the newest sample is the last of each series.
            var newest: Int64 = 0
            for analyte in try await session.distinctHormones() {
                let series = try await session.listHormoneMeasurements(hormone: analyte)
                newest = max(newest, series.map(\.atMs).max() ?? 0)
            }
            if newest > ModuleBadgePrefs.lastOpened(.labs) {
                dots.append((.labs, .news))
            }
        }

        return Dictionary(uniqueKeysWithValues: (counters + dots).prefix(Self.maxBadges))
    }

    /// Face 1…5 → a 0-10 mood, centred on the five buckets.
    static func mood(forFace face: Int) -> Int {
        switch face {
        case 1:  return 0
        case 2:  return 3
        case 3:  return 5
        case 4:  return 8
        default: return 10
        }
    }

    /// Inverse of `mood(forFace:)`, for any stored 0-10 value.
    static func face(forMood mood: Int) -> Int {
        switch mood {
        case ...1: return 1
        case ...4: return 2
        case ...6: return 3
        case ...9: return 4
        default:   return 5
        }
    }
}
