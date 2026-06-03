import Foundation
import SwiftUI

enum AppRoute: Equatable {
    case launching
    case onboarding
    case unlock
    case home
    case decoy     // fake notes app (plausible deniability)
}

// Root observable. Owns the navigation phase and the opened vault session.
@MainActor
final class AppState: ObservableObject {
    @Published var route: AppRoute = .launching
    @Published private(set) var session: VaultService?

    let manager = VaultManager()

    func bootstrap() async {
        let provisioned = await manager.isProvisioned
        route = provisioned ? .unlock : .onboarding
    }

    func completeOnboarding(session: VaultService) {
        self.session = session
        route = .home
        Task { await refreshNotifications() }
    }

    func unlocked(session: VaultService) {
        self.session = session
        route = .home
        Task { await refreshNotifications() }
    }

    /// (Re)schedule local reminders from the active schedules. Call after unlock
    /// and whenever schedules change.
    func refreshNotifications() async {
        guard let session else { return }
        do {
            let schedules = try await session.listActiveSchedules()
            let meds = try await session.listMedications(includeArchived: true)
            let names = Dictionary(uniqueKeysWithValues: meds.map { ($0.id, $0.name) })
            await NotificationManager.reschedule(
                schedules: schedules,
                nameFor: { names[$0] ?? "Médicament" })
        } catch { /* reminders are best-effort */ }
    }

    func enterDecoy() {
        session = nil
        route = .decoy
    }

    /// Lock on background / manual lock. Drops the in-memory vault handle.
    func lock() {
        session = nil
        if route == .home { route = .unlock }
    }

    func wipe() async {
        await manager.wipeEverything()
        session = nil
        route = .onboarding
    }
}
