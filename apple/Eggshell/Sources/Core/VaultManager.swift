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
    var hasRecoverySecret: Bool { prefs.hasRecovery }

    /// Whether the app should insist on a recovery secret before letting the
    /// user get on with it.
    ///
    /// Biometric mode only, and only while none is set. That mode is the one
    /// where the vault can become unopenable through no fault of the user:
    /// enrolling a new fingerprint or face invalidates the Keychain key, and
    /// without a second wrap the data is simply gone. The other modes always
    /// retain something the user knows.
    var needsRecoverySetup: Bool {
        currentMode == .keystoreBiometric && !prefs.hasRecovery
    }

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
        // Decrypt + write the DB first. A wrong bundle passphrase throws here,
        // before any local state is touched.
        let imported = try importEncrypted(bundle: bundle, passphrase: bundlePassphrase, targetDbPath: dbPath)
        let key = try VaultKey.fromRaw(raw: imported.masterKey)

        // Drop ALL device-local security state carried over from the source
        // install BEFORE persisting the restored vault's wrap. Otherwise the
        // old access/decoy PIN hashes survive in the vault prefs and gate the
        // freshly restored vault on the next unlock — the old decoy PIN would
        // open the decoy DB instead of the restored data, and accumulated
        // failures against the stale rate-limiter could auto-wipe the just-
        // imported vault. Mirrors Android's restoreFromImportedKey, which wipes
        // prefs first. (prefs.wipe() also clears mode/kdf/wrapped_key, which we
        // immediately rewrite below.)
        prefs.wipe()
        PinRateLimiter().reset()

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
        // The recovery wrap holds the same master key, so it survives a mode
        // change on paper. It is dropped anyway: it was minted under the old
        // mode's promise, and leaving it in place would let a mode the user
        // just tightened stay openable by the secret they set under a looser
        // one. `needsRecoverySetup` will ask for a new one where it matters.
        prefs.clearRecovery()
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

    // MARK: Recovery secret

    /// Wrap the master key a second time, under a secret the user holds.
    ///
    /// Needs the raw master key, which is deliberately not kept in memory after
    /// unlock — so this re-runs the mode's normal path, i.e. one more Face ID
    /// prompt. That is not an implementation detail: minting a second way into
    /// the vault should require proving you are the person who can already open
    /// it.
    func setRecoverySecret(_ secret: String, passphrase: String? = nil,
                           biometricContext: LAContext? = nil) throws {
        guard let mode = currentMode else { throw VaultError.unknownMode }
        let trimmed = secret.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { throw VaultError.missingPassphrase }

        let key = try resolveKey(mode: mode, passphrase: passphrase,
                                 biometricContext: biometricContext)

        // Deliberately costlier Argon2id than the rest of the app.
        //
        // This is the one wrap with no Keychain layer in front of it — which is
        // exactly what lets it survive a destroyed Keychain key, and also what
        // makes it brute-forceable straight out of a preferences dump. It is
        // used twice in a vault's life (here, and in an emergency), so seconds
        // are affordable where the unlock path's sub-second budget is not.
        //
        // 128 MiB rather than the 256 the arithmetic would like: many of the
        // people this app is for are not on flagship phones, and an allocation
        // that fails is worse than a cheaper KDF. Length is the real lever
        // anyway — each extra character is worth far more than this doubling.
        let mat = freshKdfMaterial()
        let wrapped = try key.wrapWithPassphrase(
            passphrase: trimmed, salt: mat.salt,
            mCostKib: Self.recoveryMCostKib, tCost: Self.recoveryTCost, pCost: mat.pCost)

        prefs.recoveryWrapped = wrapped
        prefs.recoverySalt = mat.salt
        prefs.recoveryMCostKib = Self.recoveryMCostKib
        prefs.recoveryTCost = Self.recoveryTCost
        prefs.recoveryPCost = mat.pCost
    }

    /// Open the vault with the recovery secret instead of the primary factor.
    ///
    /// This is the path that saves someone whose Keychain key was destroyed by
    /// a new biometric enrolment: it never touches the Keychain at all.
    /// Derives the key ONCE and re-arms on the way through.
    ///
    /// The re-arm used to be a second call taking the secret again, which meant
    /// running 128 MiB Argon2id twice — several seconds of it — on the one path
    /// somebody walks in a panic. It happens here, with the key already in hand.
    func unlockWithRecovery(_ secret: String,
                            biometricContext: LAContext? = nil) throws -> VaultService {
        guard prefs.isProvisioned else { throw VaultError.notProvisioned }
        guard let wrapped = prefs.recoveryWrapped, let salt = prefs.recoverySalt else {
            throw VaultError.missingWrappedKey
        }
        let key = try VaultKey.unwrapWithPassphrase(
            wrapped: wrapped, passphrase: secret.trimmingCharacters(in: .whitespacesAndNewlines),
            salt: salt, mCostKib: prefs.recoveryMCostKib,
            tCost: prefs.recoveryTCost, pCost: prefs.recoveryPCost)
        try vaultVerifyKey(dbPath: dbPath, key: key)
        let vault = try Vault(dbPath: dbPath, key: key)

        // Best-effort, and deliberately after the vault is open: nothing below
        // may turn a successful unlock into a failure.
        if currentMode == .keystoreBiometric {
            rearmBiometricKey(with: key, biometricContext: biometricContext)
        }
        return VaultService(vault: vault, isDecoy: false)
    }

    /// Rebuild the biometric wrap around a key we already hold.
    ///
    /// Without this, someone whose Keychain key died would be asked for the
    /// recovery secret at *every* unlock forever, because nothing else ever
    /// rebuilds that key. The old entry has to go first: an invalidated key
    /// still occupies its slot, so `ensureWrappingKey` would find it and keep
    /// the dead one. `prefs.wrappedKey` is only overwritten on success, so a
    /// failure here leaves exactly the state we arrived in — unopenable by
    /// biometry, still openable by the recovery secret.
    private func rearmBiometricKey(with key: VaultKey, biometricContext: LAContext?) {
        Keystore.wipe()
        try? Keystore.ensureWrappingKey(biometric: true)
        if let rewrapped = try? Keystore.wrap(key.exportRaw(), biometric: true,
                                              context: biometricContext) {
            prefs.wrappedKey = rewrapped
        }
    }

    func clearRecoverySecret() { prefs.clearRecovery() }

    private static let recoveryMCostKib: UInt32 = 128 * 1024
    private static let recoveryTCost: UInt32 = 4

    // MARK: Wipe

    func wipeEverything() {
        prefs.wipe()
        Keystore.wipe()
        MetadataSeal.wipe()
        DecoyNotesStore.clear()
        AppPaths.wipeAll()
    }
}
