package com.douxev.eggshell.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import com.douxev.eggshell.BuildConfig

/**
 * Encrypts small, off-vault metadata blobs at rest with a dedicated
 * hardware-backed key, so a seized device yields opaque ciphertext instead of
 * readable scheduling/queue data.
 *
 * Used for artefacts that must be written/read while the vault — and possibly
 * the whole device — is locked (e.g. the "Pris"-while-locked dose queue). That
 * rules out the encrypted SQLCipher DB (key unavailable pre-unlock) and rules
 * out [KeystoreWrapper], whose key is `setUnlockedDeviceRequired(true)` and so
 * refuses to operate from the lock screen. This key deliberately omits that
 * flag so a notification action can seal a blob without the user unlocking the
 * device first.
 *
 * Threat model and honest limits:
 *  - The app package, APK and notification channels still reveal that this app
 *    exists and what it broadly does — that is not hideable for an installed
 *    app. What this hides is the *content and specific purpose* of the blob.
 *  - On a powered-off / imaged device the blob is undecryptable: the AES key
 *    lives in the TEE/StrongBox, is non-exportable, and never leaves hardware.
 *  - This key is independent of the vault master key. In Paranoid mode (where
 *    the vault avoids the Keystore by design) it adds a Keystore artefact, but
 *    it can only decrypt low-value reminder metadata — never the vault — so it
 *    doesn't weaken Paranoid's deniability for the actual data.
 *
 * Every operation fails soft (returns null) so a Keystore hiccup degrades the
 * feature rather than crashing a broadcast receiver.
 *
 * Build-conditional by design: in **debug** builds sealing is a no-op
 * passthrough (a readable `0:`-tagged string) so the dose queue / reminder
 * mirror stay inspectable while developing. In **release** builds the payload
 * is AES-GCM-encrypted (`1:`-tagged). The tag lets [open] decode either form,
 * and debug/release never share an install (same applicationId, no suffix), so
 * the formats can't collide on one device.
 */
@Singleton
class MetadataObfuscator @Inject constructor() {

    /** @return a tagged blob: plaintext in debug, AES-GCM base64 in release.
     *  Null only if release sealing failed (Keystore hiccup). */
    fun seal(plaintext: String): String? {
        if (BuildConfig.DEBUG) return "$TAG_PLAIN$plaintext"
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
            val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            TAG_SEALED + Base64.encodeToString(cipher.iv + ct, Base64.NO_WRAP)
        }.getOrNull()
    }

    /** Inverse of [seal]; null if the blob is absent, corrupt, or undecryptable. */
    fun open(blob: String?): String? {
        if (blob.isNullOrEmpty()) return null
        val tag = blob.first()
        val body = blob.substring(1)
        return when (tag) {
            TAG_PLAIN -> body
            TAG_SEALED -> runCatching {
                val raw = Base64.decode(body, Base64.NO_WRAP)
                require(raw.size > IV_LEN)
                val cipher = Cipher.getInstance(TRANSFORM).apply {
                    init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, raw, 0, IV_LEN))
                }
                String(cipher.doFinal(raw, IV_LEN, raw.size - IV_LEN), Charsets.UTF_8)
            }.getOrNull()
            else -> null
        }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        // StrongBox (setIsStrongBoxBacked) is API 28+; below that, TEE only.
        return generate(useStrongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
    }

    private fun generate(useStrongBox: Boolean): SecretKey = runCatching {
        val spec = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            // Intentionally NO setUnlockedDeviceRequired / no auth: the dose
            // queue must seal from a lock-screen notification action.
            .apply {
                if (useStrongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    setIsStrongBoxBacked(true)
                }
            }
            .build()
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }.getOrElse { t ->
        // StrongBox is absent on most devices; retry in the TEE.
        if (useStrongBox && isStrongBoxUnavailable(t)) generate(useStrongBox = false)
        else throw t
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val IV_LEN = 12
        private const val TAG_BITS = 128
        // One-char scheme markers so a blob self-describes how to decode it.
        private const val TAG_PLAIN = '0'
        private const val TAG_SEALED = '1'
        // Mimics the androidx.security.crypto MasterKey alias so the entry
        // blends in with a common library rather than naming this app's intent.
        private const val ALIAS = "_androidx_security_master_key_"
    }
}
