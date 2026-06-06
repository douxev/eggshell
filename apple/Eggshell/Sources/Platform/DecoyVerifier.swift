import Foundation
import TransitionCore

// Access PIN vs decoy PIN, mirroring android/.../security/DecoyVerifier.kt.
// PINs are hashed with Argon2id by piggybacking the Rust core's
// `VaultKey.deriveFromPassphrase` (its 32-byte exportRaw() is the hash). The
// decoy PIN opens a separate empty vault — plausible deniability under coercion.
//
// SECURITY: `verify(_:)` is deliberately constant-time with respect to *which*
// PIN matched. We ALWAYS derive against both the access salt and the decoy salt
// and ALWAYS run both constant-time byte comparisons — no early `return` short-
// circuits — so a timing observer cannot distinguish "right access PIN" from
// "right decoy PIN" (nor either of those from a miss) by measuring how many
// Argon2id derivations / comparisons ran before the result settled. This
// mirrors the same intent as UnlockViewModel on Android, which evaluates both
// matchers unconditionally before branching.
struct DecoyVerifier {
    private let prefs: VaultPrefs
    init(prefs: VaultPrefs = VaultPrefs()) { self.prefs = prefs }

    enum Match { case access, decoy, none }

    var isConfigured: Bool { prefs.hasDecoyPin }

    func setPair(accessPin: String, decoyPin: String) throws {
        let a = try Self.deriveSalted(pin: accessPin)
        let d = try Self.deriveSalted(pin: decoyPin)
        prefs.accessSalt = a.salt; prefs.accessHash = a.hash
        prefs.decoySalt = d.salt;  prefs.decoyHash = d.hash
    }

    func clear() {
        prefs.accessSalt = nil; prefs.accessHash = nil
        prefs.decoySalt = nil;  prefs.decoyHash = nil
    }

    /// Returns whether the typed PIN is the access PIN, the decoy PIN, or
    /// neither. Both comparisons are evaluated unconditionally (no short-circuit)
    /// and compared in constant time. Access takes precedence over decoy when —
    /// by configuration error — both would somehow match.
    func verify(_ pin: String) -> Match {
        // Evaluate BOTH derivations + comparisons every time, regardless of an
        // earlier hit. Booleans are collected first; the branch happens only
        // after all crypto work is done so the work is independent of the input.
        let accessHit = Self.matches(
            pin: pin, salt: prefs.accessSalt, expected: prefs.accessHash)
        let decoyHit = Self.matches(
            pin: pin, salt: prefs.decoySalt, expected: prefs.decoyHash)

        if accessHit { return .access }
        if decoyHit { return .decoy }
        return .none
    }

    /// Derive `pin` against `salt` and constant-time compare to `expected`.
    /// Returns false (without leaking via timing for the common path) when the
    /// PIN isn't configured or derivation fails. The comparison itself is always
    /// over the full byte length when both inputs are present.
    private static func matches(pin: String, salt: Data?, expected: Data?) -> Bool {
        guard let salt, let expected else { return false }
        guard let got = try? derive(pin: pin, salt: salt) else { return false }
        return constantTimeEquals(got, expected)
    }

    // MARK: - hashing

    private static func deriveSalted(pin: String) throws -> (salt: Data, hash: Data) {
        let mat = freshKdfMaterial()                       // random salt + constant OWASP costs
        let hash = try derive(pin: pin, salt: mat.salt)
        return (mat.salt, hash)
    }

    /// Argon2id(pin, salt) → 32-byte hash. Cost params come from
    /// `freshKdfMaterial()` which returns build-constant costs (only its salt is
    /// random, and we ignore that here, using the stored salt).
    private static func derive(pin: String, salt: Data) throws -> Data {
        let m = freshKdfMaterial()
        let key = try VaultKey.deriveFromPassphrase(
            passphrase: pin, salt: salt,
            mCostKib: m.mCostKib, tCost: m.tCost, pCost: m.pCost
        )
        return key.exportRaw()
    }

    static func constantTimeEquals(_ a: Data, _ b: Data) -> Bool {
        // Compare over the max length so the loop count doesn't reveal the
        // shorter of the two via timing. A length mismatch still forces a
        // non-zero accumulator, so it can never be reported equal.
        var diff: UInt8 = a.count == b.count ? 0 : 1
        let n = max(a.count, b.count)
        var i = 0
        while i < n {
            let ai: UInt8 = i < a.count ? a[a.startIndex + i] : 0
            let bi: UInt8 = i < b.count ? b[b.startIndex + i] : 0
            diff |= ai ^ bi
            i += 1
        }
        return diff == 0
    }
}
