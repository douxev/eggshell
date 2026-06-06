import Foundation
import UserNotifications

// Notification action infrastructure shared by the scheduler (NotificationManager)
// and the app. Mirrors the android ReminderNotifications "Pris"/"Passer" actions
// + the locked-vault PendingDosePrefs queue: when the user acts on a reminder
// while the vault is locked, we can't write to the DB, so the action is queued
// (id-only, no medical content) and drained on the next unlock.

/// A reminder action the user took, awaiting commit to the vault.
struct PendingDose: Codable {
    let scheduleId: Int64
    let medId: Int64
    let atMs: Int64
    let taken: Bool   // true = "Pris", false = "Passer"
}

/// Plaintext id-only queue (no medication name/dose), drained on unlock.
enum PendingDoseStore {
    private static let d = UserDefaults(suiteName: "com.douxev.eggshell.pending") ?? .standard
    private static let key = "queue"

    static func append(_ p: PendingDose) {
        var all = load()
        all.append(p)
        if let data = try? JSONEncoder().encode(all) { d.set(data, forKey: key) }
    }
    static func load() -> [PendingDose] {
        guard let data = d.data(forKey: key),
              let all = try? JSONDecoder().decode([PendingDose].self, from: data) else { return [] }
        return all
    }
    static func clear() { d.removeObject(forKey: key) }
    static func drainAll() -> [PendingDose] { let all = load(); clear(); return all }
}

/// Singleton delegate: shows banners in foreground and routes "Pris"/"Passer"
/// taps into the pending queue, then asks AppState to drain it (a no-op while
/// locked). The scheduler tags each reminder with `categoryId` + userInfo
/// (scheduleId/medId) so this can reconstruct the action.
final class NotificationCoordinator: NSObject, UNUserNotificationCenterDelegate {
    static let shared = NotificationCoordinator()

    static let categoryId = "DOSE_REMINDER"
    static let actionTaken = "MARK_TAKEN"
    static let actionSkip  = "MARK_SKIPPED"

    /// Register the category/actions and become the notification delegate. Call
    /// once at launch.
    func configure() {
        let taken = UNNotificationAction(identifier: Self.actionTaken, title: "Pris", options: [])
        let skip  = UNNotificationAction(identifier: Self.actionSkip,  title: "Passer", options: [])
        let category = UNNotificationCategory(
            identifier: Self.categoryId, actions: [taken, skip],
            intentIdentifiers: [], options: [])
        UNUserNotificationCenter.current().setNotificationCategories([category])
        UNUserNotificationCenter.current().delegate = self
    }

    // Show reminders even when the app is in the foreground.
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        [.banner, .sound, .list]
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse
    ) async {
        let info = response.notification.request.content.userInfo
        guard let scheduleId = (info["scheduleId"] as? NSNumber)?.int64Value,
              let medId = (info["medId"] as? NSNumber)?.int64Value else { return }

        switch response.actionIdentifier {
        case Self.actionTaken, Self.actionSkip:
            PendingDoseStore.append(PendingDose(
                scheduleId: scheduleId, medId: medId,
                atMs: Time.nowMs(), taken: response.actionIdentifier == Self.actionTaken))
            await AppState.shared?.drainPendingDoses()
        default:
            break   // plain tap just opens the app
        }
    }
}
