import CryptoKit
import Foundation
import LocalAuthentication

// iOS analogue of android/.../security/KeystoreWrapper.kt.
//
// The Rust core hands us the 32-byte master key in the clear (VaultKey.exportRaw).
// We protect it at rest by AES-256-GCM *wrapping* it with a symmetric key that
// lives only in the Keychain — non-exportable in practice, and (in biometric
// mode) gated behind Face ID via SecAccessControl. The wrapped blob itself is
// not secret (it's useless without the Keychain key) and is stored in VaultPrefs.
//
// Two wrapping keys mirror Android's two Keystore aliases:
//   • "wrap"      — no auth required          (KEYSTORE_ONLY / PASSPHRASE)
//   • "wrap_bio"  — biometric required to use (KEYSTORE_BIOMETRIC)
enum Keystore {
    private static let service = "com.douxev.eggshell.wrap"
    private static let accountPlain = "wrap"
    private static let accountBio = "wrap_bio"

    private static func account(biometric: Bool) -> String {
        biometric ? accountBio : accountPlain
    }

    /// Create the wrapping key for `biometric` mode if it doesn't exist yet.
    static func ensureWrappingKey(biometric: Bool) throws {
        let acc = account(biometric: biometric)
        if Keychain.exists(service: service, account: acc) { return }
        var raw = Data(count: 32)
        raw.withUnsafeMutableBytes { _ = SecRandomCopyBytes(kSecRandomDefault, 32, $0.baseAddress!) }
        let ac = try Keychain.accessControl(biometric: biometric)
        try Keychain.set(raw, service: service, account: acc, accessControl: ac)
    }

    private static func wrappingKey(biometric: Bool, context: LAContext?, prompt: String?) throws -> SymmetricKey {
        let data = try Keychain.get(
            service: service,
            account: account(biometric: biometric),
            context: context,
            prompt: prompt
        )
        return SymmetricKey(data: data)
    }

    /// Wrap the 32-byte master key. Returns `nonce(12) || ciphertext || tag(16)`.
    static func wrap(_ master: Data, biometric: Bool, context: LAContext? = nil) throws -> Data {
        let key = try wrappingKey(biometric: biometric, context: context, prompt: nil)
        let sealed = try AES.GCM.seal(master, using: key)
        guard let combined = sealed.combined else { throw Keychain.KeychainError.unexpectedStatus(-1) }
        return combined
    }

    /// Unwrap a blob produced by `wrap`. In biometric mode this triggers Face ID.
    static func unwrap(_ blob: Data, biometric: Bool, context: LAContext? = nil, prompt: String? = nil) throws -> Data {
        let key = try wrappingKey(biometric: biometric, context: context, prompt: prompt)
        let box = try AES.GCM.SealedBox(combined: blob)
        return try AES.GCM.open(box, using: key)
    }

    /// Drop both wrapping keys (used on full wipe / mode reset).
    static func wipe() {
        Keychain.delete(service: service, account: accountPlain)
        Keychain.delete(service: service, account: accountBio)
    }
}

/// Separate, never-biometric key used to seal off-vault metadata (reminders,
/// pending-dose queue) so notifications can fire while the vault is locked.
/// Mirrors android/.../security/MetadataObfuscator.kt.
enum MetadataSeal {
    private static let service = "com.douxev.eggshell.meta"
    private static let account = "seal"

    private static func key() throws -> SymmetricKey {
        if let data = try? Keychain.get(service: service, account: account) {
            return SymmetricKey(data: data)
        }
        var raw = Data(count: 32)
        raw.withUnsafeMutableBytes { _ = SecRandomCopyBytes(kSecRandomDefault, 32, $0.baseAddress!) }
        try Keychain.set(raw, service: service, account: account) // accessibleWhenUnlocked, no bio
        return SymmetricKey(data: raw)
    }

    static func seal(_ plaintext: Data) throws -> Data {
        let sealed = try AES.GCM.seal(plaintext, using: try key())
        return sealed.combined ?? Data()
    }

    static func open(_ blob: Data) throws -> Data {
        try AES.GCM.open(try AES.GCM.SealedBox(combined: blob), using: try key())
    }

    static func wipe() { Keychain.delete(service: service, account: account) }
}
