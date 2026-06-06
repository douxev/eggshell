import Foundation
import SwiftUI
import TransitionCore

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
    /// Weak global handle so NotificationCoordinator can ask us to drain queued
    /// reminder actions. Set in init; the app only ever has one AppState.
    static weak var shared: AppState?

    @Published var route: AppRoute = .launching
    @Published private(set) var session: VaultService?

    let manager = VaultManager()

    init() { Self.shared = self }

    func bootstrap() async {
        let provisioned = await manager.isProvisioned
        route = provisioned ? .unlock : .onboarding
    }

    func completeOnboarding(session: VaultService) {
        self.session = session
        route = .home
        Task { await refreshNotifications(); await drainPendingDoses() }
    }

    func unlocked(session: VaultService) {
        self.session = session
        route = .home
        Task { await refreshNotifications(); await drainPendingDoses() }
    }

    /// Commit reminder actions ("Pris"/"Passer") taken while the vault was
    /// locked. No-op without an open session.
    func drainPendingDoses() async {
        guard let session else { return }
        let pending = PendingDoseStore.drainAll()
        guard !pending.isEmpty else { return }
        do {
            let schedules = try await session.listActiveSchedules()
            let byId = Dictionary(uniqueKeysWithValues: schedules.map { ($0.id, $0) })
            for p in pending {
                let dose = NewDoseEvent(
                    medicationId: p.medId, takenAtMs: p.atMs,
                    dose: nil, doseUnit: nil, route: nil, injectionSite: nil, notes: nil,
                    status: p.taken ? "taken" : "skipped", scheduledAtMs: nil, scheduleId: p.scheduleId)
                _ = try await session.logDose(dose)
                if let sched = byId[p.scheduleId] {
                    try await session.setScheduleNextDue(p.scheduleId, NextDueCalculator.advance(sched))
                }
            }
            await refreshNotifications()
        } catch { /* best-effort; queue already drained */ }
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
                nameFor: { names[$0] ?? "Traitement" })
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
