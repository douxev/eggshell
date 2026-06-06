import SwiftUI
import LocalAuthentication

@MainActor
final class UnlockViewModel: ObservableObject {
    enum Step: Equatable {
        case loading
        case pin              // decoy configured → PIN gate first
        case biometric
        case passphrase
        case working
        case throttled(Int)   // seconds remaining
    }

    @Published var step: Step = .loading
    @Published var pin = ""
    @Published var passphrase = ""
    @Published var error: String?
    /// Drives the neutral "Notes" re-skin of the lock screen. When a decoy PIN
    /// is configured the lock screen must not betray the real app (no lavender
    /// flash before the fake notes app appears), so the View reads this to swap
    /// the header + palette for a generic notes look.
    @Published private(set) var decoyConfigured = false

    private weak var app: AppState?
    private var manager: VaultManager { app!.manager }
    private var limiter = PinRateLimiter()
    private let decoy = DecoyVerifier()
    private var mode: SecurityMode = .keystoreOnly
    private var hasDecoy = false

    func bind(_ app: AppState) { self.app = app }

    func start() async {
        mode = await manager.currentMode ?? .keystoreOnly
        hasDecoy = await manager.hasDecoy
        decoyConfigured = hasDecoy
        if let ms = throttleRemainingMs(), ms > 0 {
            step = .throttled(Int(ms / 1000)); return
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

    // MARK: PIN gate (decoy)

    func appendPin(_ digit: String) {
        guard step == .pin, pin.count < 4 else { return }
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
            await beginModeAuth()
        case .decoy:
            limiter.recordSuccess()
            app?.enterDecoy()
        case .none:
            handleFailure()
        }
    }

    // MARK: Mode auth

    private func beginModeAuth() async {
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
            let ctx = try await Biometric.authenticate(reason: "Déverrouiller eggshell")
            await unlock(biometricContext: ctx)
        } catch {
            self.error = describe(error)
            step = .biometric
        }
    }

