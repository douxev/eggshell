import SwiftUI
import LocalAuthentication

// The lock screen of §6.13: no header, the mark, one warm sentence, four pips
// and the pad. Two things govern everything here.
//
//  1. The rate limiter is NOT re-implemented. `PinRateLimiter` owns the ladder
//     (3 free tries, then 5 s → 30 s → 2 min → 10 min → 1 h, and the vault is
//     erased at 12 cumulative failures). This screen only reads it and says out
//     loud how long the wait is and how many tries are left.
//  2. When a decoy PIN is configured the screen re-dresses **entirely** as a
//     notes app. Nothing themed may reach the pixels on that path — no lavender
//     flash, not even for the fraction of a second before the fake notes open.
//     That is why the view never touches `\.palette` directly: it resolves an
//     `UnlockSkin` first and paints from that, and why the decoy flag is read in
//     the view model's `init` rather than in `start()`: it is a plain
//     UserDefaults lookup, so it costs nothing to know it before the first
//     frame, and asking for it asynchronously would paint one branded frame in
//     front of whoever just asked to see the phone.

@MainActor
final class UnlockViewModel: ObservableObject {
    enum Step: Equatable {
        case loading
        case pin              // decoy configured → PIN gate first
        case biometric
        case passphrase
        case working
        case throttled(Int)   // seconds remaining
        /// The recovery secret, entered instead of the primary factor.
        case recovery
    }

    /// Starts on `.pin` under a decoy so the very first frame is already a
    /// complete passcode gate, and `.loading` otherwise.
    @Published var step: Step
    @Published var pin = ""
    @Published var passphrase = ""
    @Published var recoverySecret = ""
    @Published var error: String?
    /// Drives the neutral "Notes" re-skin of the lock screen. When a decoy PIN
    /// is configured the lock screen must not betray the real app (no lavender
    /// flash before the fake notes app appears), so the View reads this to swap
    /// the whole skin for a generic notes look. Resolved in `init` — before the
    /// first paint — never awaited.
    @Published private(set) var decoyConfigured: Bool
    @Published private(set) var mode: SecurityMode = .keystoreOnly
    /// Cumulative failures, mirrored purely so the screen can count down.
    @Published private(set) var failures = 0
    /// Raised once, just before the attempt that would erase everything (§5.4:
    /// a destructive step is announced *before* it becomes possible).
    @Published var wipeWarning = false

    /// Whether the screen may offer the recovery secret at all.
    ///
    /// Under a decoy this stays false until the access PIN has been accepted:
    /// « Utiliser ma clé de récupération » on what is supposed to be a notes
    /// app is a tell, and the whole point of the re-skin is that there is
    /// nothing to notice. Once the PIN is in, the cover is already dropped.
    @Published private(set) var recoveryReachable = false

    /// Display-only mirror of the threshold enforced by `PinRateLimiter`.
    /// Nothing here decides anything: it lets the screen say "il te reste N
    /// essais" instead of leaving the user to discover the wipe by hitting it.
    static let wipeThreshold = 12

    private weak var app: AppState?
    private var manager: VaultManager { app!.manager }
    private var limiter = PinRateLimiter()
    private let decoy: DecoyVerifier
    private var hasDecoy: Bool
    private var wipeWarningShown = false

    init() {
        // `DecoyVerifier.isConfigured` is a synchronous UserDefaults read, so the
        // skin is decided here rather than in `start()`. Anything awaited would
        // let one themed frame through, which under coercion is the whole cover
        // story lost (§6.13, « aucun flash lavande »).
        let verifier = DecoyVerifier()
        let configured = verifier.isConfigured
        decoy = verifier
        hasDecoy = configured
        decoyConfigured = configured
        step = configured ? .pin : .loading
    }

    var attemptsBeforeWipe: Int { max(0, Self.wipeThreshold - failures) }

