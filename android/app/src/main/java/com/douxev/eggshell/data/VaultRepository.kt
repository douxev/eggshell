package com.douxev.eggshell.data

import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.crypto.Cipher
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import com.douxev.eggshell.security.BiometricKeystoreUnlock
import com.douxev.eggshell.security.KeystoreWrapper
import com.douxev.eggshell.security.VaultPrefs
import uniffi.transition.FreshKdfMaterial
import uniffi.transition.Vault
import uniffi.transition.VaultKey
import uniffi.transition.freshKdfMaterial

/**
 * Single entry point used by the UI layer.
 *
 * Owns the Vault session and the [VaultPrefs] state, and bridges between the
 * UniFFI types and the Android Keystore + BiometricPrompt machinery. ViewModels
 * call into this and never touch the FFI types directly.
 */
@Singleton
class VaultRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: VaultPrefs,
    // Injected only for wipeAll: the reminder machinery deliberately lives
    // outside the vault so it works while locked, which also means a vault
    // wipe cannot reach it without help.
    private val alarms: com.douxev.eggshell.reminders.AlarmScheduler,
    private val pendingDoses: com.douxev.eggshell.reminders.PendingDosePrefs,
) {
    // Plain classes rather than Hilt bindings, so built here from the context.
    private val reminders by lazy { com.douxev.eggshell.reminders.ReminderPrefs(context) }
    private val labReminders by lazy { com.douxev.eggshell.reminders.LabReminderPrefs(context) }

    private val keystore = KeystoreWrapper(VaultPrefs.KEYSTORE_ALIAS_NO_BIO)
    private val keystoreBio = KeystoreWrapper(VaultPrefs.KEYSTORE_ALIAS_BIO)

    @Volatile private var session: Vault? = null
        set(value) {
            field = value
            _unlocked.value = value != null
            // Withdraw, then repaint, on every transition.
            //
            // The lock direction is the one that matters, and it needs both
            // halves. Emptying the mirror alone changes nothing on screen: a
            // launcher keeps the last RemoteViews it was handed until something
            // replaces them, so the note titles would sit on the home screen
            // for hours after the app locked, straight through the screen-off
            // the user locked it with. Repainting alone would redraw them from
            // a mirror that is still full.
            //
            // The plain store rather than WidgetMirrorUpdater: the updater
            // depends on this class, so reaching for it here would be a cycle.
            if (value == null) {
                runCatching { com.douxev.eggshell.widget.WidgetContentMirror(context).clear() }
            }
            runCatching { com.douxev.eggshell.widget.WidgetRefresh.refreshAll(context) }
        }

    /**
     * Observable lock state, so the UI can never be left drawing a screen that
     * needs a vault it no longer has.
     *
     * This exists because of a real incident: a background-lock change closed
     * the session while the router had already settled on Home, and nothing
     * recomputed the route until the next onStart. The app kept rendering
     * Home, and because vault reads go through
     * `runCatching { … }.getOrDefault(emptyList())` in 48 places, every list
     * came back empty. Users reported it as having lost all their data.
     *
     * A poll at onStart cannot prevent that class of bug; only reacting to the
     * transition can. Whatever decides to lock, the worst outcome is now the
     * lock screen.
     */
    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    /**
     * True while the vault is deliberately closed for maintenance the user
     * asked for — today, a paranoid-mode passphrase change, which re-encrypts
     * the database in place and therefore cannot run with a connection open.
     *
     * The router treats "closed" as "the user must re-authenticate", which is
     * right for every other way a session ends and wrong for this one: it would
     * tear down the very screen that is driving the operation. Combined with
     * [unlocked] it says "closed, but not locked — hold".
     */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /**
     * Set when something asked to lock while [busy]. The vault was already
     * closed, so [lock] had nothing to do — but the request must still be
     * honoured, or backgrounding the phone mid-rekey would end with the vault
     * re-opened behind a lock screen the user never passed.
     */
    @Volatile private var lockRequestedWhileBusy = false

    val isInitialized: Boolean get() = prefs.mode != null
    val currentMode: VaultPrefs.Mode? get() = prefs.mode
    val isUnlocked: Boolean get() = session != null

    /**
     * Borrow the open Vault. Throws if the user hasn't unlocked yet — only
     * call from code paths that are post-unlock (the UI router enforces this).
     */
    fun requireSession(): Vault =
        session ?: error("Vault not unlocked")

    private val dbPath: String
        get() = context.filesDir.resolve("vault.db").absolutePath

    /**
     * True when there is a real database behind [dbPath].
     *
     * `Vault::open` passes `SQLITE_OPEN_CREATE`, which it must for first run —
     * but on the unlock path that turns a missing or truncated vault.db into a
     * brand new empty vault that opens happily with whatever key was supplied
     * (`verify_key` cannot reject a key against zero bytes). The user is shown
     * an empty app and told nothing, having lost everything. Unlock therefore
     * refuses to proceed unless the file is actually there.
     */
    private val vaultFileExists: Boolean
        get() = context.filesDir.resolve("vault.db").let { it.isFile && it.length() > 0L }

    // -- initialization ------------------------------------------------------

    suspend fun initializeKeystoreOnly() = withContext(Dispatchers.IO) {
        require(!isInitialized) { "vault already initialized" }
        val key = VaultKey.random()
        val raw = key.exportRaw()
        val secret = keystore.getOrCreate(requireBiometric = false)
        val wrapped = keystore.encrypt(secret, raw)
        // Open the DB before committing the mode. Committing first meant that a
        // SQLCipher failure left isInitialized true with no working vault, and
        // `require(!isInitialized)` then made onboarding impossible to repeat —
        // the app was stuck on the unlock screen for good.
        session = Vault(dbPath, key)
        prefs.commitModeAndWrappedKey(VaultPrefs.Mode.KEYSTORE_ONLY, wrapped)
    }

    suspend fun initializeKeystoreBiometric(
        activity: FragmentActivity,
        biometricCopy: BiometricCopy,
    ) = withContext(Dispatchers.IO) {
        require(!isInitialized) { "vault already initialized" }
        ensureBiometricAvailable(activity)
        val key = VaultKey.random()
        val raw = key.exportRaw()
        // Always recreate the bio key — a stale alias from a prior failed
        // attempt is the #1 cause of KeyPermanentlyInvalidatedException at
        // cipher.init time, which fires *before* any biometric prompt and
        // makes the failure look like "no prompt, just an error".
        val secret = keystoreBio.recreate(requireBiometric = true)
        // Wrapping with a biometric-bound key needs a successful auth prompt.
        val encryptCipher = Cipher.getInstance(KeystoreWrapper.TRANSFORM).apply {
            init(Cipher.ENCRYPT_MODE, secret)
        }
        val unlocked = BiometricKeystoreUnlock.unlockCipher(
            activity = activity,
            cipher = encryptCipher,
            title = biometricCopy.title,
            subtitle = biometricCopy.subtitle,
            cancelLabel = biometricCopy.cancel,
        )
        val wrapped = unlocked.iv + unlocked.doFinal(raw)
        session = Vault(dbPath, key)
        prefs.commitModeAndWrappedKey(VaultPrefs.Mode.KEYSTORE_BIOMETRIC, wrapped)
    }

    /**
     * Decrypt a biometric-wrapped blob, recovering from the one failure that
     * happens *before* any prompt is ever requested.
     *
     * `Cipher.init()` on a per-use key is supposed to succeed and defer
     * authorisation to the CryptoObject. When it throws
     * [UserNotAuthenticatedException] instead, the Keystore is demanding a
     * valid auth token up front — it is treating the key as time-bound. Users
     * hit by this see no fingerprint popup at all, because the code never got
     * far enough to ask for one, and every retry fails identically.
     *
     * So: mint a token with a plain prompt, then re-init. If that works we
     * decrypt straight away rather than asking for a second fingerprint — the
     * token we just created is exactly what the Keystore was waiting for.
     */
    private suspend fun decryptWithBiometricKey(
        wrapped: ByteArray,
        activity: FragmentActivity,
        biometricCopy: BiometricCopy,
    ): ByteArray {
        val secret = keystoreBio.getOrCreate(requireBiometric = true)
        val cipher = try {
            keystoreBio.newDecryptCipher(secret, keystoreBio.ivOf(wrapped))
        } catch (notAuthenticated: UserNotAuthenticatedException) {
            BiometricKeystoreUnlock.confirmIdentity(
                activity, biometricCopy.title, biometricCopy.subtitle, biometricCopy.cancel,
            )
            val retried = keystoreBio.newDecryptCipher(secret, keystoreBio.ivOf(wrapped))
            return retried.doFinal(keystoreBio.cipherTextOf(wrapped))
        }
        val unlocked = BiometricKeystoreUnlock.unlockCipher(
            activity, cipher, biometricCopy.title, biometricCopy.subtitle, biometricCopy.cancel,
        )
        return unlocked.doFinal(keystoreBio.cipherTextOf(wrapped))
    }

    private fun ensureBiometricAvailable(activity: FragmentActivity) {
        when (BiometricKeystoreUnlock.availability(activity)) {
            BiometricKeystoreUnlock.Availability.AVAILABLE -> Unit
            BiometricKeystoreUnlock.Availability.NOT_ENROLLED ->
                error("Aucune empreinte ou visage enregistré sur ce téléphone")
            BiometricKeystoreUnlock.Availability.NO_HARDWARE ->
                error("Ce téléphone n'a pas de capteur biométrique compatible Class 3")
            BiometricKeystoreUnlock.Availability.UNAVAILABLE ->
                error("Biométrie temporairement indisponible — réessaie après avoir déverrouillé l'écran")
        }
    }

    suspend fun initializeKeystorePassphrase(passphrase: String) = withContext(Dispatchers.IO) {
        require(!isInitialized) { "vault already initialized" }
        val kdf = freshKdfMaterial()
        val key = VaultKey.random()
        val wrappedByPass = key.wrapWithPassphrase(
            passphrase, kdf.salt, kdf.mCostKib, kdf.tCost, kdf.pCost,
        )
        val secret = keystore.getOrCreate(requireBiometric = false)
        val wrappedByKeystore = keystore.encrypt(secret, wrappedByPass)
        session = Vault(dbPath, key)
        prefs.setKdfMaterial(kdf.toPrefs())
        prefs.commitModeAndWrappedKey(VaultPrefs.Mode.KEYSTORE_PASSPHRASE, wrappedByKeystore)
    }

    suspend fun initializeParanoid(passphrase: String) = withContext(Dispatchers.IO) {
        require(!isInitialized) { "vault already initialized" }
        val kdf = freshKdfMaterial()
        val key = VaultKey.deriveFromPassphrase(
            passphrase, kdf.salt, kdf.mCostKib, kdf.tCost, kdf.pCost,
        )
        session = Vault(dbPath, key)
        prefs.setKdfMaterial(kdf.toPrefs())
        prefs.commitModeAndWrappedKey(VaultPrefs.Mode.PARANOID, null)
        // Paranoid promises that nothing usable survives without the
        // passphrase, and the widget content mirror is openable with the
        // Keystore key alone. Any opt-in that predates this mode was consented
        // to under a different promise, so it is withdrawn rather than honoured.
        runCatching {
            com.douxev.eggshell.widget.WidgetConfigPrefs(context).revokeAllContent()
            com.douxev.eggshell.widget.WidgetContentMirror(context).clear()
        }
    }

    // -- change mode ---------------------------------------------------------

    /**
     * Switch from the current security mode to `newMode` without re-keying
     * the SQLCipher database (we keep the same master key, only its wrap
     * changes).
     *
     * Paranoid-mode transitions are NOT covered by this method — they would
     * require a SQLCipher `PRAGMA rekey` plus re-encryption of every photo
     * blob with the new file key. A dedicated migration flow can handle them
     * later. We return [ChangeModeOutcome.RequiresRekey] for now.
     */
    suspend fun changeMode(
        newMode: VaultPrefs.Mode,
        currentPassphrase: String?,
        newPassphrase: String?,
        activity: FragmentActivity?,
        biometricCopy: BiometricCopy?,
    ): ChangeModeOutcome = withContext(Dispatchers.IO) {
        val current = prefs.mode
            ?: return@withContext ChangeModeOutcome.Failed("vault not initialized")
        // Not a success: nothing was changed. This arm used to swallow the
        // passphrase-change dialog whole — the UI collected an old and a new
        // passphrase for PARANOID -> PARANOID, landed here, and reported the
        // mode as updated. Changing a passphrase now goes through
        // [changePassphrase]; this only guards against a stale tap.
        if (current == newMode) return@withContext ChangeModeOutcome.AlreadyInThisMode

        if (current == VaultPrefs.Mode.PARANOID || newMode == VaultPrefs.Mode.PARANOID) {
            return@withContext ChangeModeOutcome.RequiresRekey
        }

        // Step 1: recover the raw master key from the current mode's storage.
        val rawKey: ByteArray = try {
            recoverRawKey(current, currentPassphrase, activity, biometricCopy)
        } catch (t: Throwable) {
            return@withContext ChangeModeOutcome.Failed(describe(t))
        } ?: return@withContext ChangeModeOutcome.Failed("missing credentials")

        // Step 2: persist the same key under the target mode's wrap.
        try {
            applyNewMode(newMode, rawKey, newPassphrase, activity, biometricCopy)
            ChangeModeOutcome.Success
        } catch (t: Throwable) {
            ChangeModeOutcome.Failed(describe(t))
        }
    }

    /**
     * Builds a "ClassName: message" string. Bare `t.message` is often null or
     * one cryptic word ("Tag", "init") and the user has no way to tell us which
     * exception variant they hit. Carrying the class name lets a future bug
     * report be diagnosed at a glance.
     */
    private fun describe(t: Throwable): String =
        "${t::class.java.simpleName}: ${t.message ?: "no detail"}"

    private suspend fun recoverRawKey(
        mode: VaultPrefs.Mode,
        passphrase: String?,
        activity: FragmentActivity?,
        biometricCopy: BiometricCopy?,
    ): ByteArray? {
        return when (mode) {
            VaultPrefs.Mode.KEYSTORE_ONLY -> {
                val wrapped = prefs.wrappedKey() ?: return null
                val secret = keystore.getOrCreate(requireBiometric = false)
                keystore.decrypt(secret, wrapped)
            }
            VaultPrefs.Mode.KEYSTORE_BIOMETRIC -> {
                if (activity == null || biometricCopy == null) return null
                val wrapped = prefs.wrappedKey() ?: return null
                decryptWithBiometricKey(wrapped, activity, biometricCopy)
            }
            VaultPrefs.Mode.KEYSTORE_PASSPHRASE -> {
                val pass = passphrase ?: return null
                val wrapped = prefs.wrappedKey() ?: return null
                val kdf = prefs.kdfMaterial() ?: return null
                val secret = keystore.getOrCreate(requireBiometric = false)
                val wrappedByPass = keystore.decrypt(secret, wrapped)
                VaultKey.unwrapWithPassphrase(
                    wrappedByPass, pass, kdf.salt, kdf.mCostKib, kdf.tCost, kdf.pCost,
                ).exportRaw()
            }
            VaultPrefs.Mode.PARANOID -> null
        }
    }

    private suspend fun applyNewMode(
        newMode: VaultPrefs.Mode,
        rawKey: ByteArray,
        newPassphrase: String?,
        activity: FragmentActivity?,
        biometricCopy: BiometricCopy?,
    ) {
        // CRITICAL: never delete the *previous* mode's Keystore key before the
        // new mode is fully committed. If the new wrap throws (biometric prompt
        // cancelled, KeyPermanentlyInvalidatedException, …) and we've already
        // deleted the old key, the persisted wrappedKey becomes un-decryptable
        // and the vault is unrecoverable. Delete only after prefs.mode flips.
        when (newMode) {
            VaultPrefs.Mode.KEYSTORE_ONLY -> {
                val secret = keystore.getOrCreate(requireBiometric = false)
                val wrapped = keystore.encrypt(secret, rawKey)
                prefs.setKdfMaterial(null)
                // commit before the delete below: an apply() left the new mode
                // only in memory while the old alias was already, durably, gone.
                prefs.commitModeAndWrappedKey(newMode, wrapped)
                dropRecoveryOnModeChange(newMode)
                runCatching { keystoreBio.delete() }
            }
            VaultPrefs.Mode.KEYSTORE_BIOMETRIC -> {
                require(activity != null && biometricCopy != null) { "biometric needs activity + copy" }
                ensureBiometricAvailable(activity)
                // Same recreate-from-scratch dance as initializeKeystoreBiometric
                // — avoids inheriting a stale, invalidated alias from a previous
                // half-finished mode switch.
                val secret = keystoreBio.recreate(requireBiometric = true)
                val encryptCipher = javax.crypto.Cipher.getInstance(KeystoreWrapper.TRANSFORM).apply {
                    init(javax.crypto.Cipher.ENCRYPT_MODE, secret)
                }
                val unlocked = BiometricKeystoreUnlock.unlockCipher(
                    activity, encryptCipher,
                    biometricCopy.title, biometricCopy.subtitle, biometricCopy.cancel,
                )
                val wrapped = unlocked.iv + unlocked.doFinal(rawKey)
                prefs.setKdfMaterial(null)
                prefs.commitModeAndWrappedKey(newMode, wrapped)
                runCatching { keystore.delete() }
            }
            VaultPrefs.Mode.KEYSTORE_PASSPHRASE -> {
                require(!newPassphrase.isNullOrBlank()) { "new passphrase required" }
                val kdf = freshKdfMaterial()
                val key = VaultKey.fromRaw(rawKey)
                val wrappedByPass = key.wrapWithPassphrase(
                    newPassphrase, kdf.salt, kdf.mCostKib, kdf.tCost, kdf.pCost,
                )
                val secret = keystore.getOrCreate(requireBiometric = false)
                val wrappedByKeystore = keystore.encrypt(secret, wrappedByPass)
                prefs.setKdfMaterial(kdf.toPrefs())
                prefs.commitModeAndWrappedKey(newMode, wrappedByKeystore)
                dropRecoveryOnModeChange(newMode)
                runCatching { keystoreBio.delete() }
            }
            VaultPrefs.Mode.PARANOID -> error("paranoid handled separately")
        }
    }

    sealed interface ChangeModeOutcome {
        data object Success : ChangeModeOutcome
        data object AlreadyInThisMode : ChangeModeOutcome
        data object RequiresRekey : ChangeModeOutcome
        data class Failed(val reason: String) : ChangeModeOutcome
    }

    // -- change passphrase ---------------------------------------------------

    /**
     * Replace the passphrase that opens the vault, keeping every byte of data.
     *
     * This used to not exist. The only route to it in the UI was the mode
     * picker, and picking the mode you are already in hit
     * `if (current == newMode) return Success` at the top of [changeMode] —
     * so the dialog collected an old and a new passphrase, wrote neither, and
     * reported "Mode mis à jour". The old passphrase kept working because
     * nothing had ever asked it to stop.
     *
     * The two passphrase modes need entirely different work:
     *
     * - `KEYSTORE_PASSPHRASE` wraps a random master key with a passphrase-derived
     *   KEK. A new passphrase is a new wrap of the same key: one Argon2id pass,
     *   one commit, nothing on disk moves.
     * - `PARANOID` derives the SQLCipher key from the passphrase itself. There
     *   is no wrap to replace — the database and every sealed photo, recording
     *   and note image has to be re-encrypted. See [rekeyUnderNewPassphrase].
     */
    suspend fun changePassphrase(
        currentPassphrase: String,
        newPassphrase: String,
    ): ChangePassphraseOutcome = withContext(Dispatchers.IO) {
        val mode = prefs.mode
            ?: return@withContext ChangePassphraseOutcome.Failed("vault not initialized")
        if (newPassphrase.isBlank()) {
            return@withContext ChangePassphraseOutcome.Failed("empty passphrase")
        }
        if (currentPassphrase == newPassphrase) {
            return@withContext ChangePassphraseOutcome.Unchanged
        }
        if (!vaultFileExists) {
            return@withContext ChangePassphraseOutcome.Failed("vault database missing")
        }
        when (mode) {
            VaultPrefs.Mode.KEYSTORE_PASSPHRASE ->
                rewrapUnderNewPassphrase(currentPassphrase, newPassphrase)
            VaultPrefs.Mode.PARANOID ->
                rekeyUnderNewPassphrase(currentPassphrase, newPassphrase)
            // Nothing the user typed opens these; there is no passphrase to change.
            VaultPrefs.Mode.KEYSTORE_ONLY, VaultPrefs.Mode.KEYSTORE_BIOMETRIC ->
                ChangePassphraseOutcome.NotApplicable
        }
    }

    /**
     * `KEYSTORE_PASSPHRASE`: same master key, new wrap.
     *
     * Salt and wrapped blob are committed in a single edit — half of this pair
     * on disk is a vault nobody can open.
     */
    private fun rewrapUnderNewPassphrase(
        current: String,
        new: String,
    ): ChangePassphraseOutcome {
        val wrapped = prefs.wrappedKey()
            ?: return ChangePassphraseOutcome.Failed("missing wrapped key")
        val kdf = prefs.kdfMaterial()
            ?: return ChangePassphraseOutcome.Failed("missing kdf params")
        val secret = try {
            keystore.getOrCreate(requireBiometric = false)
        } catch (t: Throwable) {
            return ChangePassphraseOutcome.Failed(describe(t))
        }
        // A failure here is the GCM tag refusing the KEK, i.e. the wrong
        // current passphrase. Anything else (a dead Keystore) would have thrown
        // above, so this arm can name the cause honestly.
        val key = try {
            VaultKey.unwrapWithPassphrase(
                keystore.decrypt(secret, wrapped), current,
                kdf.salt, kdf.mCostKib, kdf.tCost, kdf.pCost,
            )
        } catch (t: Throwable) {
            return ChangePassphraseOutcome.WrongPassphrase
        }
        return try {
            val fresh = freshKdfMaterial().toPrefs()
            val rewrapped = key.wrapWithPassphrase(
                new, fresh.salt, fresh.mCostKib, fresh.tCost, fresh.pCost,
            )
            if (prefs.commitPassphraseWrap(fresh, keystore.encrypt(secret, rewrapped))) {
                ChangePassphraseOutcome.Success(mediaLeftBehind = 0u)
            } else {
                ChangePassphraseOutcome.Failed("failed to persist the new wrap")
            }
        } catch (t: Throwable) {
            ChangePassphraseOutcome.Failed(describe(t))
        }
    }

    /**
     * `PARANOID`: re-encrypt the vault under a key derived from the new
     * passphrase.
     *
     * The passphrase is the key here, so this is a full rewrite of the database
     * plus every sealed blob. The core does the work; what this function owns
     * is the ordering that makes a kill survivable:
     *
     * 1. Verify the current passphrase — a typo must cost nothing.
     * 2. Commit the new salt to the *pending* slot, durably, **before** any
     *    byte moves. It is the only record of what the file would answer to.
     * 3. Close the session. SQLCipher rekeys in place and an open connection
     *    would carry on reading pages with the retired key.
     * 4. Rekey. If the process dies inside it, [unlock] finds the vault opens
     *    with one salt or the other and finishes or discards accordingly.
     * 5. Promote the pending salt to active, then reopen.
     *
     * `NonCancellable` because steps 2-5 are one indivisible unit from the
     * user's side. The caller is a ViewModel scope that dies with its screen,
     * and the route flips as soon as the session closes at step 3 — without
     * this, the rekey would routinely be cancelled by its own side effect.
     */
    private suspend fun rekeyUnderNewPassphrase(
        current: String,
        new: String,
    ): ChangePassphraseOutcome = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
        val kdf = prefs.kdfMaterial()
            ?: return@withContext ChangePassphraseOutcome.Failed("missing kdf params")
        val oldKey = try {
            VaultKey.deriveFromPassphrase(
                current, kdf.salt, kdf.mCostKib, kdf.tCost, kdf.pCost,
            ).also { uniffi.transition.vaultVerifyKey(dbPath, it) }
        } catch (t: Throwable) {
            return@withContext ChangePassphraseOutcome.WrongPassphrase
        }

        val fresh = freshKdfMaterial().toPrefs()
        val newKey = VaultKey.deriveFromPassphrase(
            new, fresh.salt, fresh.mCostKib, fresh.tCost, fresh.pCost,
        )
        if (!prefs.commitPendingKdf(fresh)) {
            return@withContext ChangePassphraseOutcome.Failed("failed to persist the new salt")
        }

        _busy.value = true
        lockRequestedWhileBusy = false
        session = null
        try {
            val report = uniffi.transition.rekeyVault(dbPath, oldKey, newKey)
            if (!prefs.promotePendingKdf()) {
                // The bytes have already moved and only the new passphrase opens
                // them. Refusing to record that would be the data loss, so this
                // is reported as a failure to persist rather than rolled back —
                // and unlock's pending-salt fallback still finds the way in.
                return@withContext ChangePassphraseOutcome.Failed(
                    "vault re-encrypted but the new salt could not be saved"
                )
            }
            if (!lockRequestedWhileBusy) session = Vault(dbPath, newKey)
            ChangePassphraseOutcome.Success(mediaLeftBehind = report.blobsUnreadable)
        } catch (t: Throwable) {
            // The database never moved: the staged media is unreadable litter
            // and the pending salt describes a passphrase that was never used.
            runCatching { uniffi.transition.discardPendingRekey(dbPath) }
            prefs.clearPendingKdf()
            if (!lockRequestedWhileBusy) {
                runCatching {
                    session = Vault(
                        dbPath,
                        VaultKey.deriveFromPassphrase(
                            current, kdf.salt, kdf.mCostKib, kdf.tCost, kdf.pCost,
                        ),
                    )
                }
            }
            ChangePassphraseOutcome.Failed(describe(t))
        } finally {
            _busy.value = false
        }
    }

    sealed interface ChangePassphraseOutcome {
        /**
         * [mediaLeftBehind] counts blobs that could not be decrypted with the
         * old passphrase and were therefore left where they were. Non-zero
         * means pre-existing corruption, not something this change caused —
         * but the user is told, because those files are now the only ones the
         * retired key would have opened.
         */
        data class Success(val mediaLeftBehind: UInt) : ChangePassphraseOutcome
        data object WrongPassphrase : ChangePassphraseOutcome
        data object Unchanged : ChangePassphraseOutcome
        data object NotApplicable : ChangePassphraseOutcome
        data class Failed(val reason: String) : ChangePassphraseOutcome
    }

    // -- unlock --------------------------------------------------------------

    suspend fun unlock(
        passphrase: String?,
        activity: FragmentActivity?,
        biometricCopy: BiometricCopy?,
    ): UnlockOutcome = withContext(Dispatchers.IO) {
        val mode = prefs.mode ?: return@withContext UnlockOutcome.NotInitialized
        if (!vaultFileExists) return@withContext UnlockOutcome.Failed("vault database missing")
        try {
            val key: VaultKey = when (mode) {
                VaultPrefs.Mode.KEYSTORE_ONLY -> {
                    val wrapped = prefs.wrappedKey()
                        ?: return@withContext UnlockOutcome.Failed("missing wrapped key")
                    val secret = keystore.getOrCreate(requireBiometric = false)
                    VaultKey.fromRaw(keystore.decrypt(secret, wrapped))
                }
                VaultPrefs.Mode.KEYSTORE_BIOMETRIC -> {
                    if (activity == null || biometricCopy == null) {
                        return@withContext UnlockOutcome.Failed("biometric mode needs activity + copy")
                    }
                    val wrapped = prefs.wrappedKey()
                        ?: return@withContext UnlockOutcome.Failed("missing wrapped key")
                    VaultKey.fromRaw(decryptWithBiometricKey(wrapped, activity, biometricCopy))
                }
                VaultPrefs.Mode.KEYSTORE_PASSPHRASE -> {
                    val pass = passphrase ?: return@withContext UnlockOutcome.Failed("passphrase required")
                    val wrapped = prefs.wrappedKey()
                        ?: return@withContext UnlockOutcome.Failed("missing wrapped key")
                    val kdf = prefs.kdfMaterial()
                        ?: return@withContext UnlockOutcome.Failed("missing kdf params")
                    val secret = keystore.getOrCreate(requireBiometric = false)
                    val wrappedByPass = keystore.decrypt(secret, wrapped)
                    VaultKey.unwrapWithPassphrase(
                        wrappedByPass, pass, kdf.salt, kdf.mCostKib, kdf.tCost, kdf.pCost,
                    )
                }
                VaultPrefs.Mode.PARANOID -> {
                    val pass = passphrase ?: return@withContext UnlockOutcome.Failed("passphrase required")
                    resolveParanoidKey(pass)
                        ?: return@withContext UnlockOutcome.Failed("wrong passphrase")
                }
            }
            session = Vault(dbPath, key)
            UnlockOutcome.Success
        } catch (invalidated: KeyPermanentlyInvalidatedException) {
            // A fingerprint was (re-)enrolled since the key was created, so the
            // Keystore destroyed it. Nothing retried here will ever work again.
            UnlockOutcome.KeystoreUnusable(describe(invalidated))
        } catch (notAuthenticated: UserNotAuthenticatedException) {
            // Reached only when decryptWithBiometricKey's confirm-and-retry has
            // already failed too, so the platform is asking for an auth token it
            // then refuses to honour. Same dead end from the user's side.
            UnlockOutcome.KeystoreUnusable(describe(notAuthenticated))
        } catch (cause: Throwable) {
            UnlockOutcome.Failed(describe(cause))
        }
    }

    /**
     * Derive the key that actually opens a paranoid vault, and settle any
     * passphrase change that a kill interrupted on the way through.
     *
     * There are at most two candidate salts: the active one and, while a change
     * is in flight, the pending one written by [rekeyUnderNewPassphrase] before
     * it touched a single byte. Which of them opens the database is the answer
     * to "did the re-encryption land?", and nothing else on disk can say:
     *
     * - **Active wins.** The database was never re-encrypted. Whatever media the
     *   change had already staged is unreadable litter — drop it, and forget the
     *   passphrase that never came into force.
     * - **Pending wins.** The database did move and only the media renames were
     *   outstanding. Replay them, and promote the salt to active.
     *
     * Returns null when neither opens the vault, i.e. the passphrase is wrong.
     * Nothing is settled in that case: a typo must not decide the question.
     */
    private fun resolveParanoidKey(passphrase: String): VaultKey? {
        fun candidate(kdf: VaultPrefs.Kdf): VaultKey? {
            val key = VaultKey.deriveFromPassphrase(
                passphrase, kdf.salt, kdf.mCostKib, kdf.tCost, kdf.pCost,
            )
            return key.takeIf {
                runCatching { uniffi.transition.vaultVerifyKey(dbPath, it) }.isSuccess
            }
        }
        val pending = prefs.pendingKdfMaterial()

        prefs.kdfMaterial()?.let(::candidate)?.let { key ->
            if (pending != null) {
                runCatching { uniffi.transition.discardPendingRekey(dbPath) }
                prefs.clearPendingKdf()
            }
            return key
        }
        pending?.let(::candidate)?.let { key ->
            runCatching { uniffi.transition.finishPendingRekey(dbPath) }
            prefs.promotePendingKdf()
            return key
        }
        return null
    }

    // -- recovery secret -----------------------------------------------------

    /** True when the vault has a second, non-Keystore way in. */
    val hasRecoverySecret: Boolean get() = prefs.hasRecovery

    /**
     * True when the user is in a mode whose only key lives in the Keystore
     * *and* is destroyed by a biometric re-enrollment, with nothing else able
     * to open the vault. The UI turns this into a gate the user cannot skip.
     */
    val needsRecoverySetup: Boolean
        get() = prefs.mode == VaultPrefs.Mode.KEYSTORE_BIOMETRIC && !prefs.hasRecovery

    /**
     * Wrap the master key a second time under a user-held secret.
     *
     * Needs the raw master key, which we deliberately do not keep in memory
     * after unlock — so this re-runs the mode's normal recovery path, i.e. one
     * more biometric prompt. That is not just an implementation detail: minting
     * a second way into the vault should require proving you are the person who
     * can already open it.
     */
    suspend fun setRecoverySecret(
        secret: String,
        activity: FragmentActivity?,
        biometricCopy: BiometricCopy?,
    ): RecoveryOutcome = withContext(Dispatchers.IO) {
        val mode = prefs.mode ?: return@withContext RecoveryOutcome.Failed("vault not initialized")
        if (secret.isBlank()) return@withContext RecoveryOutcome.Failed("empty secret")

        val rawKey = try {
            recoverRawKey(mode, null, activity, biometricCopy)
        } catch (t: Throwable) {
            return@withContext RecoveryOutcome.Failed(describe(t))
        } ?: return@withContext RecoveryOutcome.Failed("missing credentials")

        try {
            // Deliberately costlier Argon2id than the rest of the app.
            //
            // This is the one wrap with no Keystore layer in front of it — that
            // is what lets it survive a broken Keystore, and also what makes it
            // offline-brute-forceable straight out of a prefs dump. It is used
            // twice in a vault's life (here, and in an emergency), so seconds
            // are affordable where the unlock path's sub-second budget is not.
            //
            // 128 MiB rather than the 256 the arithmetic would like: Argon2
            // allocates natively and the release profile is panic = "abort", so
            // an allocation that fails on a 2 GB phone is a process kill, not an
            // error. Many people this app is for are not on flagships. Length is
            // the real lever anyway — each extra character is worth ~90x, where
            // this doubling is worth ~2.7x.
            val fresh = freshKdfMaterial()
            val kdf = VaultPrefs.Kdf(
                salt = fresh.salt,
                mCostKib = RECOVERY_M_COST_KIB,
                tCost = RECOVERY_T_COST,
                pCost = fresh.pCost,
            )
            val wrapped = VaultKey.fromRaw(rawKey).wrapWithPassphrase(
                secret, kdf.salt, kdf.mCostKib, kdf.tCost, kdf.pCost,
            )
            // Synchronous: the gate that forced this screen reads `hasRecovery`,
            // so it must be true on disk before we let the user past it.
            if (prefs.commitRecovery(wrapped, kdf)) RecoveryOutcome.Success
            else RecoveryOutcome.Failed("failed to persist recovery wrap")
        } catch (t: Throwable) {
            RecoveryOutcome.Failed(describe(t))
        }
    }

    /**
     * Open the vault with the recovery secret instead of the primary factor.
     *
     * This is the path that saves a user whose Keystore key was destroyed by a
     * fingerprint re-enrollment: it never touches the Keystore at all.
     */
    suspend fun unlockWithRecovery(
        secret: String,
        activity: FragmentActivity? = null,
        biometricCopy: BiometricCopy? = null,
    ): UnlockOutcome = withContext(Dispatchers.IO) {
        val mode = prefs.mode ?: return@withContext UnlockOutcome.NotInitialized
        if (!vaultFileExists) return@withContext UnlockOutcome.Failed("vault database missing")
        val wrapped = prefs.recoveryWrapped()
            ?: return@withContext UnlockOutcome.Failed("no recovery secret set")
        val kdf = prefs.recoveryKdf()
            ?: return@withContext UnlockOutcome.Failed("no recovery kdf material")
        val key = try {
            VaultKey.unwrapWithPassphrase(
                wrapped, secret, kdf.salt, kdf.mCostKib, kdf.tCost, kdf.pCost,
            )
        } catch (cause: Throwable) {
            return@withContext UnlockOutcome.Failed(describe(cause))
        }
        session = Vault(dbPath, key)

        // The vault is open; from here nothing may turn this into a failure.
        // Without the re-arm below, a user whose Keystore key died would be
        // asked for the recovery secret at *every* unlock forever, because
        // nothing else ever rebuilds that key.
        if (mode == VaultPrefs.Mode.KEYSTORE_BIOMETRIC && activity != null && biometricCopy != null) {
            runCatching { rearmBiometricKey(key.exportRaw(), activity, biometricCopy) }
        }
        UnlockOutcome.Success
    }

    /**
     * Whether the biometric-bound Keystore key can still serve an unlock.
     *
     * Silent: on a healthy per-use key `Cipher.init()` succeeds with no prompt,
     * because authorisation is deferred to the CryptoObject. Anything thrown
     * here — invalidated by a new fingerprint enrollment, or the
     * UserNotAuthenticated case — means it cannot, and re-arming is warranted.
     */
    private fun biometricKeyUnusable(wrapped: ByteArray): Boolean {
        val secret = keystoreBio.existing() ?: return true
        return runCatching { keystoreBio.newDecryptCipher(secret, keystoreBio.ivOf(wrapped)) }.isFailure
    }

    /**
     * Rebuild the biometric-bound key and re-wrap the master key under it, so
     * the next unlock is a fingerprint again rather than the recovery secret.
     *
     * Costs one prompt, and cannot be made silent with this key design: the
     * Keystore key is symmetric and `setUserAuthenticationRequired(true)` gates
     * *every* operation on it, encryption included — which is why setup shows a
     * prompt to wrap in the first place. Only an asymmetric key (public half
     * usable without auth) could wrap invisibly.
     *
     * That prompt is not purely a tax. `setInvalidatedByBiometricEnrollment`
     * destroyed the old key precisely because the set of fingerprints that can
     * open this phone changed; re-binding the vault to the new set is a
     * decision worth showing the person making it.
     *
     * Safe to fail: the previous key was already unusable, and the recovery
     * wrap is untouched, so a cancelled prompt costs nothing but a repeat of
     * the recovery unlock next time.
     */
    private suspend fun rearmBiometricKey(
        rawKey: ByteArray,
        activity: FragmentActivity,
        biometricCopy: BiometricCopy,
    ) {
        val current = prefs.wrappedKey()
        if (current != null && !biometricKeyUnusable(current)) return
        ensureBiometricAvailable(activity)
        val secret = keystoreBio.recreate(requireBiometric = true)
        val encryptCipher = Cipher.getInstance(KeystoreWrapper.TRANSFORM).apply {
            init(Cipher.ENCRYPT_MODE, secret)
        }
        val unlocked = BiometricKeystoreUnlock.unlockCipher(
            activity, encryptCipher,
            biometricCopy.title, biometricCopy.subtitle, biometricCopy.cancel,
        )
        // Only now is the old blob replaceable: if the prompt above had been
        // cancelled we would still be holding a wrappedKey that matches the
        // (dead) old alias, which is no worse than before. Overwriting earlier
        // would strand anyone whose recovery secret is also lost.
        prefs.setWrappedKey(unlocked.iv + unlocked.doFinal(rawKey))
    }

    /**
     * The recovery wrap only exists to compensate for KEYSTORE_BIOMETRIC having
     * a single point of failure. Carrying it into a mode the user deliberately
     * upgraded to would silently leave an 8-character, Keystore-free second
     * door on the same master key — a downgrade disguised as a hardening.
     */
    private fun dropRecoveryOnModeChange(newMode: VaultPrefs.Mode) {
        if (newMode != VaultPrefs.Mode.KEYSTORE_BIOMETRIC) prefs.clearRecovery()
    }

    sealed interface RecoveryOutcome {
        data object Success : RecoveryOutcome
        data class Failed(val reason: String) : RecoveryOutcome
    }

    fun lock() {
        if (_busy.value) lockRequestedWhileBusy = true
        session = null
    }

    /**
     * Reset everything — wipes the prefs, the Keystore aliases, and the DB
     * file. Used for an onboarding restart or a "delete account" flow.
     */
    suspend fun wipeAll() = withContext(Dispatchers.IO) {
        session = null
        // Silence the phone FIRST. The alarms and their off-vault mirror live
        // outside the vault precisely so reminders fire while locked — which
        // meant that after a self-destruct the phone carried on announcing
        // medication by name, to whoever had just triggered it. A wipe that
        // leaves the evidence ringing is not a wipe.
        runCatching {
            reminders.all().forEach { alarms.cancel(it.scheduleId); alarms.cancelSnooze(it.scheduleId) }
            reminders.wipe()
        }
        runCatching {
            labReminders.all().forEach { alarms.cancelLab(it.id) }
        }
        runCatching { pendingDoses.clear() }
        prefs.wipe()
        runCatching { keystore.delete() }
        runCatching { keystoreBio.delete() }
        runCatching { context.filesDir.resolve("vault.db").delete() }
        // Take the on-disk encrypted blobs with us — without their metadata
        // rows they're unrecoverable anyway, but they'd otherwise hang around
        // as orphan files indefinitely.
        runCatching { context.filesDir.resolve("photos").deleteRecursively() }
        runCatching { context.filesDir.resolve("voice").deleteRecursively() }
    }

    /**
     * Take a master key freshly imported from a backup bundle (see
     * `transition_core::vault::import_encrypted`) and persist it as the
     * Keystore-wrapped vault key. Wipes any previous mode / wrapped key
     * first so the new state is consistent.
     *
     * After this returns successfully the caller MUST trigger an app
     * restart (or `refresh()` on the AppRootViewModel) so the new mode is
     * picked up and the vault opens against the restored DB.
     *
     * The caller is responsible for zeroing `rawKey` after the call
     * returns. We copy it into the Keystore-wrapped blob and never keep a
     * reference past this method.
     */
    suspend fun restoreFromImportedKey(rawKey: ByteArray) = withContext(Dispatchers.IO) {
        require(rawKey.size == 32) { "expected 32-byte master key, got ${rawKey.size}" }
        // Drop the (now-stale) open session; the next unlock opens the restored
        // DB. Recreate the Keystore key so the wrap below is fresh.
        session = null
        // Build and commit the new state BEFORE destroying the old aliases —
        // the rule this file already states at applyNewMode, and broke here.
        // Deleting first meant that any throw in the next few lines (a Keystore
        // that refuses to generate, an encrypt refused because the screen locked
        // during the import's Argon2id + full-DB decrypt, a commit returning
        // false) left both aliases gone while prefs still pointed at a blob only
        // those aliases could open — and importEncrypted has already replaced
        // vault.db by then. The deletes are not even needed for correctness:
        // getOrCreate reuses or mints the alias, and encrypt makes a fresh IV.
        val secret = keystore.getOrCreate(requireBiometric = false)
        val wrapped = keystore.encrypt(secret, rawKey)
        // Synchronous, atomic clear-old + write-new. This MUST be durable before
        // the caller restarts the process (an async apply() would be dropped by
        // the kill, stranding the vault). Also clears any stale access/decoy PIN
        // hashes from the source install so they can't gate (or wipe) the
        // restored vault on the next unlock.
        check(prefs.commitRestoredKeystoreOnly(wrapped)) {
            "failed to persist restored vault state"
        }
        // Durable now: the stale biometric alias can go. Best-effort — a
        // leftover alias is inert, the restored vault is KEYSTORE_ONLY.
        runCatching { keystoreBio.delete() }
    }

    // -- helpers -------------------------------------------------------------

    private fun FreshKdfMaterial.toPrefs() =
        VaultPrefs.Kdf(salt = salt, mCostKib = mCostKib, tCost = tCost, pCost = pCost)

    companion object {
        // Not `const`: UInt is an inline class, which const val does not accept.
        /** See setRecoverySecret for why these differ from the app default. */
        val RECOVERY_M_COST_KIB: UInt = 128u * 1024u
        val RECOVERY_T_COST: UInt = 4u
    }

    data class BiometricCopy(
        val title: String,
        val subtitle: String?,
        val cancel: String,
    )

    sealed interface UnlockOutcome {
        data object Success : UnlockOutcome
        data object NotInitialized : UnlockOutcome
        data class Failed(val reason: String) : UnlockOutcome

        /**
         * The Keystore will not produce the key and retrying cannot change
         * that. Separated from [Failed] because the right response is not
         * "try your finger again" — it is the recovery secret, and the UI
         * takes the user straight there instead of bouncing them back onto a
         * fingerprint tile that is now guaranteed to fail.
         */
        data class KeystoreUnusable(val reason: String) : UnlockOutcome
    }
}
