package com.douxev.eggshell.data

import android.content.Context
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.crypto.Cipher
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
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
) {
    private val keystore = KeystoreWrapper(VaultPrefs.KEYSTORE_ALIAS_NO_BIO)
    private val keystoreBio = KeystoreWrapper(VaultPrefs.KEYSTORE_ALIAS_BIO)

    @Volatile private var session: Vault? = null

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

    // -- initialization ------------------------------------------------------

    suspend fun initializeKeystoreOnly() = withContext(Dispatchers.IO) {
        require(!isInitialized) { "vault already initialized" }
        val key = VaultKey.random()
        val raw = key.exportRaw()
        val secret = keystore.getOrCreate(requireBiometric = false)
        val wrapped = keystore.encrypt(secret, raw)
        prefs.setWrappedKey(wrapped)
        prefs.mode = VaultPrefs.Mode.KEYSTORE_ONLY
        session = Vault(dbPath, key)
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
        prefs.setWrappedKey(wrapped)
        prefs.mode = VaultPrefs.Mode.KEYSTORE_BIOMETRIC
        session = Vault(dbPath, key)
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
        prefs.setKdfMaterial(kdf.toPrefs())
        prefs.setWrappedKey(wrappedByKeystore)
        prefs.mode = VaultPrefs.Mode.KEYSTORE_PASSPHRASE
        session = Vault(dbPath, key)
    }

    suspend fun initializeParanoid(passphrase: String) = withContext(Dispatchers.IO) {
        require(!isInitialized) { "vault already initialized" }
        val kdf = freshKdfMaterial()
        val key = VaultKey.deriveFromPassphrase(
            passphrase, kdf.salt, kdf.mCostKib, kdf.tCost, kdf.pCost,
        )
        prefs.setKdfMaterial(kdf.toPrefs())
        prefs.setWrappedKey(null)
        prefs.mode = VaultPrefs.Mode.PARANOID
        session = Vault(dbPath, key)
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
        if (current == newMode) return@withContext ChangeModeOutcome.Success

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
                val secret = keystoreBio.getOrCreate(requireBiometric = true)
                val cipher = keystoreBio.newDecryptCipher(secret, keystoreBio.ivOf(wrapped))
                val unlocked = BiometricKeystoreUnlock.unlockCipher(
                    activity, cipher, biometricCopy.title, biometricCopy.subtitle, biometricCopy.cancel,
                )
                unlocked.doFinal(keystoreBio.cipherTextOf(wrapped))
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
                prefs.setWrappedKey(wrapped)
                prefs.setKdfMaterial(null)
                prefs.mode = newMode
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
                prefs.setWrappedKey(wrapped)
                prefs.setKdfMaterial(null)
                prefs.mode = newMode
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
                prefs.setWrappedKey(wrappedByKeystore)
                prefs.mode = newMode
                runCatching { keystoreBio.delete() }
            }
            VaultPrefs.Mode.PARANOID -> error("paranoid handled separately")
        }
    }

    sealed interface ChangeModeOutcome {
        data object Success : ChangeModeOutcome
        data object RequiresRekey : ChangeModeOutcome
        data class Failed(val reason: String) : ChangeModeOutcome
    }

    // -- unlock --------------------------------------------------------------

    suspend fun unlock(
        passphrase: String?,
        activity: FragmentActivity?,
        biometricCopy: BiometricCopy?,
    ): UnlockOutcome = withContext(Dispatchers.IO) {
        val mode = prefs.mode ?: return@withContext UnlockOutcome.NotInitialized
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
                    val secret = keystoreBio.getOrCreate(requireBiometric = true)
                    val decryptCipher = keystoreBio.newDecryptCipher(secret, keystoreBio.ivOf(wrapped))
                    val unlocked = BiometricKeystoreUnlock.unlockCipher(
                        activity, decryptCipher,
                        biometricCopy.title, biometricCopy.subtitle, biometricCopy.cancel,
                    )
                    VaultKey.fromRaw(unlocked.doFinal(keystoreBio.cipherTextOf(wrapped)))
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
                    val kdf = prefs.kdfMaterial()
                        ?: return@withContext UnlockOutcome.Failed("missing kdf params")
                    VaultKey.deriveFromPassphrase(
                        pass, kdf.salt, kdf.mCostKib, kdf.tCost, kdf.pCost,
                    )
                }
            }
            session = Vault(dbPath, key)
            UnlockOutcome.Success
        } catch (cause: Throwable) {
            UnlockOutcome.Failed(describe(cause))
        }
    }

    fun lock() {
        session = null
    }

    /**
     * Reset everything — wipes the prefs, the Keystore aliases, and the DB
     * file. Used for an onboarding restart or a "delete account" flow.
     */
    suspend fun wipeAll() = withContext(Dispatchers.IO) {
        session = null
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
        runCatching { keystore.delete() }
        runCatching { keystoreBio.delete() }
        // Re-wrap the imported key under the local Keystore (KEYSTORE_ONLY
        // is the lowest-friction post-import default; the user can promote
        // to biometric/passphrase later via Settings → Change security mode).
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
    }

    // -- helpers -------------------------------------------------------------

    private fun FreshKdfMaterial.toPrefs() =
        VaultPrefs.Kdf(salt = salt, mCostKib = mCostKib, tCost = tCost, pCost = pCost)

    data class BiometricCopy(
        val title: String,
        val subtitle: String?,
        val cancel: String,
    )

    sealed interface UnlockOutcome {
        data object Success : UnlockOutcome
        data object NotInitialized : UnlockOutcome
        data class Failed(val reason: String) : UnlockOutcome
    }
}
