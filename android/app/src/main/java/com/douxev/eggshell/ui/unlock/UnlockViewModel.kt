package com.douxev.eggshell.ui.unlock

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.douxev.eggshell.data.VaultRepository
import com.douxev.eggshell.security.DecoyVerifier
import com.douxev.eggshell.security.PinRateLimiter
import com.douxev.eggshell.security.VaultPrefs

@HiltViewModel
class UnlockViewModel @Inject constructor(
    private val repo: VaultRepository,
    private val decoy: DecoyVerifier,
    private val throttle: PinRateLimiter,
) : ViewModel() {

    val mode: VaultPrefs.Mode? = repo.currentMode
    val hasDecoy: Boolean get() = decoy.hasAccessPin && decoy.hasDecoyPin

    sealed interface State {
        /** Initial / waiting for input. */
        data object Idle : State

        /** The user is at the PIN-keypad gate (decoy enabled). */
        data object AwaitingPin : State

        /**
         * The PIN gate passed; show the text passphrase input.
         * Only relevant for passphrase modes (3, 4).
         */
        data object AwaitingPassphrase : State

        /**
         * Keystore-biometric mode landed back here after a dismiss/error.
         * The UI shows a single "tap to unlock" fingerprint button — we
         * deliberately do NOT auto-retry since the user dismissed for a
         * reason and re-popping the prompt would be hostile.
         */
        data object AwaitingBiometric : State

        /**
         * The PIN gate passed in a Keystore-only-style mode (1, 2). The
         * screen reacts by triggering the normal Keystore / biometric
         * unwrap. No further input from the user.
         */
        data object AccessGranted : State

        /** Async work in flight (Keystore unwrap, biometric, SQLCipher open). */
        data object InProgress : State

        /** Throttle is active: the user has to wait `remainingMs` before
         *  the next PIN attempt is accepted. Surfaces a countdown in the UI. */
        data class Throttled(val remainingMs: Long) : State

        /** Too many failed attempts in a row — vault has been wiped to
         *  prevent further brute-force. The UI takes the user back to
         *  onboarding. */
        data object Wiped : State

        data object Success : State
        data object Decoy : State
        data class Failed(val reason: String) : State
    }

    private val _state = MutableStateFlow<State>(initialState())
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Milliseconds left on the current lockout, refreshed every second.
     *
     * The ladder itself lives in [PinRateLimiter] and is not re-implemented
     * here: we only *read* it, so the screen can show a countdown the user can
     * actually plan around instead of silently swallowing their taps.
     */
    private val _lockoutMs = MutableStateFlow(throttle.lockedOutMs())
    val lockoutMs: StateFlow<Long> = _lockoutMs.asStateFlow()

    /**
     * Attempts left before the vault self-erases, or `null` while unknown.
     * The limiter doesn't publish its counter, so this only fills in once the
     * user has failed at least once in this process — which is exactly when
     * the warning becomes useful.
     */
    private val _attemptsLeft = MutableStateFlow<Int?>(null)
    val attemptsLeft: StateFlow<Int?> = _attemptsLeft.asStateFlow()

    init {
        viewModelScope.launch {
            while (isActive) {
                _lockoutMs.value = throttle.lockedOutMs()
                delay(1_000L)
            }
        }
    }

    private fun initialState(): State = when {
        mode == null -> State.Failed("not initialized")
        // Decoy is set → user MUST type a PIN, regardless of underlying mode.
        // This makes the keypad the consistent surface a snooper sees.
        hasDecoy -> State.AwaitingPin
        // No decoy: Keystore modes auto-unlock (the screen triggers it),
        // passphrase modes go straight to the text field.
        mode == VaultPrefs.Mode.KEYSTORE_ONLY ||
            mode == VaultPrefs.Mode.KEYSTORE_BIOMETRIC -> State.Idle
        else -> State.AwaitingPassphrase
    }

    /** Called by the screen when the PIN-pad input reaches 4 digits. */
    fun submitPin(input: String) {
        if (input.length != 4) return
        // Reject early when the throttle is active. UI shows the countdown.
        val remaining = throttle.lockedOutMs()
        if (remaining > 0) {
            _lockoutMs.value = remaining
            _state.value = State.Throttled(remaining)
            return
        }
        _state.value = State.InProgress
        viewModelScope.launch {
            // Always evaluate both PIN matchers — even when the access PIN
            // already matched — so a timing observer can't distinguish
            // "right access PIN" from "right decoy PIN" by measuring how
            // many Argon2id derivations ran before the UI updated.
            val decoyHit = decoy.decoyMatches(input)
            val accessHit = decoy.accessMatches(input)
            when {
                decoyHit -> {
                    // Decoy hits don't count as failed attempts — the user
                    // (or a snooper) entered a "valid" PIN by design.
                    throttle.reset()
                    clearThrottleDisplay()
                    _state.value = State.Decoy
                }
                accessHit -> {
                    throttle.reset()
                    clearThrottleDisplay()
                    _state.value = when (mode) {
                        VaultPrefs.Mode.KEYSTORE_ONLY,
                        VaultPrefs.Mode.KEYSTORE_BIOMETRIC -> State.AccessGranted
                        VaultPrefs.Mode.KEYSTORE_PASSPHRASE,
                        VaultPrefs.Mode.PARANOID,
                        null -> State.AwaitingPassphrase
                    }
                }
                else -> {
                    val failures = throttle.recordFailure()
                    _attemptsLeft.value =
                        (PinRateLimiter.WIPE_THRESHOLD - failures).coerceAtLeast(0)
                    _lockoutMs.value = throttle.lockedOutMs()
                    if (throttle.shouldWipe()) {
                        repo.wipeAll()
                        throttle.reset()
                        clearThrottleDisplay()
                        _state.value = State.Wiped
                    } else {
                        _state.value = State.Failed(REASON_WRONG_PIN)
                    }
                }
            }
        }
    }

    /** Called for passphrase modes when the user submits the text field. */
    fun submitPassphrase(
        passphrase: String,
        activity: FragmentActivity?,
        biometricCopy: VaultRepository.BiometricCopy?,
    ) {
        _state.value = State.InProgress
        viewModelScope.launch {
            attemptUnlock(passphrase, activity, biometricCopy)
        }
    }

    /**
     * Triggered automatically for Keystore-only / Keystore-biometric modes.
     *
     * In `KEYSTORE_ONLY` this is a *silent* Keystore unwrap that opens the real
     * vault with no prompt at all, and in `KEYSTORE_BIOMETRIC` a single finger
     * does it. So when a decoy PIN is configured it is refused until the PIN
     * gate has actually been passed: the decoy only holds if the four digits
     * typed on that keypad are the single thing that decides whether the real
     * vault or the notes app opens. The screen already hides every biometric
     * affordance there; this makes the rule hold even if some future entry
     * point forgets to.
     */
    fun attemptAutoUnlock(activity: FragmentActivity?, biometricCopy: VaultRepository.BiometricCopy?) {
        if (hasDecoy && _state.value !is State.AccessGranted) return
        _state.value = State.InProgress
        viewModelScope.launch {
            attemptUnlock(null, activity, biometricCopy)
        }
    }

    /** Reset back to the right entry-point after a Failed state. */
    fun resetToPin() {
        _state.value = when {
            hasDecoy -> State.AwaitingPin
            mode == VaultPrefs.Mode.KEYSTORE_BIOMETRIC -> State.AwaitingBiometric
            mode == VaultPrefs.Mode.KEYSTORE_ONLY -> State.Idle
            else -> State.AwaitingPassphrase
        }
    }

    private fun clearThrottleDisplay() {
        _attemptsLeft.value = null
        _lockoutMs.value = 0L
    }

    private suspend fun attemptUnlock(
        passphrase: String?,
        activity: FragmentActivity?,
        biometricCopy: VaultRepository.BiometricCopy?,
    ) {
        when (val out = repo.unlock(passphrase, activity, biometricCopy)) {
            is VaultRepository.UnlockOutcome.Success -> _state.value = State.Success
            is VaultRepository.UnlockOutcome.Failed -> _state.value = State.Failed(out.reason)
            VaultRepository.UnlockOutcome.NotInitialized -> _state.value = State.Failed("not initialized")
        }
    }

    companion object {
        /** Marker the screen matches on to swap the raw reason for plain copy. */
        const val REASON_WRONG_PIN = "PIN incorrect"
    }
}
