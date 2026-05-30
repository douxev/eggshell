package com.douxev.eggshell.ui.unlock

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.douxev.eggshell.data.VaultRepository
import com.douxev.eggshell.security.DecoyVerifier
import com.douxev.eggshell.security.VaultPrefs

@HiltViewModel
class UnlockViewModel @Inject constructor(
    private val repo: VaultRepository,
    private val decoy: DecoyVerifier,
    private val throttle: com.douxev.eggshell.security.PinRateLimiter,
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
                    _state.value = State.Decoy
                }
                accessHit -> {
                    throttle.reset()
                    _state.value = when (mode) {
                        VaultPrefs.Mode.KEYSTORE_ONLY,
                        VaultPrefs.Mode.KEYSTORE_BIOMETRIC -> State.AccessGranted
                        VaultPrefs.Mode.KEYSTORE_PASSPHRASE,
                        VaultPrefs.Mode.PARANOID,
                        null -> State.AwaitingPassphrase
                    }
                }
                else -> {
                    throttle.recordFailure()
                    if (throttle.shouldWipe()) {
                        repo.wipeAll()
                        throttle.reset()
                        _state.value = State.Wiped
                    } else {
                        _state.value = State.Failed("PIN incorrect")
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

    /** Triggered automatically for Keystore-only / Keystore-biometric modes. */
    fun attemptAutoUnlock(activity: FragmentActivity?, biometricCopy: VaultRepository.BiometricCopy?) {
        _state.value = State.InProgress
        viewModelScope.launch {
            attemptUnlock(null, activity, biometricCopy)
        }
    }

    /** Reset back to the PIN gate after a Failed state. */
    fun resetToPin() {
        _state.value = if (hasDecoy) State.AwaitingPin else State.AwaitingPassphrase
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
}
