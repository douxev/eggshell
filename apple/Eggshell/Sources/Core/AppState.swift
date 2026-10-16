import Foundation
import SwiftUI
import TransitionCore
import UIKit

enum AppRoute: Equatable {
    case launching
    case onboarding
    case unlock
    /// Biometric mode with no recovery secret set. Sits between unlock and home
    /// and cannot be walked past — see RecoverySetupView.
    case recoverySetup
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

    /// True while the vault is deliberately closed for maintenance the user
    /// asked for — today, a paranoid-mode passphrase change, which re-encrypts
    /// the database in place and therefore cannot run with a connection open.
    ///
    /// Elsewhere "no session" means "the user must re-authenticate", which is
    /// right for every other way a session ends and wrong for this one: it
    /// would tear down the very screen that is driving the operation.
    @Published private(set) var busy = false

    /// Set when something asked to lock while `busy`. The vault was already
    /// closed, so `lock()` had nothing to do — but the request must still be
    /// honoured, or backgrounding the phone mid-rekey would end with the vault
    /// re-opened behind a lock screen the user never passed.
    private var lockRequestedWhileBusy = false

    let manager = VaultManager()

    init() { Self.shared = self }

    func bootstrap() async {
        let provisioned = await manager.isProvisioned
        route = provisioned ? .unlock : .onboarding
    }

    func completeOnboarding(session: VaultService) {
        self.session = session
        route = .home
        Task { await refreshNotifications(); await refreshAppointmentReminders(); await drainPendingDoses() }
    }

    func unlocked(session: VaultService) {
        self.session = session
        // Fire-and-forget, after the session exists and without holding up
        // the route: step totals, Santé imports and reminder dates are
        // conveniences, the unlock is not. The step and Santé syncs write to
        // the vault, so only this path — a real unlock — reaches them; the
        // decoy never does. Reminder dates live outside the vault and are also
        // re-armed on return to the foreground (see LabReminderStore).
        Task {
            await StepCounter.syncAfterUnlock(session: session)
            // After the pedometer, so the two step writers never interleave —
            // the vault keeps the max either way. A no-op unless the build
            // carries HealthKit and the user turned the import on.
            await HealthKitImporter.syncAfterUnlock(session: session)
        }
        LabReminderStore.shared?.refresh()
        Task {
            // Decided before the route is set, not after: routing to .home and
            // correcting it a hop later mounts the whole shell, loads it, and
            // tears it down again behind the gate. The gate itself can only
            // exist once the vault is open — it mints a second wrap of the
            // master key.
            // Bound to its own `let` rather than written inline: `await` in
            // front of a ternary is legal but reads as though it covered only
            // the condition, and this line decides whether a gate appears.
            let needsGate = await manager.needsRecoverySetup
            route = needsGate ? .recoverySetup : .home
            await refreshNotifications(); await refreshAppointmentReminders(); await drainPendingDoses()
        }
    }

    /// Leave the recovery gate, once a secret has actually been persisted.
    func recoverySetupFinished() { route = .home }

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

    /// Re-arm appointment reminders from the vault. Local notifications are
    /// pre-scheduled, so this rebuilds them on unlock (and after any change).
    func refreshAppointmentReminders() async {
        guard let session else { return }
        do {
            let appts = try await session.listAppointments(limit: 1000)
            await NotificationManager.scheduleAppointmentReminders(appts)
        } catch { /* reminders are best-effort */ }
    }

    func enterDecoy() {
        session = nil
        route = .decoy
    }

    /// Lock on background / manual lock. Drops the in-memory vault handle.
    func lock() {
        if busy {
            lockRequestedWhileBusy = true
            return
        }
        session = nil
        if route == .home { route = .unlock }
    }

    /// Replace the passphrase that opens the vault, keeping every byte of data.
    ///
    /// Two passphrase modes, two entirely different jobs — see
    /// `VaultManager.rewrapPassphrase` and `VaultManager.rekeyParanoid`. The
    /// paranoid path is the one this method exists for: the session has to be
    /// closed before the rewrite and reopened after it, and a lock requested in
    /// between must win over the reopen.
    ///
    /// The session is closed by the manager, through `closeSession`, and only
    /// once the current passphrase has been verified. Closing it first used to
    /// mean a typo in « phrase actuelle » ended with the reopen failing too —
    /// the user thrown out to the lock screen for a spelling mistake.
    func changePassphrase(current: String, new: String) async -> VaultManager.ChangePassphraseOutcome {
        guard let mode = await manager.currentMode else { return .failed("coffre non initialisé") }
        switch mode {
        case .keystorePassphrase:
            return await manager.rewrapPassphrase(current: current, new: new)
        case .paranoid:
            // A full re-encryption can outlast the few seconds iOS grants a
            // backgrounded app. Ask for more, so switching apps mid-way does
            // not freeze the rewrite half done — the order stays kill-safe
            // either way, this only makes the kill less likely.
            let grace = BackgroundGrace(name: "eggshell.passphrase-rekey")
            defer { grace.end() }

            let outcome = await manager.rekeyParanoid(current: current, new: new) { [weak self] in
                guard let self else { return }
                self.lockRequestedWhileBusy = false
                self.busy = true
                // SQLCipher rekeys in place, and an open handle would carry
                // on reading pages with the retired key.
                self.session = nil
            }
            // Never closed: a typo, nothing to change, or a refusal before any
            // byte moved. The session the user was working in is untouched.
            guard busy else { return outcome }

            // Which passphrase the file answers to now. `undetermined` tries
            // both: the manager's resolution settles the change with
            // whichever one opens it.
            let candidates: [String]
            switch outcome {
            case .success:  candidates = [new]
            case .failed:   candidates = [current]
            default:        candidates = [new, current]
            }
            // Still `busy` while reopening, so a lock requested during the
            // reopen is recorded rather than lost.
            var reopened: VaultService?
            if !lockRequestedWhileBusy {
                for passphrase in candidates {
                    if let s = try? await manager.unlock(passphrase: passphrase) {
                        reopened = s
                        break
                    }
                }
            }
            busy = false
            if let reopened, !lockRequestedWhileBusy {
                session = reopened
            } else {
                // Either a lock was asked for while busy, or nothing reopened
                // the vault; the unlock screen's two-salt resolution is the
                // way back in.
                lockRequestedWhileBusy = false
                lock()
            }
            return outcome
        case .keystoreOnly, .keystoreBiometric:
            return .notApplicable
        }
    }

    func wipe() async {
        await manager.wipeEverything()
        session = nil
        route = .onboarding
    }
}

/// A background-task assertion around work that must not be frozen half way.
///
/// iOS suspends a backgrounded app within seconds unless it asks for time.
/// `end()` is idempotent: it runs from `defer` when the work finishes, or from
/// the expiration handler when iOS runs out of patience — and it must run in
/// the latter case, or iOS kills the app for overstaying instead of merely
/// suspending it.
@MainActor
private final class BackgroundGrace {
    private var id: UIBackgroundTaskIdentifier = .invalid

    init(name: String) {
        id = UIApplication.shared.beginBackgroundTask(withName: name) { [weak self] in
            // Delivered on the main thread.
            MainActor.assumeIsolated { self?.end() }
        }
    }

    func end() {
        guard id != .invalid else { return }
        UIApplication.shared.endBackgroundTask(id)
        id = .invalid
    }
}