    /// The biometric prompt is a system alert: under a decoy it must not print
    /// the real product name on top of a disguised app.
    var biometricReason: String {
        decoyConfigured ? "Déverrouiller tes notes" : "Déverrouiller eggshell"
    }

    func bind(_ app: AppState) { self.app = app }

    func start() async {
        mode = await manager.currentMode ?? .keystoreOnly
        // Same value `init` already read; re-read so that clearing the decoy pair
        // from Réglages is honoured the next time the app locks. It can only turn
        // the skin *off*, which is never a leak.
        hasDecoy = await manager.hasDecoy
        decoyConfigured = hasDecoy
        recoveryReachable = await manager.hasRecoverySecret && !hasDecoy
        refreshFailures()
        if let ms = throttleRemainingMs(), ms > 0 {
            step = .throttled(Int((ms + 999) / 1000))
            Task { await countdown() }
            return
        }
        if hasDecoy {
            step = .pin
        } else {
            await beginModeAuth()
        }
    }

    private func throttleRemainingMs() -> Int64? {
        let ms = limiter.remainingLockMs
        return ms > 0 ? ms : nil
    }

    private func refreshFailures() {
        failures = limiter.failures
        if attemptsBeforeWipe <= 1 && !wipeWarningShown {
            wipeWarningShown = true
            wipeWarning = true
        }
    }

    // MARK: PIN gate (decoy)

    func appendPin(_ digit: String) {
        guard step == .pin, pin.count < 4 else { return }
        error = nil
        pin.append(digit)
        if pin.count == 4 { Task { await submitPin() } }
    }
    func backspacePin() { if !pin.isEmpty { pin.removeLast() } }

    private func submitPin() async {
        let entered = pin
        pin = ""
        switch decoy.verify(entered) {
        case .access:
            limiter.recordSuccess()
            refreshFailures()
            await beginModeAuth()
        case .decoy:
            limiter.recordSuccess()
            refreshFailures()
            app?.enterDecoy()
        case .none:
            error = "Ce code n'est pas le bon."
            handleFailure()
        }
    }

    // MARK: Mode auth

    private func beginModeAuth() async {
        // Past the decoy gate (or never behind one): the recovery route may now
        // be shown without telling anyone anything they did not already know.
        recoveryReachable = await manager.hasRecoverySecret

        switch mode {
        case .keystoreOnly:
            await unlock()
        case .keystoreBiometric:
            step = .biometric
            await runBiometric()
        case .keystorePassphrase, .paranoid:
            step = .passphrase
        }
    }

    func runBiometric() async {
        error = nil
        do {
            let ctx = try await Biometric.authenticate(reason: biometricReason)
            await unlock(biometricContext: ctx)
        } catch {
            self.error = describe(error)
            step = .biometric
        }
    }

    func submitPassphrase() async {
        guard !passphrase.isEmpty else { return }
        await unlock(passphrase: passphrase)
    }

    private func unlock(passphrase: String? = nil, biometricContext: LAContext? = nil) async {
        step = .working
        error = nil
        do {
            let session = try await manager.unlock(
                passphrase: passphrase,
                biometricContext: biometricContext
            )
            limiter.recordSuccess()
            refreshFailures()
            app?.unlocked(session: session)
        } catch {
            if isWrongKey(error) { handleFailure() }
            self.error = describe(error)
            self.passphrase = ""   // @Published property, not the shadowing parameter
            // `handleFailure` may have moved us behind the lockout: honour that
            // rather than dropping the user straight back onto the keyboard.
            if case .throttled = step { return }
            step = (mode.needsPassphrase) ? .passphrase : (mode.needsBiometric ? .biometric : .passphrase)
        }
    }

