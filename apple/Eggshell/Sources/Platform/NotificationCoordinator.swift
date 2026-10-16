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
    /// Several actions at once — a grouped reminder's « Tout pris » — in one
    /// read-modify-write, so no member can be lost between two of them.
    static func append(contentsOf items: [PendingDose]) {
        guard !items.isEmpty else { return }
        var all = load()
        all.append(contentsOf: items)
        if let data = try? JSONEncoder().encode(all) { d.set(data, forKey: key) }
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
    /// « Plus tard » on a single reminder: re-shown after `snoozeDelay`.
    static let actionSnooze = "SNOOZE"

    /// A grouped medication reminder (« Grouper les rappels de traitement »).
    /// Its userInfo carries parallel `scheduleIds` / `medIds` arrays, and each
    /// action applies to every member exactly as the single-reminder action
    /// applies to its one schedule.
    static let groupCategoryId = "DOSE_GROUP_REMINDER"
    static let actionAllTaken = "MARK_ALL_TAKEN"
    static let actionAllSkipped = "MARK_ALL_SKIPPED"
    static let actionSnoozeAll = "SNOOZE_ALL"

    /// « Plus tard » re-shows the reminder (single or grouped) this long after
    /// the tap. Same delay as Android's `AlarmScheduler.SNOOZE_MS`.
    static let snoozeDelay: TimeInterval = 30 * 60

    /// Every medication reminder shares one thread, so iOS stacks them in the
    /// notification centre whether or not they were merged.
    static let medicationThreadId = "eggshell.medication"

    /// Register the categories/actions and become the notification delegate.
    /// Call once at launch.
    func configure() {
        let taken = UNNotificationAction(identifier: Self.actionTaken, title: "Pris", options: [])
        let skip  = UNNotificationAction(identifier: Self.actionSkip,  title: "Passer", options: [])
        let snooze = UNNotificationAction(identifier: Self.actionSnooze, title: "Plus tard", options: [])
        let category = UNNotificationCategory(
            identifier: Self.categoryId, actions: [taken, snooze, skip],
            intentIdentifiers: [], options: [])
        let allTaken = UNNotificationAction(identifier: Self.actionAllTaken, title: "Tout pris", options: [])
        let later = UNNotificationAction(identifier: Self.actionSnoozeAll, title: "Plus tard", options: [])
        let allSkipped = UNNotificationAction(identifier: Self.actionAllSkipped, title: "Tout passer", options: [])
        let group = UNNotificationCategory(
            identifier: Self.groupCategoryId, actions: [allTaken, later, allSkipped],
            intentIdentifiers: [], options: [])
        UNUserNotificationCenter.current().setNotificationCategories([category, group])
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

        // A grouped reminder: one action, applied to every member.
        if let scheduleIds = info["scheduleIds"] as? [NSNumber],
           let medIds = info["medIds"] as? [NSNumber],
           !scheduleIds.isEmpty, scheduleIds.count == medIds.count {
            switch response.actionIdentifier {
            case Self.actionAllTaken, Self.actionAllSkipped:
                let taken = response.actionIdentifier == Self.actionAllTaken
                let atMs = Time.nowMs()
                // Through the same id-only queue as « Pris »/« Passer »: the
                // vault may be locked, and draining is a no-op until it opens.
                PendingDoseStore.append(contentsOf: zip(scheduleIds, medIds).map {
                    PendingDose(scheduleId: $0.int64Value, medId: $1.int64Value, atMs: atMs, taken: taken)
                })
                await AppState.shared?.drainPendingDoses()
            case Self.actionSnoozeAll:
                await NotificationManager.snoozeGroup(response.notification.request.content)
            default:
                break   // plain tap just opens the app
            }
            return
        }

        guard let scheduleId = (info["scheduleId"] as? NSNumber)?.int64Value,
              let medId = (info["medId"] as? NSNumber)?.int64Value else { return }

        switch response.actionIdentifier {
        case Self.actionTaken, Self.actionSkip:
            PendingDoseStore.append(PendingDose(
                scheduleId: scheduleId, medId: medId,
                atMs: Time.nowMs(), taken: response.actionIdentifier == Self.actionTaken))
            await AppState.shared?.drainPendingDoses()
        case Self.actionSnooze:
            // Nothing is logged: putting a dose off is not taking or skipping it.
            await NotificationManager.snoozeSingle(
                response.notification.request.content, scheduleId: scheduleId)
        default:
            break   // plain tap just opens the app
        }
    }
}
