package com.douxev.eggshell.security

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.withResumed
import javax.crypto.Cipher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
     * Authorise one Keystore operation: the prompt carries the Cipher, and the
     * returned Cipher is the authorised one to `doFinal()` with.
     */
    suspend fun unlockCipher(
        activity: FragmentActivity,
        cipher: Cipher,
        title: String,
        subtitle: String? = null,
        cancelLabel: String,
    ): Cipher = runPrompt(
        activity = activity,
        crypto = BiometricPrompt.CryptoObject(cipher),
        title = title,
        subtitle = subtitle,
        cancelLabel = cancelLabel,
    ) ?: throw IllegalStateException("no cipher in result")

    /**
     * Prompt with **no** CryptoObject, purely to mint a fresh hardware auth
     * token — it authorises nothing by itself.
     *
     * This is the recovery path for a `Cipher.init()` that threw
     * [android.security.keystore.UserNotAuthenticatedException]: that exception
     * means the Keystore wanted a valid auth token *up front*, i.e. it is
     * enforcing the key as time-bound rather than per-operation, whatever we
     * asked for at generation time. Re-running `init()` after this succeeds
     * where the first attempt failed.
     */
    suspend fun confirmIdentity(
        activity: FragmentActivity,
        title: String,
        subtitle: String? = null,
        cancelLabel: String,
    ) {
        runPrompt(
            activity = activity,
            crypto = null,
            title = title,
            subtitle = subtitle,
            cancelLabel = cancelLabel,
        )
    }

    /**
     * MUST run on the main thread: BiometricPrompt creates a Fragment via
     * FragmentManager, which requires the host's main thread. Callers
     * (VaultRepository) run on Dispatchers.IO, so we switch here. Symptom of
     * forgetting this switch: "IllegalStateException: Must be called from main
     * thread of fragment host" flashes for a frame before the prompt appears,
     * or "FragmentManager is already executing transactions" intermittently.
     *
     * Returns the authorised Cipher when [crypto] was supplied, null otherwise.
     * Never resolves to "suspended forever": see [awaitPromptOrGiveUp].
     */
    private suspend fun runPrompt(
        activity: FragmentActivity,
        crypto: BiometricPrompt.CryptoObject?,
        title: String,
        subtitle: String?,
        cancelLabel: String,
    ): Cipher? = withContext(Dispatchers.Main) {
        // `authenticate()` is silently discarded when the host FragmentManager
        // has saved its state — and `FragmentManager.isStateSaved()` is
        // `mStateSaved || mStopped`, so merely *not being started yet* is
        // enough. Callers reach this after a Dispatchers.IO hop plus Keystore
        // work, which is easily long enough for the activity to have moved,
        // so wait for a host that can actually host a dialog before asking
        // for one. Throws LifecycleDestroyedException if the activity is gone,
        // which the caller reports as an ordinary failed unlock.
        activity.lifecycle.withResumed { }

        coroutineScope {
            val outcome = CompletableDeferred<Cipher?>()
            // ContextCompat covers API 26-27 where Context.mainExecutor isn't available.
            val executor = ContextCompat.getMainExecutor(activity)
            val prompt = BiometricPrompt(
                activity,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (crypto == null) {
                            // Identity-confirmation only — there is no cipher to
                            // hand back, and that is not a failure.
                            outcome.complete(null)
                            return
                        }
                        val unlocked = result.cryptoObject?.cipher
                        if (unlocked != null) outcome.complete(unlocked)
                        else outcome.completeExceptionally(IllegalStateException("no cipher in result"))
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        outcome.completeExceptionally(
                            BiometricAuthException(errorCode, errString.toString())
                        )
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
            if (crypto != null) prompt.authenticate(info, crypto) else prompt.authenticate(info)

            val watchdog = launch { awaitPromptOrGiveUp(activity, prompt, outcome) }
            try {
                outcome.await()
            } finally {
                watchdog.cancel()
                // Only on cancellation: a completed outcome means the prompt
                // already dismissed itself (success, error, or the watchdog,
                // which cancels on its own way out). cancelAuthentication also
                // needs the main thread, which the surrounding withContext
                // guarantees we are still on.
                if (!outcome.isCompleted) prompt.cancelAuthentication()
            }
        }
    }

    /**
     * Fails the pending prompt when it never actually appeared.
     *
     * androidx.biometric has several paths that drop `authenticate()` on the
     * floor — a `Log.e` and a bare `return`, with no callback ever delivered:
     * a null client FragmentManager, `FragmentManager.isStateSaved()`, a null
     * BiometricFragment context, and a `BiometricViewModel` still flagged as
     * showing a previous prompt. It can also swallow the *result* ("… not sent
     * to client. Client is not awaiting a result."). All of them are still
     * present in 1.4.0-alpha07. Any one leaves the caller suspended forever,
     * which on the lock screen reads as "no fingerprint popup ever came and
     * the app is stuck on a progress bar with no way out".
     *
     * The tell that no prompt is up is that our own window still holds input
     * focus: the API 28+ system prompt and the library's own pre-28 dialog
     * both take focus away. So once the prompt is genuinely showing this goes
     * quiet indefinitely — a user who takes a minute to place their finger is
     * never interrupted. Only a host that is resumed *and* focused for several
     * consecutive polls, with nothing having happened, counts as a drop.
     */
    private suspend fun awaitPromptOrGiveUp(
        activity: FragmentActivity,
        prompt: BiometricPrompt,
        outcome: CompletableDeferred<Cipher?>,
    ) {
        // Clears the library's own 600 ms `isDelayingPrompt` postDelayed path
        // plus the system dialog's animation in.
        delay(PROMPT_GRACE_MS)
        var focusedPolls = 0
        while (!outcome.isCompleted) {
            val nothingOnTop =
                activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                    activity.hasWindowFocus()
            focusedPolls = if (nothingOnTop) focusedPolls + 1 else 0
            if (focusedPolls >= PROMPT_MISSING_POLLS) {
                outcome.completeExceptionally(PromptNotShownException())
                // After the outcome, so a late ERROR_CANCELED can't overwrite
                // the diagnosis with a generic "cancelled".
                prompt.cancelAuthentication()
                return
            }
            delay(PROMPT_POLL_MS)
        }
    }

    private const val PROMPT_GRACE_MS = 2_000L
    private const val PROMPT_POLL_MS = 500L
    private const val PROMPT_MISSING_POLLS = 4

    class BiometricAuthException(val errorCode: Int, message: String) : Exception(message)

    /**
     * The prompt was requested but never reached the screen, and the library
     * gave us no callback to explain why. Distinct from [BiometricAuthException]
     * (which carries a real system error code) precisely so a bug report can
     * tell "the sensor refused me" apart from "nothing was ever asked".
     */
    class PromptNotShownException : Exception(
        "le lecteur d'empreinte n'a pas répondu"
    )
}
