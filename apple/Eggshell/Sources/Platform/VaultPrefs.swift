import Foundation

// Non-secret vault configuration, mirroring android/.../security/VaultPrefs.kt.
// The wrapped key blob is useless without the Keychain wrapping key, and the KDF
// params / PIN hashes are not secret either, so plain UserDefaults is fine.
struct VaultPrefs {
    private let d: UserDefaults
    init(suite: String = "com.douxev.eggshell.vault") {
        d = UserDefaults(suiteName: suite) ?? .standard
    }

    private enum K {
        static let mode = "mode"
        static let kdfSalt = "kdf_salt"
        static let kdfM = "kdf_m_cost_kib"
        static let kdfT = "kdf_t_cost"
        static let kdfP = "kdf_p_cost"
        static let wrappedKey = "wrapped_key"
        static let accessSalt = "access_salt"
        static let accessHash = "access_hash"
        static let decoySalt = "decoy_salt"
        static let decoyHash = "decoy_hash"
    }

    // Whether a vault has been provisioned at all.
    var isProvisioned: Bool { d.string(forKey: K.mode) != nil }

    var modeRaw: String? {
        get { d.string(forKey: K.mode) }
        nonmutating set { d.set(newValue, forKey: K.mode) }
    }

    // KDF material (Argon2id) for passphrase/paranoid modes.
    var kdfSalt: Data? {
        get { d.data(forKey: K.kdfSalt) }
        nonmutating set { d.set(newValue, forKey: K.kdfSalt) }
    }
    var kdfMCostKib: UInt32 {
        get { UInt32(d.integer(forKey: K.kdfM)) }
        nonmutating set { d.set(Int(newValue), forKey: K.kdfM) }
    }
    var kdfTCost: UInt32 {
        get { UInt32(d.integer(forKey: K.kdfT)) }
        nonmutating set { d.set(Int(newValue), forKey: K.kdfT) }
    }
    var kdfPCost: UInt32 {
        get { UInt32(d.integer(forKey: K.kdfP)) }
        nonmutating set { d.set(Int(newValue), forKey: K.kdfP) }
    }

    var wrappedKey: Data? {
        get { d.data(forKey: K.wrappedKey) }
        nonmutating set { d.set(newValue, forKey: K.wrappedKey) }
    }

    // PIN pair (access + decoy) — Argon2id-hashed, constant-time compared.
    var accessSalt: Data? {
        get { d.data(forKey: K.accessSalt) }
        nonmutating set { d.set(newValue, forKey: K.accessSalt) }
    }
    var accessHash: Data? {
        get { d.data(forKey: K.accessHash) }
        nonmutating set { d.set(newValue, forKey: K.accessHash) }
    }
    var decoySalt: Data? {
        get { d.data(forKey: K.decoySalt) }
        nonmutating set { d.set(newValue, forKey: K.decoySalt) }
    }
    var decoyHash: Data? {
        get { d.data(forKey: K.decoyHash) }
        nonmutating set { d.set(newValue, forKey: K.decoyHash) }
    }
    var hasDecoyPin: Bool { decoyHash != nil && accessHash != nil }

    func wipe() {
        [K.mode, K.kdfSalt, K.kdfM, K.kdfT, K.kdfP, K.wrappedKey,
         K.accessSalt, K.accessHash, K.decoySalt, K.decoyHash]
            .forEach { d.removeObject(forKey: $0) }
    }
}
