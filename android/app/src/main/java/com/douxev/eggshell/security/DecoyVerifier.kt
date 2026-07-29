package com.douxev.eggshell.security

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.douxev.eggshell.widget.WidgetVisibility
import uniffi.transition.FreshKdfMaterial
import uniffi.transition.VaultKey
import uniffi.transition.freshKdfMaterial

/**
 * Stores and verifies the optional 4-digit access PIN and decoy PIN.
 *
 * Both PINs are Argon2id-derived hashes (we piggy-back on
 * [VaultKey.deriveFromPassphrase] which gives us a deterministic 32-byte
 * output for a (passphrase, salt, params) tuple — same trick as the vault KDF).
 *
 * Pairing rule, enforced by the UI layer:
 * - Access PIN and decoy PIN MUST be set together.
 * - They must be different 4-digit strings.
 * - Without an access PIN, the lock screen falls back to the plain passphrase
 *   prompt for password modes (no PIN gate, no decoy).
 */
@Singleton
class DecoyVerifier @Inject constructor(
    private val prefs: VaultPrefs,
    private val widgetVisibility: WidgetVisibility,
) {
    val hasAccessPin: Boolean get() = prefs.accessPin() != null
    val hasDecoyPin: Boolean get() = prefs.decoy() != null

    suspend fun setPair(accessPin: String?, decoyPin: String?) = withContext(Dispatchers.IO) {
        require((accessPin == null) == (decoyPin == null)) {
            "access PIN and decoy PIN must be set together"
        }
        if (accessPin == null) {
            prefs.setAccessPin(null)
            prefs.setDecoy(null)
            // No decoy any more → safe to expose the widget again.
            widgetVisibility.setEnabled(true)
            return@withContext
        }
        require(accessPin != decoyPin) { "access and decoy PINs must differ" }
        // Derive BOTH before persisting either. Each hashFor spends seconds in
        // Argon2id, and writing the access PIN first left a window where a
        // process death produced an access PIN with no decoy behind it: the
        // keypad would then accept the real code and refuse the cover story,
        // which is the one failure this feature must never have.
        val accessHash = hashFor(accessPin)
        val decoyHash = hashFor(decoyPin!!)
        prefs.setAccessPin(accessHash)
        prefs.setDecoy(decoyHash)
        // The widget would leak real reminder copy to whoever holds the
        // decoy PIN, so we hide it from the launcher picker entirely.
        widgetVisibility.setEnabled(false)
    }

    suspend fun accessMatches(input: String): Boolean = withContext(Dispatchers.IO) {
        val mat = prefs.accessPin() ?: return@withContext false
        verify(input, mat)
    }

    suspend fun decoyMatches(input: String): Boolean = withContext(Dispatchers.IO) {
        val mat = prefs.decoy() ?: return@withContext false
        verify(input, mat)
    }

    /** Legacy entry point kept for sites that haven't been refactored yet. */
    @Deprecated("Use decoyMatches", ReplaceWith("decoyMatches(input)"))
    suspend fun matches(input: String): Boolean = decoyMatches(input)

    private fun hashFor(pin: String): VaultPrefs.PinMaterial {
        val kdf: FreshKdfMaterial = freshKdfMaterial()
        val hash = VaultKey.deriveFromPassphrase(
            pin, kdf.salt, kdf.mCostKib, kdf.tCost, kdf.pCost,
        ).exportRaw()
        return VaultPrefs.PinMaterial(
            salt = kdf.salt,
            hash = hash,
            mCostKib = kdf.mCostKib,
            tCost = kdf.tCost,
            pCost = kdf.pCost,
        )
    }

    private fun verify(input: String, mat: VaultPrefs.PinMaterial): Boolean {
        val typed = runCatching {
            VaultKey.deriveFromPassphrase(input, mat.salt, mat.mCostKib, mat.tCost, mat.pCost)
                .exportRaw()
        }.getOrNull() ?: return false
        return constantTimeEquals(typed, mat.hash)
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }
}
