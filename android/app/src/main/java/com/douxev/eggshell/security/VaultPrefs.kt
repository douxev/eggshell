package com.douxev.eggshell.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistence for the non-DB auth metadata: which security mode was chosen at
 * onboarding, the Argon2id salt + parameters (not secret), and the
 * Keystore-wrapped vault master key blob.
 *
 * Stored in plain SharedPreferences because:
 * - The salt and KDF params are not secret by design.
 * - The wrapped key is already protected by the Android Keystore (TEE/StrongBox)
 *   so storing the wrapped blob in plain SharedPrefs is fine.
 * - In Paranoid mode nothing sensitive is persisted here at all.
 */
@Singleton
class VaultPrefs @Inject constructor(@ApplicationContext context: Context) {

    private val prefs: SharedPreferences =
        com.douxev.eggshell.data.SecurePrefs.get(context, PREFS_NAME)

    enum class Mode {
        KEYSTORE_ONLY,
        KEYSTORE_BIOMETRIC,
        KEYSTORE_PASSPHRASE,
        PARANOID,
    }

    var mode: Mode?
        get() = prefs.getString(KEY_MODE, null)?.let { runCatching { Mode.valueOf(it) }.getOrNull() }
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_MODE) else putString(KEY_MODE, value.name)
            }.apply()
        }

    /** Returns the persisted KDF material or null if no passphrase mode is in use. */
    fun kdfMaterial(): Kdf? {
        val salt = prefs.getString(KEY_KDF_SALT, null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return null
        return Kdf(
            salt = salt,
            mCostKib = prefs.getInt(KEY_KDF_M, 0).toUInt(),
            tCost = prefs.getInt(KEY_KDF_T, 0).toUInt(),
            pCost = prefs.getInt(KEY_KDF_P, 0).toUInt(),
        )
    }

    fun setKdfMaterial(material: Kdf?) {
        prefs.edit().apply {
            if (material == null) {
                remove(KEY_KDF_SALT)
                remove(KEY_KDF_M)
                remove(KEY_KDF_T)
                remove(KEY_KDF_P)
            } else {
                putString(KEY_KDF_SALT, Base64.encodeToString(material.salt, Base64.NO_WRAP))
                putInt(KEY_KDF_M, material.mCostKib.toInt())
                putInt(KEY_KDF_T, material.tCost.toInt())
                putInt(KEY_KDF_P, material.pCost.toInt())
            }
        }.apply()
    }

    /**
     * Returns the Keystore-wrapped master key. Null in Paranoid mode (where
     * the key is re-derived from the passphrase at every cold start).
     */
    fun wrappedKey(): ByteArray? =
        prefs.getString(KEY_WRAPPED, null)?.let { Base64.decode(it, Base64.NO_WRAP) }

    fun setWrappedKey(wrapped: ByteArray?) {
        prefs.edit().apply {
            if (wrapped == null) remove(KEY_WRAPPED)
            else putString(KEY_WRAPPED, Base64.encodeToString(wrapped, Base64.NO_WRAP))
        }.apply()
    }

    // -- Recovery secret ------------------------------------------------------

    /**
     * Second, independent wrap of the vault master key.
     *
     * `KEYSTORE_BIOMETRIC` otherwise has exactly one way in, and the Keystore
     * key behind it is destroyed outright the moment a new fingerprint is
     * enrolled in Android's settings (`setInvalidatedByBiometricEnrollment`),
     * or when an OEM update re-provisions the biometric templates. With a
     * single wrap that is unrecoverable data loss — the DB, the photos and the
     * voice notes stay on disk, encrypted, forever.
     *
     * This wrap is derived from a user-held secret via Argon2id, exactly like
     * `KEYSTORE_PASSPHRASE` mode, and deliberately does **not** sit under a
     * Keystore layer: surviving a broken Keystore is its entire purpose, so
     * depending on one would reintroduce the single point of failure it exists
     * to remove. Offline brute-force resistance therefore rests on Argon2id
     * and on the secret's own strength.
     */
    fun recoveryWrapped(): ByteArray? =
        prefs.getString(KEY_RECOVERY_WRAPPED, null)?.let { Base64.decode(it, Base64.NO_WRAP) }

    fun recoveryKdf(): Kdf? {
        val salt = prefs.getString(KEY_RECOVERY_SALT, null)
            ?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return null
        return Kdf(
            salt = salt,
            mCostKib = prefs.getInt(KEY_RECOVERY_M, 0).toUInt(),
            tCost = prefs.getInt(KEY_RECOVERY_T, 0).toUInt(),
            pCost = prefs.getInt(KEY_RECOVERY_P, 0).toUInt(),
        )
    }

    /** True once a usable recovery wrap *and* its KDF material are both stored. */
    val hasRecovery: Boolean
        get() = prefs.contains(KEY_RECOVERY_WRAPPED) && prefs.contains(KEY_RECOVERY_SALT)

    /**
     * Persist both halves together with `commit`, not `apply`.
     *
     * A half-written recovery wrap is worse than none: the gate that forces
     * this setup keys off [hasRecovery], so an interrupted async write would
     * let the user through while leaving them with a secret that cannot
     * actually open anything.
     */
    fun commitRecovery(wrapped: ByteArray, kdf: Kdf): Boolean =
        prefs.edit()
            .putString(KEY_RECOVERY_WRAPPED, Base64.encodeToString(wrapped, Base64.NO_WRAP))
            .putString(KEY_RECOVERY_SALT, Base64.encodeToString(kdf.salt, Base64.NO_WRAP))
            .putInt(KEY_RECOVERY_M, kdf.mCostKib.toInt())
            .putInt(KEY_RECOVERY_T, kdf.tCost.toInt())
            .putInt(KEY_RECOVERY_P, kdf.pCost.toInt())
            .commit()

    fun clearRecovery() {
        prefs.edit()
            .remove(KEY_RECOVERY_WRAPPED)
            .remove(KEY_RECOVERY_SALT)
            .remove(KEY_RECOVERY_M)
            .remove(KEY_RECOVERY_T)
            .remove(KEY_RECOVERY_P)
            .apply()
    }

    /** Erase everything — used at logout / vault reset / failed onboarding. */
    fun wipe() {
        prefs.edit().clear().apply()
    }

    /**
     * Atomically wipe ALL prior auth state (mode, KDF, wrapped key, AND the
     * access/decoy PIN hashes) and persist the post-restore KEYSTORE_ONLY
     * state, **synchronously** (`commit`, not `apply`).
     *
     * The backup-restore flow kills the process right after this returns to
     * force a clean relaunch; `apply()` queues the write on a background
     * thread that the kill would abort, so the new mode/wrapped-key could be
     * lost — leaving the relaunched app either trying the old key against the
     * imported DB ("file is not in database") or falling back to onboarding
     * over the restored data. `commit()` guarantees the bytes hit disk first.
     * Doing it in a single edit() also avoids a half-wiped intermediate state.
     */
    fun commitRestoredKeystoreOnly(wrapped: ByteArray): Boolean =
        prefs.edit()
            .clear()
            .putString(KEY_WRAPPED, Base64.encodeToString(wrapped, Base64.NO_WRAP))
            .putString(KEY_MODE, Mode.KEYSTORE_ONLY.name)
            .commit()

    // -- Access + Decoy PINs (Phase 7) ---------------------------------------

    /** Persisted material for a 4-digit PIN (access OR decoy). */
    data class PinMaterial(
        val salt: ByteArray,
        val hash: ByteArray,
        val mCostKib: UInt,
        val tCost: UInt,
        val pCost: UInt,
    )

    fun decoy(): PinMaterial? = readPin(KEY_DECOY_SALT, KEY_DECOY_HASH, KEY_DECOY_M, KEY_DECOY_T, KEY_DECOY_P)
    fun setDecoy(material: PinMaterial?) =
        writePin(material, KEY_DECOY_SALT, KEY_DECOY_HASH, KEY_DECOY_M, KEY_DECOY_T, KEY_DECOY_P)

    /**
     * The "access PIN" is the 4-digit gate that, when entered correctly,
     * reveals the passphrase prompt. It only matters when a decoy is also
     * set — without a decoy, the user types their passphrase directly.
     */
    fun accessPin(): PinMaterial? = readPin(KEY_ACCESS_SALT, KEY_ACCESS_HASH, KEY_ACCESS_M, KEY_ACCESS_T, KEY_ACCESS_P)
    fun setAccessPin(material: PinMaterial?) =
        writePin(material, KEY_ACCESS_SALT, KEY_ACCESS_HASH, KEY_ACCESS_M, KEY_ACCESS_T, KEY_ACCESS_P)

    private fun readPin(saltKey: String, hashKey: String, mKey: String, tKey: String, pKey: String): PinMaterial? {
        val salt = prefs.getString(saltKey, null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return null
        val hash = prefs.getString(hashKey, null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return null
        return PinMaterial(
            salt = salt,
            hash = hash,
            mCostKib = prefs.getInt(mKey, 0).toUInt(),
            tCost = prefs.getInt(tKey, 0).toUInt(),
            pCost = prefs.getInt(pKey, 0).toUInt(),
        )
    }

    private fun writePin(material: PinMaterial?, saltKey: String, hashKey: String, mKey: String, tKey: String, pKey: String) {
        prefs.edit().apply {
            if (material == null) {
                remove(saltKey); remove(hashKey); remove(mKey); remove(tKey); remove(pKey)
            } else {
                putString(saltKey, Base64.encodeToString(material.salt, Base64.NO_WRAP))
                putString(hashKey, Base64.encodeToString(material.hash, Base64.NO_WRAP))
                putInt(mKey, material.mCostKib.toInt())
                putInt(tKey, material.tCost.toInt())
                putInt(pKey, material.pCost.toInt())
            }
        }.apply()
    }

    data class Kdf(
        val salt: ByteArray,
        val mCostKib: UInt,
        val tCost: UInt,
        val pCost: UInt,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Kdf) return false
            return salt.contentEquals(other.salt) &&
                mCostKib == other.mCostKib &&
                tCost == other.tCost &&
                pCost == other.pCost
        }

        override fun hashCode(): Int =
            salt.contentHashCode() * 31 + mCostKib.hashCode() * 17 +
                tCost.hashCode() * 13 + pCost.hashCode()
    }

    companion object {
        private const val PREFS_NAME = "transition_vault_prefs"
        private const val KEY_MODE = "mode"
        private const val KEY_KDF_SALT = "kdf_salt"
        private const val KEY_KDF_M = "kdf_m_cost_kib"
        private const val KEY_KDF_T = "kdf_t_cost"
        private const val KEY_KDF_P = "kdf_p_cost"
        private const val KEY_WRAPPED = "wrapped_key"

        private const val KEY_RECOVERY_WRAPPED = "recovery_wrapped"
        private const val KEY_RECOVERY_SALT = "recovery_salt"
        private const val KEY_RECOVERY_M = "recovery_m"
        private const val KEY_RECOVERY_T = "recovery_t"
        private const val KEY_RECOVERY_P = "recovery_p"

        private const val KEY_DECOY_SALT = "decoy_salt"
        private const val KEY_DECOY_HASH = "decoy_hash"
        private const val KEY_DECOY_M = "decoy_m"
        private const val KEY_DECOY_T = "decoy_t"
        private const val KEY_DECOY_P = "decoy_p"

        private const val KEY_ACCESS_SALT = "access_salt"
        private const val KEY_ACCESS_HASH = "access_hash"
        private const val KEY_ACCESS_M = "access_m"
        private const val KEY_ACCESS_T = "access_t"
        private const val KEY_ACCESS_P = "access_p"

        const val KEYSTORE_ALIAS_NO_BIO = "transition_vault_key"
        const val KEYSTORE_ALIAS_BIO = "transition_vault_key_bio"
    }
}
