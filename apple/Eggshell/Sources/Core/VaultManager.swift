import Foundation
import LocalAuthentication
import TransitionCore

enum SecurityMode: String, CaseIterable, Identifiable {
    case keystoreOnly       = "KEYSTORE_ONLY"
    case keystoreBiometric  = "KEYSTORE_BIOMETRIC"
    case keystorePassphrase = "KEYSTORE_PASSPHRASE"
    case paranoid           = "PARANOID"

    var id: String { rawValue }
    var needsPassphrase: Bool { self == .keystorePassphrase || self == .paranoid }
    var needsBiometric: Bool { self == .keystoreBiometric }

    var title: String {
        switch self {
        case .keystoreOnly:       return "Keystore seul"
        case .keystoreBiometric:  return "Keystore + biométrie"
        case .keystorePassphrase: return "Keystore + phrase secrète"
        case .paranoid:           return "Paranoïaque"
        }
    }
    var blurb: String {
        switch self {
        case .keystoreOnly:       return "Déverrouillage immédiat. La clé est protégée par le matériel de l'appareil."
        case .keystoreBiometric:  return "Face ID / Touch ID à chaque ouverture. Recommandé."
        case .keystorePassphrase: return "Phrase secrète + protection matérielle. Aucune clé déchiffrable sans elle."
        case .paranoid:           return "Clé jamais stockée : redérivée de votre phrase secrète à chaque ouverture."
        }
    }
}

enum VaultError: Error {
    case notProvisioned, unknownMode, missingPassphrase, missingWrappedKey
    /// Paranoid derives the DB key from the passphrase, so it can't be applied to
    /// an existing DB (restore/mode-change) without re-keying — unsupported here.
    case paranoidRequiresRekey
}

