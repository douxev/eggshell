package com.douxev.eggshell.security

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import javax.crypto.Cipher
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Suspending wrapper around AndroidX [BiometricPrompt].
 *
 * Used when the Keystore key requires user authentication
 * (`setUserAuthenticationRequired(true)`). The flow is:
 * 1. Build a partially-initialised Cipher with [KeystoreWrapper.newDecryptCipher]
 * 2. Pass it to [unlockCipher] which shows the biometric prompt
 * 3. On success, the returned Cipher can `doFinal()` to actually decrypt
 *
 * Cancellation propagates: dismissing the prompt cancels the suspending call.
 */
object BiometricKeystoreUnlock {

    enum class Availability {
        AVAILABLE,
        NOT_ENROLLED,
        NO_HARDWARE,
        UNAVAILABLE,
    }

    fun availability(activity: FragmentActivity): Availability {
        val mgr = BiometricManager.from(activity)
        return when (mgr.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> Availability.AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> Availability.NOT_ENROLLED
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> Availability.NO_HARDWARE
            else -> Availability.UNAVAILABLE
        }
    }

    /**
     * MUST run on the main thread: BiometricPrompt creates a Fragment via
     * FragmentManager, which requires the host's main thread. Callers
     * (VaultRepository) run on Dispatchers.IO, so we switch here. Symptom of
     * forgetting this switch: "IllegalStateException: Must be called from main
     * thread of fragment host" flashes for a frame before the prompt appears,
     * or "FragmentManager is already executing transactions" intermittently.
     */
    suspend fun unlockCipher(
        activity: FragmentActivity,
        cipher: Cipher,
        title: String,
        subtitle: String? = null,
        cancelLabel: String,
    ): Cipher = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            // ContextCompat covers API 26-27 where Context.mainExecutor isn't available.
            val executor = ContextCompat.getMainExecutor(activity)
            val prompt = BiometricPrompt(
                activity,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        val unlocked = result.cryptoObject?.cipher
                        if (unlocked != null) cont.resume(unlocked)
                        else cont.resumeWithException(IllegalStateException("no cipher in result"))
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        cont.resumeWithException(BiometricAuthException(errorCode, errString.toString()))
                    }
                }
            )
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .apply { if (subtitle != null) setSubtitle(subtitle) }
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButtonText(cancelLabel)
                .setConfirmationRequired(false)
                .build()
            prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
            // cancelAuthentication also needs the main thread; the cancellation
            // callback already runs on the dispatcher we suspended on, which is
            // Main here because of the surrounding withContext.
            cont.invokeOnCancellation { prompt.cancelAuthentication() }
        }
    }

    class BiometricAuthException(val errorCode: Int, message: String) : Exception(message)
}
