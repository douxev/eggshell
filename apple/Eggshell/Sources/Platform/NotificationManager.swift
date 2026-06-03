import Foundation
import UserNotifications
import TransitionCore

// Local reminders. Unlike Android (AlarmManager + a locked-vault metadata
// mirror), iOS local notifications are pre-scheduled and fire regardless of app
// state — so we simply (re)schedule the next occurrence of each active schedule
// whenever the vault is opened or schedules change. Content defaults to generic
// text (no medication name on the lock screen).
enum NotificationContentMode: String { case generic, name }

enum NotificationManager {
    private static var center: UNUserNotificationCenter { .current() }

    static func requestAuthorization() async -> Bool {
        (try? await center.requestAuthorization(options: [.alert, .sound, .badge])) ?? false
    }

    /// Replace all pending medication reminders with the next occurrence of each
    /// active schedule. `nameFor` resolves a medication id → display name.
    static func reschedule(
        schedules: [DoseSchedule],
        nameFor: (Int64) -> String,
        mode: NotificationContentMode = .generic
    ) async {
        guard await requestAuthorization() else { return }
        center.removeAllPendingNotificationRequests()

        let now = Date()
        for s in schedules where s.active {
            var fire = NextDueCalculator.date(s.nextDueAtMs)
            if fire <= now { fire = now.addingTimeInterval(60) } // never schedule in the past

            let content = UNMutableNotificationContent()
            content.sound = .default
            switch mode {
            case .generic:
                content.title = "Rappel"
                content.body = "C'est l'heure de votre prise."
            case .name:
                content.title = nameFor(s.medicationId)
                content.body = "C'est l'heure de votre prise."
            }

            let comps = Calendar.current.dateComponents(
                [.year, .month, .day, .hour, .minute], from: fire)
            let trigger = UNCalendarNotificationTrigger(dateMatching: comps, repeats: false)
            let req = UNNotificationRequest(identifier: "sched-\(s.id)", content: content, trigger: trigger)
            try? await center.add(req)
        }
    }

    static func cancelAll() {
        center.removeAllPendingNotificationRequests()
    }
}