    /// Open the vault with the recovery secret. The manager rebuilds the
    /// biometric key on the way through, from the key it just derived.
    func submitRecovery() async {
        let secret = recoverySecret
        guard !secret.isEmpty else { return }
        step = .working
        error = nil
        do {
            let session = try await manager.unlockWithRecovery(secret)
            limiter.recordSuccess()
            refreshFailures()
            recoverySecret = ""
            app?.unlocked(session: session)
        } catch {
            handleFailure()
            self.error = "Cette clé de récupération ne correspond pas."
            self.recoverySecret = ""
            if case .throttled = step { return }
            step = .recovery
        }
    }

    private func handleFailure() {
        let outcome = limiter.recordFailure()
        refreshFailures()
        switch outcome {
        case .wipe:
            Task { await app?.wipe() }
        case .locked(let ms):
            step = .throttled(Int((ms + 999) / 1000))
            Task { await countdown() }
        case .allowed:
            break
        }
    }

    private func countdown() async {
        while case .throttled(let s) = step, s > 0 {
            try? await Task.sleep(nanoseconds: 1_000_000_000)
            if case .throttled(let cur) = step { step = .throttled(cur - 1) }
        }
        if case .throttled(let s) = step, s <= 0 { await start() }
    }
}

// MARK: - Skin

/// Every colour the lock screen paints, resolved up front.
///
/// The `notes` skin is deliberately outside the token system, exactly like
/// `Brand`: it must NOT follow the user's theme, because its whole job is to
/// look like somebody else's app. Its values are the ones `DecoyNotesView`
/// already uses, so the lock screen and the fake notes are one continuous
/// surface with no colour jump between them.
private struct UnlockSkin {
    let accent: Color
    let onAccent: Color
    let surface: Color
    let keyContainer: Color
    let onSurface: Color
    let onSurfaceVariant: Color
    let outline: Color
    let errorContainer: Color
    let onErrorContainer: Color

    static func themed(_ p: Palette) -> UnlockSkin {
        UnlockSkin(
            accent: p.primary,
            onAccent: p.onPrimary,
            surface: p.surface,
            keyContainer: p.surfaceContainerHigh,
            onSurface: p.onSurface,
            onSurfaceVariant: p.onSurfaceVariant,
            outline: p.outline,
            errorContainer: p.errorContainer,
            onErrorContainer: p.onErrorContainer)
    }

    static let notes = UnlockSkin(
        accent: Color(hex: 0x006A6A),
        onAccent: .white,
        surface: Color(hex: 0xFAFDFC),
        keyContainer: Color(hex: 0xDCEDEC),
        onSurface: Color(hex: 0x191C1C),
        onSurfaceVariant: Color(hex: 0x3F4948),
        outline: Color(hex: 0xBEC9C8),
        errorContainer: Color(hex: 0xFFDAD6),
        onErrorContainer: Color(hex: 0x410002))
}

// MARK: - Screen

// Resolved once: `Biometric.kind` builds an `LAContext`, and the lock screen
// re-renders on every digit.
private let lockBiometricGlyph: String = Biometric.kind == .faceID ? "faceid" : "touchid"
private let lockBiometricWord: String = Biometric.kind == .faceID ? "Face ID" : "Touch ID"

struct UnlockView: View {
    @EnvironmentObject private var app: AppState
    @Environment(\.palette) private var palette
    @StateObject private var vm = UnlockViewModel()

    private var skin: UnlockSkin {
        vm.decoyConfigured ? .notes : .themed(palette)
    }

    private var locked: Bool {
        if case .throttled = vm.step { return true }
        return false
    }

