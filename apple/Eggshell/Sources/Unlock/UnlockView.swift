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
            passphrase = ""
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

    var body: some View {
        ZStack {
            LinearGradient(colors: [palette.surface, palette.primaryContainer.opacity(0.5)],
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
            Image(systemName: "heart.fill")
                .font(.system(size: 40))
                .foregroundStyle(palette.primary)
            Text("eggshell").font(.eggTitle).foregroundStyle(palette.onSurface)
        }
    }

    @ViewBuilder
    private var content: some View {
        switch vm.step {
        case .loading, .working:
            ProgressView().tint(palette.primary)
        case .pin:
            PinPad(pin: vm.pin,
                   onDigit: { vm.appendPin($0) },
                   onBackspace: { vm.backspacePin() })
        case .biometric:
            Button { Task { await vm.runBiometric() } } label: {
                Label("Authentifier", systemImage: "faceid").padding(.horizontal)
            }
            .glassProminentButton().tint(palette.primary)
        case .passphrase:
            VStack(spacing: Spacing.m) {
                SecureField("Phrase secrète", text: $vm.passphrase)
                    .textFieldStyle(.roundedBorder)
                    .padding(.horizontal, Spacing.xl)
                Button { Task { await vm.submitPassphrase() } } label: {
                    Text("Déverrouiller").padding(.horizontal)
                }
                .glassProminentButton().tint(palette.primary)
                .disabled(vm.passphrase.isEmpty)
            }
        case .throttled(let secs):
            Text("Trop de tentatives. Réessayez dans \(secs) s")
                .font(.eggCallout)
                .foregroundStyle(palette.error)
        }
    }
}

/// 4-dot display + 3×4 keypad.
struct PinPad: View {
    @Environment(\.palette) private var palette
    let pin: String
    let onDigit: (String) -> Void
    let onBackspace: () -> Void

    private let keys = ["1","2","3","4","5","6","7","8","9","","0","⌫"]

    var body: some View {
        VStack(spacing: Spacing.xl) {
            HStack(spacing: Spacing.l) {
                ForEach(0..<4, id: \.self) { i in
                    Circle()
                        .fill(i < pin.count ? palette.primary : palette.outlineVariant)
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
                        }.buttonStyle(.plain).foregroundStyle(palette.onSurface)
                    } else {
                        Button { onDigit(key) } label: {
                            Text(key).font(.title.weight(.medium))
                                .frame(maxWidth: .infinity, minHeight: 64)
                                .background(palette.surfaceContainerHigh, in: Circle())
                        }.buttonStyle(.plain).foregroundStyle(palette.onSurface)
                    }
                }
            }
            .padding(.horizontal, Spacing.xl)
        }
    }
}
