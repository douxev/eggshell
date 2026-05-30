package com.douxev.eggshell.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM key held inside the Android Keystore (StrongBox when available,
 * TEE otherwise) used to wrap the vault's master key.
 *
 * The Keystore key is **never** extractable. We never see its bytes — we only
 * call into the Keystore to encrypt and decrypt the master key blob.
 *
 * Two profiles are exposed:
 * - `getOrCreate(requireBiometric = false)` — auto-unlock, no user interaction
 * - `getOrCreate(requireBiometric = true)`  — Keystore refuses to operate
 *   unless a fresh biometric authentication has succeeded (handled by
 *   [BiometricKeystoreUnlock]).
 *
 * The wrapped output layout matches our Rust [`EncryptedBlob`] convention:
 * `iv (12 bytes) || ciphertext+tag`.
 */
class KeystoreWrapper(private val alias: String) {

    fun getOrCreate(requireBiometric: Boolean): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        ks.getKey(alias, null)?.let { return it as SecretKey }

        // Try StrongBox first when the device has a dedicated security chip
        // (Pixel 3+, Samsung Knox-class hardware). Fall back to the regular
        // TEE-backed key on StrongBoxUnavailableException.
        return runCatching { generateKey(requireBiometric, useStrongBox = true) }
            .getOrElse { t ->
                when (t) {
                    is StrongBoxUnavailableException -> generateKey(requireBiometric, useStrongBox = false)
                    else -> throw t
                }
            }
    }

    private fun generateKey(requireBiometric: Boolean, useStrongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            // Random IV chosen by Android — we receive it back via cipher.iv
            .setRandomizedEncryptionRequired(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    // Refuse to operate while the device is locked. Cheap
                    // defence-in-depth: an attacker who lifts an unlocked
                    // device can still use the key, but one that grabs a
                    // locked one cannot — even with root and Keystore access.
                    setUnlockedDeviceRequired(true)
                    if (useStrongBox) setIsStrongBoxBacked(true)
                }
                if (requireBiometric) {
                    setUserAuthenticationRequired(true)
                    // 0 = an auth must happen at each use (we'll attach the
                    // Cipher to a BiometricPrompt so the prompt unlocks the key
                    // for a single operation).
                    setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                    setInvalidatedByBiometricEnrollment(true)
                }
            }
            .build()

        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(spec)
        return gen.generateKey()
    }

    fun delete() {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(alias)
    }

    fun encrypt(secret: SecretKey, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secret)
        val ciphertext = cipher.doFinal(plaintext)
        val iv = cipher.iv
        require(iv.size == GCM_IV_LEN) { "Unexpected IV length ${iv.size}" }
        return iv + ciphertext
    }

    fun decrypt(secret: SecretKey, wrapped: ByteArray): ByteArray {
        require(wrapped.size > GCM_IV_LEN) { "wrapped blob too short" }
        val cipher = newDecryptCipher(secret, wrapped.copyOfRange(0, GCM_IV_LEN))
        return cipher.doFinal(wrapped.copyOfRange(GCM_IV_LEN, wrapped.size))
    }

    /**
     * Build a partially-initialised Cipher that the caller passes to a
     * [BiometricPrompt] for biometric-gated operations. The returned cipher
     * is in DECRYPT mode; once the prompt succeeds, call `cipher.doFinal()`
     * with the ciphertext (everything after the IV) to obtain the plaintext.
     */
    fun newDecryptCipher(secret: SecretKey, iv: ByteArray): Cipher {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher
    }

    /** Convenience: split the IV from a wrapped blob, for BiometricPrompt flows. */
    fun ivOf(wrapped: ByteArray): ByteArray = wrapped.copyOfRange(0, GCM_IV_LEN)

    fun cipherTextOf(wrapped: ByteArray): ByteArray = wrapped.copyOfRange(GCM_IV_LEN, wrapped.size)

    companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val KEY_SIZE_BITS = 256
        const val GCM_IV_LEN = 12
        const val GCM_TAG_BITS = 128
    }
}