    var body: some View {
        ZStack {
            skin.surface.ignoresSafeArea()
            VStack(spacing: 0) {
                Spacer(minLength: Spacing.l)
                identity
                if let message = vm.error, !locked {
                    Text(message)
                        .font(EggFont.bodyS)
                        .foregroundStyle(skin.onErrorContainer)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                        .background(skin.errorContainer, in: Capsule())
                        .padding(.top, Spacing.l)
                }
                if locked { lockoutCard.padding(.top, Spacing.l) }
                Spacer(minLength: Spacing.l)
                bottom
                recoveryEscape
            }
            .padding(.horizontal, 26)
            .padding(.bottom, 30)
        }
        // The root injects `palette.primary` as the app tint; on the decoy path
        // that would tint the caret and the spinner lavender. Override it here.
        .tint(skin.accent)
        .task { vm.bind(app); await vm.start() }
        .alert("Dernier essai", isPresented: $vm.wipeWarning) {
            Button("J'ai compris", role: .cancel) { }
        } message: {
            Text(vm.decoyConfigured
                 ? "Une erreur de plus et tout le contenu de cette app sera effacé, sans retour possible."
                 : "Une erreur de plus et ton coffre sera effacé, sans retour possible. Prends le temps qu'il faut.")
        }
    }

    // MARK: Identity

    private var identity: some View {
        VStack(spacing: 20) {
            if vm.decoyConfigured {
                // A notes app has no brand mark to show: a plain padlock.
                Image(systemName: "lock.fill")
                    .font(.system(size: 40))
                    .foregroundStyle(skin.accent)
                    .frame(height: 74)
            } else {
                EggshellLogo(size: 74)
            }
            Text(vm.decoyConfigured ? "Notes" : "Content de te revoir")
                .font(EggFont.titleL)
                .foregroundStyle(skin.onSurface)
                .multilineTextAlignment(.center)
            // The pips stay put while the lockout counts down, so the screen
            // keeps the same silhouette instead of collapsing under the card.
            if vm.step == .pin || (locked && vm.decoyConfigured) {
                PinPips(count: vm.pin.count, filled: skin.accent, empty: skin.outline)
                    .padding(.top, -4)
            }
        }
    }

    // MARK: Lockout (§5.3 — the error lives in the flow, with a live countdown)

