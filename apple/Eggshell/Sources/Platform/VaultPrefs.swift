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
        static let recoveryWrapped = "recovery_wrapped"
        static let recoverySalt = "recovery_salt"
        static let recoveryM = "recovery_m_cost_kib"
        static let recoveryT = "recovery_t_cost"
        static let recoveryP = "recovery_p_cost"
        static let pendingKdfSalt = "pending_kdf_salt"
        static let pendingKdfM = "pending_kdf_m_cost_kib"
        static let pendingKdfT = "pending_kdf_t_cost"
        static let pendingKdfP = "pending_kdf_p_cost"
        static let recoveryNeedsRenewal = "recovery_needs_renewal"
    }

    /// One set of Argon2id parameters, as the core hands them out and as they
    /// are stored — the shape both the active slot and the pending one share.
    struct Kdf {
        var salt: Data
        var mCostKib: UInt32
        var tCost: UInt32
        var pCost: UInt32
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

    // MARK: Recovery wrap
    //
    // A second, independent wrapping of the same master key under a secret the
    // user holds. Its own KDF material, deliberately costlier than the app's —
    // see VaultManager.setRecoverySecret for why. Kept apart from `kdf*` so
    // changing the security mode, which re-rolls those, cannot silently
    // invalidate the one way back in.

    var recoveryWrapped: Data? {
        get { d.data(forKey: K.recoveryWrapped) }
        nonmutating set { d.set(newValue, forKey: K.recoveryWrapped) }
    }
    var recoverySalt: Data? {
        get { d.data(forKey: K.recoverySalt) }
        nonmutating set { d.set(newValue, forKey: K.recoverySalt) }
    }
    var recoveryMCostKib: UInt32 {
        get { UInt32(d.integer(forKey: K.recoveryM)) }
        nonmutating set { d.set(Int(newValue), forKey: K.recoveryM) }
    }
    var recoveryTCost: UInt32 {
        get { UInt32(d.integer(forKey: K.recoveryT)) }
        nonmutating set { d.set(Int(newValue), forKey: K.recoveryT) }
    }
    var recoveryPCost: UInt32 {
        get { UInt32(d.integer(forKey: K.recoveryP)) }
        nonmutating set { d.set(Int(newValue), forKey: K.recoveryP) }
    }
    var hasRecovery: Bool { recoveryWrapped != nil && recoverySalt != nil }

    /// Set when a paranoid passphrase change retired the key the recovery wrap
    /// held, so the wrap had to go. Read by Réglages to say *why* the recovery
    /// key is gone and that a new one is needed; cleared once one is set.
    ///
    /// A flag rather than an inference from `hasRecovery == false`: "never had
    /// one" and "had one, and it silently stopped working" call for different
    /// sentences, and only the second is news.
    var recoveryNeedsRenewal: Bool {
        get { d.bool(forKey: K.recoveryNeedsRenewal) }
        nonmutating set {
            if newValue { d.set(true, forKey: K.recoveryNeedsRenewal) }
            else { d.removeObject(forKey: K.recoveryNeedsRenewal) }
        }
    }

    // MARK: Passphrase change
    //
    // In paranoid mode the passphrase *is* the SQLCipher key, so changing it
    // rewrites the whole vault. That cannot be made instantaneous, which means
    // there is a window in which a kill can land — and the salt is the only
    // thing that says which passphrase the file on disk now answers to.
    //
    // So the new salt is written to this slot *before* the rewrite starts, and
    // unlock tries the active salt first and this one second. Whichever opens
    // the vault is the truth: the active one means the rewrite never happened,
    // this one means it did and only the media renames are outstanding.
    // `VaultManager.resolveParanoidKey` settles that at the next unlock.

    /// The active Argon2id material, or nil before provisioning.
    var kdfMaterial: Kdf? {
        guard let salt = kdfSalt else { return nil }
        return Kdf(salt: salt, mCostKib: kdfMCostKib, tCost: kdfTCost, pCost: kdfPCost)
    }

    var pendingKdfMaterial: Kdf? {
        guard let salt = d.data(forKey: K.pendingKdfSalt) else { return nil }
        return Kdf(
            salt: salt,
            mCostKib: UInt32(d.integer(forKey: K.pendingKdfM)),
            tCost: UInt32(d.integer(forKey: K.pendingKdfT)),
            pCost: UInt32(d.integer(forKey: K.pendingKdfP)))
    }

    /// Written and flushed before the rewrite it describes begins: a write
    /// the rewrite outran would leave a vault only an unrecorded salt could
    /// open.
    ///
    /// Returns whether the slot reads back as written. The caller must not
    /// start the rewrite on `false`: a salt that did not stick is exactly the
    /// unrecorded salt this slot exists to rule out.
    @discardableResult
    func commitPendingKdf(_ kdf: Kdf) -> Bool {
        d.set(kdf.salt, forKey: K.pendingKdfSalt)
        d.set(Int(kdf.mCostKib), forKey: K.pendingKdfM)
        d.set(Int(kdf.tCost), forKey: K.pendingKdfT)
        d.set(Int(kdf.pCost), forKey: K.pendingKdfP)
        d.synchronize()
        guard let back = pendingKdfMaterial else { return false }
        return back.salt == kdf.salt && back.mCostKib == kdf.mCostKib
            && back.tCost == kdf.tCost && back.pCost == kdf.pCost
    }

    /// The rewrite landed: the pending salt becomes the active one. Flushed
    /// before the pending slot is dropped, so there is no instant at which
    /// neither slot holds the salt the database answers to.
    func promotePendingKdf() {
        guard let pending = pendingKdfMaterial else { return }
        kdfSalt = pending.salt
        kdfMCostKib = pending.mCostKib
        kdfTCost = pending.tCost
        kdfPCost = pending.pCost
        d.synchronize()
        clearPendingKdf()
    }

    /// The rewrite never happened: forget the passphrase that was never used.
    func clearPendingKdf() {
        [K.pendingKdfSalt, K.pendingKdfM, K.pendingKdfT, K.pendingKdfP]
            .forEach { d.removeObject(forKey: $0) }
        d.synchronize()
    }

    /// Replace the passphrase wrap of a KEYSTORE_PASSPHRASE vault: new salt and
    /// new wrapped key together, then flushed. They must move together — the
    /// wrapped blob can only be opened by the KEK the salt derives.
    func commitPassphraseWrap(_ kdf: Kdf, wrapped: Data) {
        kdfSalt = kdf.salt
        kdfMCostKib = kdf.mCostKib
        kdfTCost = kdf.tCost
        kdfPCost = kdf.pCost
        wrappedKey = wrapped
        d.synchronize()
    }

    /// Forget the recovery wrap. Called when the mode changes: the wrap is of
    /// the master key, and a mode change that re-keys would leave it opening
    /// nothing while still claiming to be a way in.
    func clearRecovery() {
        [K.recoveryWrapped, K.recoverySalt, K.recoveryM, K.recoveryT, K.recoveryP]
            .forEach { d.removeObject(forKey: $0) }
    }

    func wipe() {
        [K.mode, K.kdfSalt, K.kdfM, K.kdfT, K.kdfP, K.wrappedKey,
         K.accessSalt, K.accessHash, K.decoySalt, K.decoyHash,
         K.recoveryWrapped, K.recoverySalt, K.recoveryM, K.recoveryT, K.recoveryP,
         K.pendingKdfSalt, K.pendingKdfM, K.pendingKdfT, K.pendingKdfP,
         K.recoveryNeedsRenewal]
            .forEach { d.removeObject(forKey: $0) }
    }
}
