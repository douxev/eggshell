import Foundation
import TransitionCore

// Access PIN vs decoy PIN, mirroring android/.../security/DecoyVerifier.kt.
// PINs are hashed with Argon2id by piggybacking the Rust core's
// `VaultKey.deriveFromPassphrase` (its 32-byte exportRaw() is the hash). The
// decoy PIN opens a separate empty vault — plausible deniability under coercion.
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

    func verify(_ pin: String) -> Match {
        if let salt = prefs.accessSalt, let hash = prefs.accessHash,
           let got = try? Self.derive(pin: pin, salt: salt),
           Self.constantTimeEquals(got, hash) {
            return .access
        }
        if let salt = prefs.decoySalt, let hash = prefs.decoyHash,
           let got = try? Self.derive(pin: pin, salt: salt),
           Self.constantTimeEquals(got, hash) {
            return .decoy
        }
        return .none
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
        guard a.count == b.count else { return false }
        var diff: UInt8 = 0
        for i in 0..<a.count { diff |= a[a.startIndex + i] ^ b[b.startIndex + i] }
        return diff == 0
    }
}