    @ViewBuilder
    private var lockoutCard: some View {
        if case .throttled(let secs) = vm.step {
            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: Spacing.s) {
                    Image(systemName: "clock.fill").font(.system(size: 15, weight: .semibold))
                    Text("Trop d'essais").font(EggFont.titleS)
                }
                Text("Réessaie dans \(frenchDelay(secs)).")
                    .font(EggFont.bodyS)
                Text(remainingSentence)
                    .font(EggFont.bodyS)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .foregroundStyle(skin.onErrorContainer)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(Metrics.cardPadding)
            .background(
                RoundedRectangle(cornerRadius: Radius.card, style: .continuous)
                    .fill(skin.errorContainer)
            )
            .accessibilityElement(children: .combine)
        }
    }

    private var remainingSentence: String {
        let n = vm.attemptsBeforeWipe
        let what = vm.decoyConfigured ? "que tout soit effacé" : "que le coffre s'efface"
        if n <= 1 { return "Un dernier essai avant \(what)." }
        return "Il te reste \(n) essais avant \(what)."
    }

    // MARK: Bottom band — the pad, or the one action of the step

    @ViewBuilder
    private var bottom: some View {
        switch vm.step {
        case .loading, .working:
            VStack {
                ProgressView().tint(skin.accent)
            }
            .frame(maxWidth: .infinity, minHeight: Metrics.actionBarHeight)

        case .pin:
            keypad(enabled: true)

        case .throttled:
            // The lockout freezes whichever input the step was using, so the
            // screen keeps its shape instead of swapping under the countdown.
            if vm.decoyConfigured {
                keypad(enabled: false)
            } else if vm.mode.needsPassphrase {
                passphraseInput(enabled: false)
            } else {
                biometricAction(enabled: false)
            }

        case .biometric:
            biometricAction(enabled: true)

        case .passphrase:
            passphraseInput(enabled: true)

        case .recovery:
            recoveryInput
        }
    }

    /// The way back in when the primary factor is gone. Offered as a quiet
    /// text button rather than a filled one: it is the exception, and a screen
    /// that presents two equal buttons makes people hesitate every single day
    /// over a path they should need once.
    @ViewBuilder
    private var recoveryEscape: some View {
        if vm.recoveryReachable, vm.step == .biometric || vm.step == .passphrase {
            Button("Utiliser ma clé de récupération") {
                vm.error = nil
                vm.step = .recovery
            }
            .font(EggFont.bodyS)
            .foregroundStyle(skin.accent)
            .padding(.top, Spacing.s)
        }
    }

    private var recoveryInput: some View {
        VStack(spacing: Spacing.s) {
            SecureField("Clé de récupération", text: $vm.recoverySecret)
                .textContentType(.password)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .padding(Spacing.m)
                .background(RoundedRectangle(cornerRadius: Radius.field, style: .continuous)
                    .fill(skin.keyContainer))
                .foregroundStyle(skin.onSurface)

            filledButton("Ouvrir le coffre",
                         systemImage: "key.horizontal.fill",
                         enabled: !vm.recoverySecret.isEmpty) {
                Task { await vm.submitRecovery() }
            }

            Button("Revenir") {
                vm.error = nil
                vm.recoverySecret = ""
                vm.step = vm.mode.needsPassphrase ? .passphrase : .biometric
            }
            .font(EggFont.bodyS)
            .foregroundStyle(skin.onSurfaceVariant)
        }
        .padding(.horizontal, Metrics.screenMargin)
    }

    private func keypad(enabled: Bool) -> some View {
        PinKeypad(
            keyContainer: skin.keyContainer,
            digitColor: skin.onSurface,
            accentColor: skin.accent,
            mutedColor: skin.onSurfaceVariant,
            enabled: enabled,
            // The slot left of the 0 stays empty on purpose. The PIN gate is
            // what chooses between the real vault and the decoy one; a face
            // held in front of the phone under coercion must never be able to
            // make that choice, so biometrics come *after* the PIN, never
            // instead of it.
            biometricSymbol: nil,
            onDigit: { vm.appendPin($0) },
            onBackspace: { vm.backspacePin() })
    }

    private func biometricAction(enabled: Bool) -> some View {
        filledButton("Déverrouiller avec \(lockBiometricWord)",
                     systemImage: lockBiometricGlyph,
                     enabled: enabled) {
            Task { await vm.runBiometric() }
        }
        .frame(minHeight: Metrics.actionBarHeight)
    }

    private func passphraseInput(enabled: Bool) -> some View {
        VStack(spacing: Spacing.m) {
            SecureField("Phrase secrète", text: $vm.passphrase)
                .textFieldStyle(.plain)
                .textContentType(.password)
                .submitLabel(.go)
                .onSubmit { Task { await vm.submitPassphrase() } }
                .foregroundStyle(skin.onSurface)
                .padding(.horizontal, Spacing.l)
                .frame(height: 52)
                .background(
                    RoundedRectangle(cornerRadius: Radius.field, style: .continuous)
                        .fill(skin.keyContainer)
                )
            filledButton("Déverrouiller",
                         systemImage: nil,
                         enabled: enabled && !vm.passphrase.isEmpty) {
                Task { await vm.submitPassphrase() }
            }
        }
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.4)
        .frame(minHeight: Metrics.actionBarHeight)
    }

    /// The main action, iOS grammar: full width, filled, radius 100. Written
    /// locally rather than with `ActionBarButton` because that one reads the
    /// palette, which the decoy skin may not expose.
    private func filledButton(
        _ label: String,
        systemImage: String?,
        enabled: Bool = true,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: Spacing.s) {
                if let systemImage {
                    Image(systemName: systemImage).font(.system(size: 17, weight: .semibold))
                }
                Text(label).font(.system(size: 15.5, weight: .semibold))
            }
            .foregroundStyle(skin.onAccent)
            .frame(maxWidth: .infinity)
            .frame(height: 46)
            .background(skin.accent.opacity(enabled ? 1 : 0.45), in: Capsule())
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
    }
}