// iOS analogue of android/.../data/VaultRepository.kt — owns vault creation,
// unlock, mode changes and wipe. An actor so the (blocking) SQLCipher open runs
// off the main thread. Returns a `VaultService` the UI then talks to.
actor VaultManager {
    private let prefs = VaultPrefs()
    private var dbPath: String { AppPaths.realDB.path }

    var isProvisioned: Bool { prefs.isProvisioned }
    var currentMode: SecurityMode? { prefs.modeRaw.flatMap(SecurityMode.init(rawValue:)) }
    var hasDecoy: Bool { prefs.hasDecoyPin }

    // MARK: Create (onboarding)

    func create(mode: SecurityMode, passphrase: String?, biometricContext: LAContext? = nil) throws -> VaultService {
        let mat = freshKdfMaterial()
        persistKdf(mat)

        let key: VaultKey
        switch mode {
        case .paranoid:
            guard let passphrase else { throw VaultError.missingPassphrase }
            key = try VaultKey.deriveFromPassphrase(
                passphrase: passphrase, salt: mat.salt,
                mCostKib: mat.mCostKib, tCost: mat.tCost, pCost: mat.pCost)
        default:
            key = VaultKey.random()
        }

        try wrapAndPersist(key: key, mode: mode, passphrase: passphrase, mat: mat, biometricContext: biometricContext)
        prefs.modeRaw = mode.rawValue
        let vault = try Vault(dbPath: dbPath, key: key)
        return VaultService(vault: vault, isDecoy: false)
    }

    // MARK: Restore (import an encrypted .transition.enc bundle)

    /// Decrypt `bundle` with `bundlePassphrase`, write the DB to disk, then
    /// re-wrap the embedded master key under the chosen local `mode`. Paranoid
    /// is unsupported here (it would require re-keying the DB).
    func restore(fromBundle bundle: Data, bundlePassphrase: String,
                 mode: SecurityMode, localPassphrase: String?,
                 biometricContext: LAContext? = nil) throws -> VaultService {
        guard mode != .paranoid else { throw VaultError.paranoidRequiresRekey }
        let imported = try importEncrypted(bundle: bundle, passphrase: bundlePassphrase, targetDbPath: dbPath)
        let key = try VaultKey.fromRaw(raw: imported.masterKey)

        let mat = freshKdfMaterial()
        persistKdf(mat)
        try wrapAndPersist(key: key, mode: mode, passphrase: localPassphrase, mat: mat, biometricContext: biometricContext)
        prefs.modeRaw = mode.rawValue
        let vault = try Vault(dbPath: dbPath, key: key)
        return VaultService(vault: vault, isDecoy: false)
    }

    // MARK: Change security mode (re-wrap the master key, no DB re-key)

    func changeMode(to newMode: SecurityMode, currentPassphrase: String?,
                    newPassphrase: String?, biometricContext: LAContext? = nil) throws {
        guard newMode != .paranoid else { throw VaultError.paranoidRequiresRekey }
        guard let oldMode = currentMode else { throw VaultError.unknownMode }
        let key = try resolveKey(mode: oldMode, passphrase: currentPassphrase, biometricContext: biometricContext)
        let mat = freshKdfMaterial()
        persistKdf(mat)
        try wrapAndPersist(key: key, mode: newMode, passphrase: newPassphrase, mat: mat, biometricContext: biometricContext)
        prefs.modeRaw = newMode.rawValue
    }

    // MARK: Wrapping helpers

    private func persistKdf(_ mat: FreshKdfMaterial) {
        prefs.kdfSalt = mat.salt
        prefs.kdfMCostKib = mat.mCostKib
        prefs.kdfTCost = mat.tCost
        prefs.kdfPCost = mat.pCost
    }

    /// Wrap `key` under `mode` and persist `prefs.wrappedKey` accordingly. For
    /// paranoid the key is not persisted (it's re-derived on unlock); the caller
    /// must have derived `key` from the passphrase.
    private func wrapAndPersist(key: VaultKey, mode: SecurityMode, passphrase: String?,
                                mat: FreshKdfMaterial, biometricContext: LAContext?) throws {
        switch mode {
        case .paranoid:
            guard passphrase != nil else { throw VaultError.missingPassphrase }
            prefs.wrappedKey = nil
        case .keystoreOnly:
            try Keystore.ensureWrappingKey(biometric: false)
            prefs.wrappedKey = try Keystore.wrap(key.exportRaw(), biometric: false)
        case .keystoreBiometric:
            try Keystore.ensureWrappingKey(biometric: true)
            prefs.wrappedKey = try Keystore.wrap(key.exportRaw(), biometric: true, context: biometricContext)
        case .keystorePassphrase:
            guard let passphrase else { throw VaultError.missingPassphrase }
            let passWrapped = try key.wrapWithPassphrase(
                passphrase: passphrase, salt: mat.salt,
                mCostKib: mat.mCostKib, tCost: mat.tCost, pCost: mat.pCost)
            try Keystore.ensureWrappingKey(biometric: false)
            prefs.wrappedKey = try Keystore.wrap(passWrapped, biometric: false)  // double-wrap
        }
    }

    // MARK: Unlock

    func unlock(passphrase: String? = nil, biometricContext: LAContext? = nil) throws -> VaultService {
        guard let mode = currentMode else { throw VaultError.unknownMode }
        let key = try resolveKey(mode: mode, passphrase: passphrase, biometricContext: biometricContext)
        try vaultVerifyKey(dbPath: dbPath, key: key)   // fast WrongKey check before swapping state
        let vault = try Vault(dbPath: dbPath, key: key)
        return VaultService(vault: vault, isDecoy: false)
    }

    private func resolveKey(mode: SecurityMode, passphrase: String?, biometricContext: LAContext?) throws -> VaultKey {
        switch mode {
        case .keystoreOnly:
            guard let wrapped = prefs.wrappedKey else { throw VaultError.missingWrappedKey }
            return try VaultKey.fromRaw(raw: Keystore.unwrap(wrapped, biometric: false))
        case .keystoreBiometric:
            guard let wrapped = prefs.wrappedKey else { throw VaultError.missingWrappedKey }
            let raw = try Keystore.unwrap(wrapped, biometric: true, context: biometricContext,
                                          prompt: "Déverrouiller eggshell")
            return try VaultKey.fromRaw(raw: raw)
        case .keystorePassphrase:
            guard let passphrase else { throw VaultError.missingPassphrase }
            guard let wrapped = prefs.wrappedKey, let salt = prefs.kdfSalt else { throw VaultError.missingWrappedKey }
            let passWrapped = try Keystore.unwrap(wrapped, biometric: false)
            return try VaultKey.unwrapWithPassphrase(
                wrapped: passWrapped, passphrase: passphrase, salt: salt,
                mCostKib: prefs.kdfMCostKib, tCost: prefs.kdfTCost, pCost: prefs.kdfPCost)
        case .paranoid:
            guard let passphrase else { throw VaultError.missingPassphrase }
            guard let salt = prefs.kdfSalt else { throw VaultError.notProvisioned }
            return try VaultKey.deriveFromPassphrase(
                passphrase: passphrase, salt: salt,
                mCostKib: prefs.kdfMCostKib, tCost: prefs.kdfTCost, pCost: prefs.kdfPCost)
        }
    }

    // MARK: Wipe

    func wipeEverything() {
        prefs.wipe()
        Keystore.wipe()
        MetadataSeal.wipe()
        AppPaths.wipeAll()
    }
}
