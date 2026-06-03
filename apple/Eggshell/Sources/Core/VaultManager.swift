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

enum VaultError: Error { case notProvisioned, unknownMode, missingPassphrase, missingWrappedKey }

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
        prefs.kdfSalt = mat.salt
        prefs.kdfMCostKib = mat.mCostKib
        prefs.kdfTCost = mat.tCost
        prefs.kdfPCost = mat.pCost

        let key: VaultKey
        switch mode {
        case .paranoid:
            guard let passphrase else { throw VaultError.missingPassphrase }
            key = try VaultKey.deriveFromPassphrase(
                passphrase: passphrase, salt: mat.salt,
                mCostKib: mat.mCostKib, tCost: mat.tCost, pCost: mat.pCost)
            prefs.wrappedKey = nil   // never persisted

        case .keystoreOnly:
            let k = VaultKey.random()
            try Keystore.ensureWrappingKey(biometric: false)
            prefs.wrappedKey = try Keystore.wrap(k.exportRaw(), biometric: false)
            key = k

        case .keystoreBiometric:
            let k = VaultKey.random()
            try Keystore.ensureWrappingKey(biometric: true)
            prefs.wrappedKey = try Keystore.wrap(k.exportRaw(), biometric: true, context: biometricContext)
            key = k

        case .keystorePassphrase:
            guard let passphrase else { throw VaultError.missingPassphrase }
            let k = VaultKey.random()
            let passWrapped = try k.wrapWithPassphrase(
                passphrase: passphrase, salt: mat.salt,
                mCostKib: mat.mCostKib, tCost: mat.tCost, pCost: mat.pCost)
            try Keystore.ensureWrappingKey(biometric: false)
            prefs.wrappedKey = try Keystore.wrap(passWrapped, biometric: false)  // double-wrap
            key = k
        }

        prefs.modeRaw = mode.rawValue
        let vault = try Vault(dbPath: dbPath, key: key)
        return VaultService(vault: vault, isDecoy: false)
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