    func submitPassphrase() async {
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
            app?.unlocked(session: session)
        } catch {
            if isWrongKey(error) { handleFailure() }
            self.error = describe(error)
            // back to the appropriate input step
            step = (mode.needsPassphrase) ? .passphrase : (mode.needsBiometric ? .biometric : .passphrase)
            self.passphrase = ""   // @Published property, not the shadowing parameter
        }
    }

    private func handleFailure() {
        switch limiter.recordFailure() {
        case .wipe:
            Task { await app?.wipe() }
        case .locked(let ms):
            step = .throttled(Int(ms / 1000))
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

struct UnlockView: View {
    @EnvironmentObject private var app: AppState
    @Environment(\.palette) private var palette
    @StateObject private var vm = UnlockViewModel()

    // Neutral teal "Notes" palette, identical in spirit to DecoyNotesView /
    // android DecoyColors. When a decoy is configured we paint the whole lock
    // screen with these so the lock-screen → fake-notes transition shows no
    // colour jump and the app reads as a plain notes app's passcode gate.
    private static let decoyTeal = Color(hex: 0x006A6A)
    private static let decoySurface = Color(hex: 0xFAFDFC)
    private static let decoyContainer = Color(hex: 0xB2ECEC)
    private static let decoyOnSurface = Color(hex: 0x191C1C)
    private static let decoyOutline = Color(hex: 0xBEC9C8)
    private static let decoyError = Color(hex: 0xBA1A1A)

    private var decoySkin: Bool { vm.decoyConfigured }

    // Resolved colours: real lavender palette by default, neutral teal when a
    // decoy is configured.
    private var accent: Color { decoySkin ? Self.decoyTeal : palette.primary }
    private var onAccent: Color { decoySkin ? .white : palette.onPrimary }
    private var surfaceTop: Color { decoySkin ? Self.decoySurface : palette.surface }
    private var surfaceBottom: Color {
        decoySkin ? Self.decoySurface : palette.primaryContainer.opacity(0.5)
    }
    private var dotFilled: Color { decoySkin ? Self.decoyTeal : palette.primary }
    private var dotEmpty: Color { decoySkin ? Self.decoyOutline : palette.outlineVariant }
    private var keyBackground: Color { decoySkin ? Self.decoyContainer.opacity(0.45) : palette.surfaceContainerHigh }
    private var foreground: Color { decoySkin ? Self.decoyOnSurface : palette.onSurface }
    private var errorColor: Color { decoySkin ? Self.decoyError : palette.error }

    var body: some View {
        ZStack {
            LinearGradient(colors: [surfaceTop, surfaceBottom],
                           startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea()
            VStack(spacing: Spacing.xl) {
                Spacer()
                header
                content
                Spacer()
                if let e = vm.error { ErrorBanner(message: e).padding(.horizontal, Spacing.xl) }
            }
            .padding()
        }
        .task { vm.bind(app); await vm.start() }
    }

    private var header: some View {
        VStack(spacing: Spacing.s) {
            // Generic lock icon + "Notes" label when disguised; branded heart +
            // "eggshell" only on the real, unmasked lock screen.
            Image(systemName: decoySkin ? "lock.fill" : "heart.fill")
                .font(.system(size: 40))
                .foregroundStyle(accent)
            Text(decoySkin ? "Notes" : "eggshell")
                .font(.eggTitle)
                .foregroundStyle(foreground)
        }
    }

    @ViewBuilder
    private var content: some View {
        switch vm.step {
        case .loading, .working:
            ProgressView().tint(accent)
        case .pin:
            PinPad(pin: vm.pin,
                   filledColor: dotFilled,
                   emptyColor: dotEmpty,
                   keyBackground: keyBackground,
                   foreground: foreground,
                   onDigit: { vm.appendPin($0) },
                   onBackspace: { vm.backspacePin() })
        case .biometric:
            Button { Task { await vm.runBiometric() } } label: {
                Label("Authentifier", systemImage: "faceid").padding(.horizontal)
            }
            .glassProminentButton().tint(accent)
        case .passphrase:
            VStack(spacing: Spacing.m) {
                SecureField("Phrase secrète", text: $vm.passphrase)
                    .textFieldStyle(.roundedBorder)
                    .padding(.horizontal, Spacing.xl)
                Button { Task { await vm.submitPassphrase() } } label: {
                    Text("Déverrouiller").padding(.horizontal)
                }
                .glassProminentButton().tint(accent)
                .disabled(vm.passphrase.isEmpty)
            }
        case .throttled(let secs):
            Text("Trop de tentatives. Réessayez dans \(secs) s")
                .font(.eggCallout)
                .foregroundStyle(errorColor)
        }
    }
}

/// 4-dot display + 3×4 keypad. Colours are injected so the lock screen can
/// re-skin to the neutral decoy palette without leaking the real app's theme.
struct PinPad: View {
    let pin: String
    let filledColor: Color
    let emptyColor: Color
    let keyBackground: Color
    let foreground: Color
    let onDigit: (String) -> Void
    let onBackspace: () -> Void

    private let keys = ["1","2","3","4","5","6","7","8","9","","0","⌫"]

    var body: some View {
        VStack(spacing: Spacing.xl) {
            HStack(spacing: Spacing.l) {
                ForEach(0..<4, id: \.self) { i in
                    Circle()
                        .fill(i < pin.count ? filledColor : emptyColor)
                        .frame(width: 14, height: 14)
                }
            }
            LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 3), spacing: Spacing.l) {
                ForEach(keys, id: \.self) { key in
                    if key.isEmpty {
                        Color.clear.frame(height: 64)
                    } else if key == "⌫" {
                        Button(action: onBackspace) {
                            Image(systemName: "delete.left").font(.title2)
                                .frame(maxWidth: .infinity, minHeight: 64)
                        }.buttonStyle(.plain).foregroundStyle(foreground)
                    } else {
                        Button { onDigit(key) } label: {
                            Text(key).font(.title.weight(.medium))
                                .frame(maxWidth: .infinity, minHeight: 64)
                                .background(keyBackground, in: Circle())
                        }.buttonStyle(.plain).foregroundStyle(foreground)
                    }
                }
            }
            .padding(.horizontal, Spacing.xl)
        }
    }
}
