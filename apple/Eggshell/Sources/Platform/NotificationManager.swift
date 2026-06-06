import Foundation
import UserNotifications
import TransitionCore

// Local reminders. Unlike Android (AlarmManager + a locked-vault metadata
// mirror), iOS local notifications are pre-scheduled and fire regardless of app
// state — so whenever the vault opens or schedules change we replace all pending
// reminders with the next several occurrences of each active schedule, advancing
// each one via `NextDueCalculator.advance` (iOS caps an app at 64 pending local
// notifications, so we fan out a bounded number).
//
// Content mode/alias/priority is read internally from `NotifPrefs` (the caller
// only passes schedules + a name resolver). The privacy default is generic copy:
// nothing about which medication is due reaches the lock screen.
//
// Category + userInfo wiring matches NotificationCoordinator so its delegate can
// reconstruct the "Pris"/"Passer" action. We DO NOT register categories or set
// the delegate here — NotificationCoordinator.configure() owns that.
enum NotificationManager {
    private static var center: UNUserNotificationCenter { .current() }

    /// How many future occurrences to pre-schedule per active schedule. iOS only
    /// keeps 64 pending notifications; this leaves room across several schedules.
    private static let occurrencesPerSchedule = 16

    static func requestAuthorization() async -> Bool {
        (try? await center.requestAuthorization(options: [.alert, .sound, .badge])) ?? false
    }

    /// Replace all pending medication reminders with the next several occurrences
    /// of each active schedule. `nameFor` resolves a medication id → display name
    /// (used only in `.name` content mode). AppState calls this with exactly
    /// these two arguments.
    static func reschedule(
        schedules: [DoseSchedule],
        nameFor: (Int64) -> String
    ) async {
        guard await requestAuthorization() else { return }
        center.removeAllPendingNotificationRequests()

        let mode = NotifPrefs.contentMode
        let highPriority = NotifPrefs.highPriority
        let now = Date()

        for s in schedules where s.active {
            // Seed from the stored next-due, never the past.
            var fire = NextDueCalculator.date(s.nextDueAtMs)
            if fire <= now { fire = now.addingTimeInterval(60) }

            for occurrence in 0..<occurrencesPerSchedule {
                let content = makeMedContent(for: s, mode: mode, highPriority: highPriority, nameFor: nameFor)

                let comps = Calendar.current.dateComponents(
                    [.year, .month, .day, .hour, .minute], from: fire)
                let trigger = UNCalendarNotificationTrigger(dateMatching: comps, repeats: false)
                let req = UNNotificationRequest(
                    identifier: "sched-\(s.id)-\(occurrence)",
                    content: content,
                    trigger: trigger)
                try? await center.add(req)

                // Advance to the next occurrence. `advance` is phase-aware for
                // daily/days_interval and just adds the interval otherwise.
                let nextMs = NextDueCalculator.advance(s, now: fire)
                let next = NextDueCalculator.date(nextMs)
                guard next > fire else { break } // guard against a non-advancing schedule
                fire = next
            }
        }
    }

    /// One-shot lab/photo/voice reminders. One pending notification per enabled
    /// reminder, at its next due date. Generic copy: the lock screen never shows
    /// which bilan/photo/voix is due.
    static func scheduleLabReminders(_ items: [LabReminder]) async {
        guard await requestAuthorization() else { return }
        // Drop only the lab identifiers we own, leaving med reminders intact.
        let pending = await center.pendingNotificationRequests()
        let stale = pending.map(\.identifier).filter { $0.hasPrefix("lab-") }
        if !stale.isEmpty { center.removePendingNotificationRequests(withIdentifiers: stale) }

        let highPriority = NotifPrefs.highPriority
        let now = Date()

        for r in items where r.enabled {
            var fire = NextDueCalculator.date(r.nextDueMs)
            if fire <= now { fire = now.addingTimeInterval(60) }

            let content = UNMutableNotificationContent()
            content.sound = .default
            content.title = "Rappel"
            content.body = "Un rappel est arrivé à échéance."
            content.interruptionLevel = highPriority ? .timeSensitive : .passive

            let comps = Calendar.current.dateComponents(
                [.year, .month, .day, .hour, .minute], from: fire)
            let trigger = UNCalendarNotificationTrigger(dateMatching: comps, repeats: false)
            let req = UNNotificationRequest(identifier: "lab-\(r.id)", content: content, trigger: trigger)
            try? await center.add(req)
        }
    }

    static func cancelAll() {
        center.removeAllPendingNotificationRequests()
    }

    // MARK: - Content

    private static func makeMedContent(
        for s: DoseSchedule,
        mode: NotifContentMode,
        highPriority: Bool,
        nameFor: (Int64) -> String
    ) -> UNMutableNotificationContent {
        let content = UNMutableNotificationContent()
        content.sound = .default
        content.categoryIdentifier = NotificationCoordinator.categoryId
        content.userInfo = [
            "scheduleId": NSNumber(value: s.id),
            "medId": NSNumber(value: s.medicationId),
        ]
        content.interruptionLevel = highPriority ? .timeSensitive : .passive

        switch mode {
        case .generic:
            content.title = "Rappel"
            content.body = "C'est l'heure de votre prise."
        case .name:
            content.title = nameFor(s.medicationId)
            content.body = "C'est l'heure de votre prise."
        case .alias:
            // Fall back to generic copy when no alias is set — never the real name.
            if let alias = NotifPrefs.alias(for: s.medicationId) {
                content.title = alias
                content.body = "C'est l'heure de votre prise."
            } else {
                content.title = "Rappel"
                content.body = "C'est l'heure de votre prise."
            }
        }
        return content
    }
}
