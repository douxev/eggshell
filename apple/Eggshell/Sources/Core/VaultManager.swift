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
    /// The recovery secret was correct but its wrap holds a key a paranoid
    /// passphrase change retired. The wrap has been removed.
    case staleRecovery
    /// A paranoid passphrase change still has media renames outstanding; the
    /// next unlock finishes them.
    case rekeyUnfinished
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
            guard prefs.kdfSalt != nil else { throw VaultError.notProvisioned }
            guard let key = resolveParanoidKey(passphrase) else {
                throw TransitionError.WrongKey(message: "wrong passphrase")
            }
            return key
        }
    }

    /// Derive the key that actually opens a paranoid vault, and settle any
    /// passphrase change that a kill interrupted on the way through.
    ///
    /// There are at most two candidate salts: the active one and, while a
    /// change is in flight, the pending one written by `rekeyParanoid` before
    /// it touched a single byte. Which of them opens the database is the
    /// answer to "did the re-encryption land?", and nothing else on disk can
    /// say:
    ///
    /// - **Active wins.** The database was never re-encrypted. Whatever media
    ///   the change had already staged is unreadable litter — drop it, and
    ///   forget the passphrase that never came into force.
    /// - **Pending wins.** The database did move and only the media renames
    ///   were outstanding. Replay them, and promote the salt to active — but
    ///   only once the replay succeeded. Until then the pending slot is the
    ///   one record that renames are outstanding: promoting regardless would
    ///   strand the staged files for good, their originals still sealed under
    ///   the retired key. Kept, the next unlock simply replays again (the
    ///   replay is idempotent).
    ///
    /// Nil when neither opens the vault, i.e. the passphrase is wrong. Nothing
    /// is settled in that case: a typo must not decide the question.
    private func resolveParanoidKey(_ passphrase: String) -> VaultKey? {
        func candidate(_ kdf: VaultPrefs.Kdf) -> VaultKey? {
            guard let key = try? VaultKey.deriveFromPassphrase(
                passphrase: passphrase, salt: kdf.salt,
                mCostKib: kdf.mCostKib, tCost: kdf.tCost, pCost: kdf.pCost) else { return nil }
            return (try? vaultVerifyKey(dbPath: dbPath, key: key)) != nil ? key : nil
        }
        let pending = prefs.pendingKdfMaterial
        if let active = prefs.kdfMaterial, let key = candidate(active) {
            if pending != nil {
                _ = try? discardPendingRekey(dbPath: dbPath)
                prefs.clearPendingKdf()
            }
            return key
        }
        if let pending, let key = candidate(pending) {
            if (try? finishPendingRekey(dbPath: dbPath)) != nil {
                prefs.promotePendingKdf()
            }
            // The database now answers to a key the recovery wrap (if any)
            // never held. `setRecoverySecret` refuses while a pending slot
            // exists, so any wrap still here predates the change.
            _ = dropRecoveryRetiredByRekey()
            return key
        }
        return nil
    }

    /// Remove a recovery wrap whose key a paranoid rekey just retired.
    ///
    /// In paranoid mode the recovery secret wraps the passphrase-derived key
    /// itself (there is no separate master key), so once the database moves
    /// to a new key that wrap opens nothing. Left in place it would still be
    /// offered on the lock screen, still be "the second way in" in Réglages —
    /// and every correct attempt with it would count as a failure toward the
    /// auto-wipe. Re-wrapping would need the recovery secret, which this path
    /// never has; dropping it and saying so is the honest option.
    ///
    /// Returns whether there was one to drop.
    private func dropRecoveryRetiredByRekey() -> Bool {
        guard prefs.hasRecovery else { return false }
        prefs.clearRecovery()
        prefs.recoveryNeedsRenewal = true
        return true
    }

    // MARK: Change passphrase

    enum ChangePassphraseOutcome: Equatable {
        /// What a completed change still wants the user to know.
        struct Completion: Equatable {
            /// Blobs that could not be decrypted with the old passphrase and
            /// were therefore left where they were. Non-zero means pre-existing
            /// corruption, not something this change caused — but the user is
            /// told, because those files are now the only ones the retired key
            /// would have opened. Zero when the core's report was lost to a
            /// late error (see `rekeyParanoid`).
            var mediaLeftBehind: UInt32 = 0
            /// Some media renames are still outstanding; the next unlock
            /// replays them. Nothing is lost, the files are staged.
            var mediaPending = false
            /// The recovery key wrapped the retired key and was removed.
            var recoveryDropped = false
        }

        /// The vault now opens with the new passphrase.
        case success(Completion)
        case wrongPassphrase
        case unchanged
        /// Nothing the user typed opens this vault; there is no passphrase to change.
        case notApplicable
        /// Nothing changed: the vault still opens with the current passphrase.
        case failed(String)
        /// The rewrite failed and afterwards neither key could be shown to open
        /// the database. Nothing was discarded; the next unlock settles it with
        /// whichever passphrase works.
        case undetermined(String)
    }

    /// Whether the current mode has a passphrase to change at all.
    var canChangePassphrase: Bool { currentMode?.needsPassphrase == true }

    /// Whether a paranoid passphrase change removed the recovery key and none
    /// has been set since.
    var recoveryNeedsRenewal: Bool { prefs.recoveryNeedsRenewal && !prefs.hasRecovery }

    /// `KEYSTORE_PASSPHRASE`: same master key, new wrap. One Argon2id pass, one
    /// commit, nothing on disk moves. Runs with the vault open. The recovery
    /// wrap holds that same master key, so it keeps working.
    func rewrapPassphrase(current: String, new: String) -> ChangePassphraseOutcome {
        guard currentMode == .keystorePassphrase else { return .notApplicable }
        let trimmedNew = new.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedNew.isEmpty else { return .failed("phrase secrète vide") }
        if current == new { return .unchanged }
        guard let wrapped = prefs.wrappedKey, let kdf = prefs.kdfMaterial else {
            return .failed("clé enveloppée absente")
        }
        let passWrapped: Data
        do {
            passWrapped = try Keystore.unwrap(wrapped, biometric: false)
        } catch {
            return .failed(describe(error))
        }
        // A failure here is the GCM tag refusing the KEK, i.e. the wrong
        // current passphrase. Anything else would have thrown above.
        guard let key = try? VaultKey.unwrapWithPassphrase(
            wrapped: passWrapped, passphrase: current, salt: kdf.salt,
            mCostKib: kdf.mCostKib, tCost: kdf.tCost, pCost: kdf.pCost) else {
            return .wrongPassphrase
        }
        do {
            let mat = freshKdfMaterial()
            let rewrapped = try key.wrapWithPassphrase(
                passphrase: new, salt: mat.salt,
                mCostKib: mat.mCostKib, tCost: mat.tCost, pCost: mat.pCost)
            try Keystore.ensureWrappingKey(biometric: false)
            let sealed = try Keystore.wrap(rewrapped, biometric: false)
            prefs.commitPassphraseWrap(
                VaultPrefs.Kdf(salt: mat.salt, mCostKib: mat.mCostKib, tCost: mat.tCost, pCost: mat.pCost),
                wrapped: sealed)
            return .success(.init())
        } catch {
            return .failed(describe(error))
        }
    }

    /// `PARANOID`: re-encrypt the vault under a key derived from the new
    /// passphrase.
    ///
    /// The core does the work; what this owns is the ordering that makes a
    /// kill — or a failure — survivable without losing a byte:
    ///
    /// 1. Verify the current passphrase **with the session still open**. A
    ///    typo costs nothing: `closeSession` is not called, the screen the
    ///    user is on stays usable. This also settles anything an earlier,
    ///    interrupted change left behind.
    /// 2. Commit the new salt to the *pending* slot and read it back, before
    ///    any byte moves. It is the only record of what the file would answer
    ///    to.
    /// 3. `closeSession` — SQLCipher rekeys in place, and an open connection
    ///    would carry on reading pages with the retired key.
    /// 4. Rekey. If the process dies inside it, the next unlock finds the vault
    ///    opens with one salt or the other and finishes or discards accordingly.
    /// 5. Promote the pending salt to active.
    func rekeyParanoid(
        current: String,
        new: String,
        closeSession: @MainActor @Sendable () -> Void
    ) async -> ChangePassphraseOutcome {
        guard currentMode == .paranoid else { return .notApplicable }
        let trimmedNew = new.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedNew.isEmpty else { return .failed("phrase secrète vide") }
        if current == new { return .unchanged }

        // 1. Through the same resolution unlock uses, so a change that a kill
        //    interrupted earlier is finished or discarded first.
        guard let oldKey = resolveParanoidKey(current) else { return .wrongPassphrase }
        // Still pending after resolution means an earlier change's media
        // renames keep failing. Stacking a second change on top would
        // overwrite the one salt that records the first — refuse instead.
        guard prefs.pendingKdfMaterial == nil else {
            return .failed("un changement précédent n'est pas terminé. Verrouille l'app, rouvre-la, puis réessaie")
        }

        let mat = freshKdfMaterial()
        let fresh = VaultPrefs.Kdf(salt: mat.salt, mCostKib: mat.mCostKib, tCost: mat.tCost, pCost: mat.pCost)
        guard let newKey = try? VaultKey.deriveFromPassphrase(
            passphrase: new, salt: fresh.salt,
            mCostKib: fresh.mCostKib, tCost: fresh.tCost, pCost: fresh.pCost) else {
            return .failed("dérivation de la nouvelle clé impossible")
        }

        // 2. A pending salt with no rewrite behind it is harmless: the next
        //    unlock sees the active salt still opens the vault and drops it.
        guard prefs.commitPendingKdf(fresh) else {
            prefs.clearPendingKdf()
            return .failed("le nouveau sel n'a pas pu être enregistré")
        }

        // 3. Past this point the session is gone; the caller reopens it.
        await closeSession()

        // 4.
        do {
            let report = try rekeyVault(dbPath: dbPath, oldKey: oldKey, newKey: newKey)
            prefs.promotePendingKdf()
            // The access/decoy PINs are untouched — they gate the screen, not
            // the key. The recovery wrap is not: see dropRecoveryRetiredByRekey.
            let dropped = dropRecoveryRetiredByRekey()
            return .success(.init(mediaLeftBehind: report.blobsUnreadable, recoveryDropped: dropped))
        } catch {
            return settleFailedRekey(oldKey: oldKey, newKey: newKey, reason: describe(error))
        }
    }

    /// Decide what a failed `rekeyVault` actually left on disk, and only then
    /// clean up.
    ///
    /// **No data loss is the rule here, and it is absolute.** The core can
    /// throw *after* the database has been re-encrypted — the media renames
    /// come last and a rename can fail. Treating every error as "nothing
    /// moved" would then discard the staged media (the only copies sealed
    /// under the new key) and forget the pending salt (the only record of the
    /// key the database now answers to): a vault no passphrase opens. So the
    /// error message is not trusted; the database is asked, by trying each
    /// key on it:
    ///
    /// - **The new key opens it.** The rewrite landed. Replay the renames;
    ///   promote the salt only if the replay succeeded, otherwise keep the
    ///   pending slot so the next unlock replays again. Never discard here.
    /// - **The old key opens it.** Provably nothing moved. Only now is the
    ///   staging litter and the pending salt meaningless; drop both.
    /// - **Neither.** Something else is wrong (I/O, a lock held elsewhere).
    ///   Either guess destroys something on the wrong branch, so nothing is
    ///   touched: both salts and every staged file stay, and the next unlock
    ///   settles it with whichever passphrase opens the vault.
    private func settleFailedRekey(oldKey: VaultKey, newKey: VaultKey, reason: String) -> ChangePassphraseOutcome {
        if (try? vaultVerifyKey(dbPath: dbPath, key: newKey)) != nil {
            let replayed = (try? finishPendingRekey(dbPath: dbPath)) != nil
            if replayed { prefs.promotePendingKdf() }
            let dropped = dropRecoveryRetiredByRekey()
            // The core's report went down with the error, hence no count of
            // unreadable media here.
            return .success(.init(mediaPending: !replayed, recoveryDropped: dropped))
        }
        if (try? vaultVerifyKey(dbPath: dbPath, key: oldKey)) != nil {
            _ = try? discardPendingRekey(dbPath: dbPath)
            prefs.clearPendingKdf()
            return .failed(reason)
        }
        return .undetermined(reason)
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
        // A paranoid change whose media renames are still outstanding: the key
        // in hand is the new one, but the next unlock may still be the one that
        // settles the change — and settling drops any recovery wrap on the
        // assumption it predates the change. Minting one now would have it
        // dropped from under the user.
        if mode == .paranoid && prefs.pendingKdfMaterial != nil {
            throw VaultError.rekeyUnfinished
        }

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
        prefs.recoveryNeedsRenewal = false
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
        do {
            try vaultVerifyKey(dbPath: dbPath, key: key)
        } catch let error where isWrongKey(error) {
            // The secret was right — the AEAD tag accepted it above — but the
            // key it unwrapped no longer opens the database: the wrap predates
            // a paranoid passphrase change. Reporting that as a wrong secret
            // would count it toward the auto-wipe for typing the right thing,
            // and leave a dead door on the lock screen. Retire it instead.
            prefs.clearRecovery()
            prefs.recoveryNeedsRenewal = true
            throw VaultError.staleRecovery
        }
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
