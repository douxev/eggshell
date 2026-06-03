import Foundation
import LocalAuthentication

// Face ID / Touch ID gate. The returned, already-evaluated LAContext is handed
// to Keychain reads (kSecUseAuthenticationContext) so the wrapping key unwrap
// doesn't trigger a *second* prompt.
enum Biometric {
    enum BiometricError: Error { case unavailable, cancelled, failed }

    static var isAvailable: Bool {
        var err: NSError?
        return LAContext().canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &err)
    }

    static var kind: LABiometryType {
        let ctx = LAContext()
        _ = ctx.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: nil)
        return ctx.biometryType   // .faceID / .touchID / .none
    }

    /// Evaluate biometrics and return the authenticated context.
    static func authenticate(reason: String) async throws -> LAContext {
        let ctx = LAContext()
        ctx.localizedFallbackTitle = ""          // hide "Enter Password" — vault has its own gate
        var err: NSError?
        guard ctx.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &err) else {
            throw BiometricError.unavailable
        }
        do {
            let ok = try await ctx.evaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, localizedReason: reason)
            guard ok else { throw BiometricError.failed }
            return ctx
        } catch let e as LAError {
            switch e.code {
            case .userCancel, .appCancel, .systemCancel: throw BiometricError.cancelled
            default: throw BiometricError.failed
            }
        }
    }
}
