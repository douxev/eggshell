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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
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

    /** Whether a second, non-Keystore way into the vault exists. */
    val hasRecovery: Boolean get() = repo.hasRecoverySecret

    /**
     * True once the access PIN has been entered correctly this session.
     *
     * Only meaningful on a decoy install, where it is the difference between
     * "someone is holding this phone" and "the owner is holding this phone".
     */
    private val _accessGatePassed = MutableStateFlow(false)

    /**
     * Whether the recovery field may be shown at all.
     *
     * Without a decoy: as soon as a recovery secret exists. With one: only
     * after the access PIN has been passed. The earlier rule — never, under a
     * decoy — was a straight bug: `needsRecoverySetup` does not exempt decoy
     * installs, so those users were forced through a mandatory gate to create
     * a key the app then refused to ever accept, leaving the permanent
     * data-loss hole this whole feature exists to close wide open for them.
     */
    val recoveryReachable: StateFlow<Boolean> =
        _accessGatePassed
            .map { gatePassed -> repo.hasRecoverySecret && (!hasDecoy || gatePassed) }
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                repo.hasRecoverySecret && !decoy.hasAccessPin,
            )

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
         * The user asked for the recovery-key field because the primary factor
         * is not working — typically a Keystore key destroyed by a fingerprint
         * re-enrollment, which no amount of retrying will fix.
         */
        data object AwaitingRecovery : State

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
        // Reset when the vault closes.
        //
        // UnlockScreen is called straight from AppRoot rather than inside a
        // NavHost, so hiltViewModel() scopes this to the ACTIVITY and it
        // survives being navigated away from. After a successful unlock the
        // state stayed Success — and once background locking started sending
        // the router back to Unlock, the screen recomposed against that stale
        // Success, hit `onUnlocked(); return`, drew nothing, refreshed, was
        // sent back to Unlock, and span. A white screen, every time.
        //
        // The decoy never showed it because that path ends at Decoy and never
        // reaches Success at all.
        viewModelScope.launch {
            repo.unlocked.collect { open ->
                if (!open && _state.value is State.Success) {
                    lastAttemptWasRecovery = false
                    _keystoreUnusable.value = false
                    _accessGatePassed.value = false
                    _state.value = initialState()
                }
            }
        }
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
                    // Deliberately neither reset nor recorded as a failure.
                    //
                    // Resetting handed the snooper an unlimited, un-throttled
                    // oracle: alternate the decoy PIN with a guess at the real
                    // one and the ladder never climbs, the wipe never fires.
                    // The decoy PIN is the one credential the adversary is most
                    // likely to have been given or to have watched being typed.
                    //
                    // Counting it as a failure is the opposite trap: anyone who
                    // knows the decoy could then destroy the vault by entering
                    // it a dozen times. Leaving the counter untouched is the
                    // only option that neither helps nor punishes.
                    clearThrottleDisplay()
                    _state.value = State.Decoy
                }
                accessHit -> {
                    throttle.reset()
                    clearThrottleDisplay()
                    // The one gate that proves this is the owner and not the
                    // person the decoy exists to mislead. It is what makes the
                    // recovery field safe to expose on a decoy install.
                    _accessGatePassed.value = true
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
        // The trigger LaunchedEffect re-runs on every Activity recreation, so a
        // rotation mid-prompt used to fire a second concurrent authenticate()
        // — which androidx answers by no-op'ing one of them, leaving a prompt
        // nobody is waiting on.
        if (_state.value is State.InProgress) return
        lastAttemptWasRecovery = false
        _state.value = State.InProgress
        viewModelScope.launch {
            attemptUnlock(null, activity, biometricCopy)
        }
    }

    /**
     * Show the recovery-key field.
     *
     * Refused under a decoy for the same reason the fingerprint tile is: the
     * decoy skin claims to be a notes app's passcode gate, and every surface on
     * it must lead through the PIN. A recovery field there would open the real
     * vault straight from the screen built to hide that it exists.
     */
    fun openRecovery() {
        if (!recoveryReachable.value) return
        lastAttemptWasRecovery = true
        _state.value = State.AwaitingRecovery
    }

    /** Back to the primary factor from the recovery field. */
    fun closeRecovery() {
        lastAttemptWasRecovery = false
        resetToPin()
    }

    /**
     * The activity and copy are not for the recovery unlock itself — that path
     * never touches the Keystore. They are for the re-arm that follows it, so
     * a user whose key was destroyed goes back to using their fingerprint
     * instead of typing the recovery secret at every single unlock.
     */
    fun submitRecovery(
        secret: String,
        activity: FragmentActivity?,
        biometricCopy: VaultRepository.BiometricCopy?,
    ) {
        if (!recoveryReachable.value) return
        lastAttemptWasRecovery = true
        // Same backoff ladder as the PIN, on its own counter, and with no wipe
        // threshold: this is the surface a locked-out owner reaches for, so
        // destroying their vault over a typo would invert its purpose. Without
        // it the field was an unthrottled online brute-force oracle against the
        // real vault — the only unlock surface in the app without a limiter.
        val waiting = throttle.recoveryLockedOutMs()
        if (waiting > 0) {
            _lockoutMs.value = waiting
            _state.value = State.Throttled(waiting)
            return
        }
        _state.value = State.InProgress
        viewModelScope.launch {
            when (val out = repo.unlockWithRecovery(secret, activity, biometricCopy)) {
                is VaultRepository.UnlockOutcome.Success -> {
                    throttle.resetRecovery()
                    clearThrottleDisplay()
                    _state.value = State.Success
                }
                is VaultRepository.UnlockOutcome.Failed -> {
                    throttle.recordRecoveryFailure()
                    _lockoutMs.value = throttle.recoveryLockedOutMs()
                    _state.value = State.Failed(out.reason)
                }
                VaultRepository.UnlockOutcome.NotInitialized ->
                    _state.value = State.Failed("not initialized")
                // Unreachable in practice: this path never touches the Keystore,
                // which is the entire point of it. Handled rather than `else`d
                // so a future outcome cannot slip through unnoticed.
                is VaultRepository.UnlockOutcome.KeystoreUnusable ->
                    _state.value = State.Failed(out.reason)
            }
        }
    }

    /**
     * Whether the last attempt came from the recovery field.
     *
     * Two jobs. It puts the post-failure bounce back on that field instead of
     * dumping the user on the fingerprint tile they already told us is not
     * working — and it tells the screen not to treat a slow recovery unlock as
     * a stalled biometric prompt. Argon2id derivation legitimately takes
     * seconds, and there is no prompt to have been dropped on that path.
     */
    private val _recoveryAttempt = MutableStateFlow(false)
    val recoveryAttempt: StateFlow<Boolean> = _recoveryAttempt.asStateFlow()

    /**
     * The Keystore has definitively refused the primary factor this session.
     * Drives the one line of copy that tells the user why they are suddenly
     * looking at the recovery field instead of their fingerprint.
     */
    private val _keystoreUnusable = MutableStateFlow(false)
    val keystoreUnusable: StateFlow<Boolean> = _keystoreUnusable.asStateFlow()

    private var lastAttemptWasRecovery: Boolean
        get() = _recoveryAttempt.value
        set(value) { _recoveryAttempt.value = value }

    /** Reset back to the right entry-point after a Failed state. */
    fun resetToPin() {
        _state.value = when {
            lastAttemptWasRecovery -> State.AwaitingRecovery
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
            is VaultRepository.UnlockOutcome.KeystoreUnusable -> {
                _keystoreUnusable.value = true
                // Send them where they can actually get in. Under a decoy no
                // recovery surface may exist, and with no recovery secret set
                // there is nowhere to send them — both fall back to the plain
                // error, which is at least honest about the dead end.
                if (recoveryReachable.value) {
                    lastAttemptWasRecovery = true
                    _state.value = State.AwaitingRecovery
                } else {
                    _state.value = State.Failed(out.reason)
                }
            }
        }
    }

    companion object {
        /** Marker the screen matches on to swap the raw reason for plain copy. */
        const val REASON_WRONG_PIN = "PIN incorrect"
    }
}
