package com.douxev.eggshell.ui.unlock

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.delay
import com.douxev.eggshell.R
import com.douxev.eggshell.data.VaultRepository
import com.douxev.eggshell.security.VaultPrefs
import com.douxev.eggshell.ui.common.PasswordField
import com.douxev.eggshell.ui.common.currentFragmentActivity
import com.douxev.eggshell.ui.components.CardVariant
import com.douxev.eggshell.ui.components.EggCard

/**
 * The lock screen of the refonte (§6.13). No header, no chrome: the logo, one
 * warm sentence, four pips and a keypad that fills the thumb zone.
 *
 * It still serves every vault mode:
 *  - Keystore-only / Keystore-biometric: silent unwrap or biometric prompt.
 *  - Passphrase / Paranoid: the text field.
 *  - Decoy configured: the 4-digit gate in front of all of them.
 *
 * With a decoy PIN set the whole screen re-dresses as the notes app's passcode
 * gate — the same neutral teal palette [DecoyScreen] uses, a padlock instead of
 * the egg, and none of the refonte's tokens — so the hand-off from lock screen
 * to fake notes shows no colour jump and no lavender flash.
 */
@Composable
fun UnlockScreen(
    onUnlocked: () -> Unit,
    vm: UnlockViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    val lockoutMs by vm.lockoutMs.collectAsState()
    val attemptsLeft by vm.attemptsLeft.collectAsState()
    val recoveryAttempt by vm.recoveryAttempt.collectAsState()
    val keystoreUnusable by vm.keystoreUnusable.collectAsState()
    val activity = currentFragmentActivity()
    val biometricCopy = VaultRepository.BiometricCopy(
        title = stringResource(R.string.biometric_unlock_title),
        subtitle = stringResource(R.string.biometric_unlock_subtitle),
        cancel = stringResource(R.string.action_cancel),
    )
    val rearmCopy = VaultRepository.BiometricCopy(
        title = stringResource(R.string.biometric_rearm_title),
        subtitle = stringResource(R.string.biometric_rearm_subtitle),
        cancel = stringResource(R.string.action_cancel),
    )

    // Auto-trigger Keystore modes the first time we land here — but ONLY
    // when no decoy is set. With a decoy, the user must first pass the PIN
    // gate; the silent Keystore unwrap kicks in after access PIN matches.
    LaunchedEffect(vm.mode, vm.hasDecoy) {
        if (!vm.hasDecoy &&
            (vm.mode == VaultPrefs.Mode.KEYSTORE_ONLY ||
                vm.mode == VaultPrefs.Mode.KEYSTORE_BIOMETRIC)
        ) {
            vm.attemptAutoUnlock(activity, biometricCopy)
        }
    }

    // After a successful PIN-gate match in Keystore modes, fire the normal
    // unwrap (silent for KEYSTORE_ONLY, biometric prompt for KEYSTORE_BIOMETRIC).
    LaunchedEffect(state) {
        if (state is UnlockViewModel.State.AccessGranted) {
            vm.attemptAutoUnlock(activity, biometricCopy)
        }
    }

    // Auto-bounce Failed → previous stage so the user can retry without tapping anywhere.
    LaunchedEffect(state) {
        if (state is UnlockViewModel.State.Failed) {
            delay(1400)
            vm.resetToPin()
        }
    }

    if (state is UnlockViewModel.State.Success) { onUnlocked(); return }
    if (state is UnlockViewModel.State.Decoy) { DecoyScreen(); return }
    // Wipe completes inside the VM; the AppRoot recomputes the route from
    // VaultRepository.isInitialized (now false) and routes back to
    // Onboarding — calling onUnlocked() triggers exactly that path.
    if (state is UnlockViewModel.State.Wiped) { onUnlocked(); return }

    val body: @Composable () -> Unit = {
        UnlockBody(
            state = state,
            mode = vm.mode,
            // Read once for the whole composition: every biometric affordance is
            // gated on it, so the flag must not be able to differ between the
            // key that is drawn and the callback that key fires.
            decoy = vm.hasDecoy,
            hasRecovery = vm.hasRecovery,
            recoveryAttempt = recoveryAttempt,
            keystoreUnusable = keystoreUnusable,
            lockoutMs = lockoutMs,
            attemptsLeft = attemptsLeft,
            onSubmitPin = vm::submitPin,
            onSubmitPassphrase = { pp -> vm.submitPassphrase(pp, activity, biometricCopy) },
            onBiometric = { vm.attemptAutoUnlock(activity, biometricCopy) },
            onOpenRecovery = vm::openRecovery,
            onCloseRecovery = vm::closeRecovery,
            // Distinct copy: the prompt that follows a recovery unlock is not
            // "prove it's you to get in" — they are already in — it is
            // "re-link the fingerprint that changed".
            onSubmitRecovery = { s -> vm.submitRecovery(s, activity, rearmCopy) },
        )
    }

    if (vm.hasDecoy) {
        MaterialTheme(
            colorScheme = DecoyColors,
            typography = MaterialTheme.typography,
            content = body,
        )
    } else {
        body()
    }
}

/** Which input surface the current state puts in front of the user. */
private enum class Stage { Pin, Passphrase, Biometric, Recovery, Working, Silent }

/**
 * How long a non-interactive state may last before the screen assumes it is
 * stuck and offers a way out.
 *
 * Comfortably past the ~4 s watchdog inside [BiometricKeystoreUnlock], which
 * catches the common case (prompt never reached the screen) and reports a real
 * failure. This is the backstop for what that watchdog cannot see — a prompt
 * that did appear but whose result the library swallowed.
 */
private const val STALLED_AFTER_MS = 12_000L

private fun stageFor(
    state: UnlockViewModel.State,
    mode: VaultPrefs.Mode?,
    decoy: Boolean,
    stalled: Boolean,
    recoveryAttempt: Boolean,
): Stage {
    if (mode == null) return Stage.Silent
    // A failure bounces back to whatever surface the user came from, so the
    // keypad (or the field) stays put under the error line instead of the
    // screen emptying out for a second and a half.
    val effective = if (state is UnlockViewModel.State.Failed) {
        when {
            decoy -> UnlockViewModel.State.AwaitingPin
            mode == VaultPrefs.Mode.KEYSTORE_BIOMETRIC -> UnlockViewModel.State.AwaitingBiometric
            mode == VaultPrefs.Mode.KEYSTORE_ONLY -> UnlockViewModel.State.Idle
            else -> UnlockViewModel.State.AwaitingPassphrase
        }
    } else {
        state
    }
    // Biometric unlock is the one path with no input surface of its own: the
    // prompt is the whole interaction, so if it never arrives the screen has
    // nothing on it to tap and the only way out is force-stopping the app.
    // Whenever we have been sitting on a non-interactive state long enough to
    // call it stuck, fall back to the fingerprint tile — worst case it sits
    // unseen behind a prompt that did show up.
    val biometricStuck = stalled && mode == VaultPrefs.Mode.KEYSTORE_BIOMETRIC && !recoveryAttempt
    val stage = when (effective) {
        UnlockViewModel.State.AwaitingPin -> Stage.Pin
        UnlockViewModel.State.AwaitingPassphrase -> Stage.Passphrase
        UnlockViewModel.State.AwaitingBiometric -> Stage.Biometric
        UnlockViewModel.State.AwaitingRecovery -> Stage.Recovery
        UnlockViewModel.State.InProgress,
        UnlockViewModel.State.AccessGranted -> if (biometricStuck) Stage.Biometric else Stage.Working
        UnlockViewModel.State.Idle -> if (biometricStuck) Stage.Biometric else Stage.Silent
        is UnlockViewModel.State.Throttled -> Stage.Pin
        else -> Stage.Silent
    }
    // Under a decoy the screen has exactly one way in — the PIN typed on it,
    // which then routes to the real vault or to the notes app. A fingerprint
    // (or recovery-key) surface here would open the *real* vault straight from
    // a screen that claims to be a notes-app passcode gate, so neither appears.
    return if (decoy && (stage == Stage.Biometric || stage == Stage.Recovery)) Stage.Pin else stage
}

@Composable
private fun UnlockBody(
    state: UnlockViewModel.State,
    mode: VaultPrefs.Mode?,
    decoy: Boolean,
    hasRecovery: Boolean,
    recoveryAttempt: Boolean,
    keystoreUnusable: Boolean,
    lockoutMs: Long,
    attemptsLeft: Int?,
    onSubmitPin: (String) -> Unit,
    onSubmitPassphrase: (String) -> Unit,
    onBiometric: () -> Unit,
    onOpenRecovery: () -> Unit,
    onCloseRecovery: () -> Unit,
    onSubmitRecovery: (String) -> Unit,
) {
    // No state on this screen is allowed to be terminal. The biometric prompt
    // can be dropped by the platform without ever calling back (see
    // BiometricKeystoreUnlock.awaitPromptOrGiveUp), and that used to strand
    // the user on a progress bar with no button, no error and no retry. Any
    // state that outlives this delay is treated as stuck; `stageFor` decides
    // what to offer instead.
    var stalled by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        stalled = false
        delay(STALLED_AFTER_MS)
        stalled = true
    }

    val stage = stageFor(state, mode, decoy, stalled, recoveryAttempt)
    val locked = lockoutMs > 0L

    // Never `rememberSaveable`: that would serialise the PIN into the
    // saved-state Bundle, where `dumpsys` can read it back.
    var pin by remember { mutableStateOf("") }
    LaunchedEffect(pin) {
        if (pin.length == 4) {
            onSubmitPin(pin)
            pin = ""
        }
    }
    LaunchedEffect(stage) { if (stage != Stage.Pin) pin = "" }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = ScreenPad),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                BrandMark(decoy = decoy)
                Spacer(Modifier.height(20.dp))
                Text(
                    stringResource(
                        if (decoy) R.string.alias_notes else R.string.access_unlock_welcome
                    ),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )

                when (stage) {
                    Stage.Pin -> {
                        Spacer(Modifier.height(16.dp))
                        PinPips(filled = pin.length)
                    }
                    Stage.Working -> {
                        Spacer(Modifier.height(20.dp))
                        LinearProgressIndicator(modifier = Modifier.width(120.dp))
                    }
                    else -> Unit
                }

                val message = messageFor(state, mode, stage, keystoreUnusable)
                if (message != null) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state is UnlockViewModel.State.Failed) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        textAlign = TextAlign.Center,
                    )
                }

                if (locked) {
                    Spacer(Modifier.height(18.dp))
                    LockoutCard(
                        lockoutMs = lockoutMs,
                        attemptsLeft = attemptsLeft,
                        decoy = decoy,
                    )
                }

                when (stage) {
                    Stage.Passphrase -> {
                        Spacer(Modifier.height(22.dp))
                        PassphraseStage(onSubmit = onSubmitPassphrase)
                    }
                    Stage.Biometric -> {
                        Spacer(Modifier.height(22.dp))
                        BiometricStage(onRetry = onBiometric)
                        // The escape hatch for the failure the retry button
                        // cannot fix: a Keystore key destroyed by a fingerprint
                        // re-enrollment will refuse forever, however many times
                        // it is tapped.
                        if (hasRecovery) {
                            TextButton(onClick = onOpenRecovery) {
                                Text(stringResource(R.string.unlock_recovery_open))
                            }
                        }
                    }
                    Stage.Recovery -> {
                        Spacer(Modifier.height(22.dp))
                        RecoveryStage(onSubmit = onSubmitRecovery, onBack = onCloseRecovery)
                    }
                    else -> Unit
                }
            }

            if (stage == Stage.Pin) {
                Keypad(
                    enabled = !locked,
                    onDigit = { d -> if (pin.length < 4) pin += d },
                    onBackspace = { if (pin.isNotEmpty()) pin = pin.dropLast(1) },
                    // No biometric key at all on the decoy skin: one finger on
                    // it would unwrap the Keystore and open the real vault.
                    onBiometric = if (decoy) null else onBiometric,
                    modifier = Modifier.padding(bottom = 30.dp),
                )
            }
        }
    }
}

/**
 * The eggshell mark, drawn from the launcher artwork so it is **never**
 * re-tinted by the active palette (§11). Under a decoy it is replaced by a
 * plain padlock: nothing of the real app may show through here.
 */
@Composable
private fun BrandMark(decoy: Boolean) {
    if (decoy) {
        Box(
            modifier = Modifier
                .size(LogoSize)
                .background(
                    MaterialTheme.colorScheme.surfaceContainerHigh,
                    RoundedCornerShape(22.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(36.dp),
            )
        }
    } else {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.size(LogoSize),
        )
    }
}

/**
 * Four pips: filled ones in `primary`, the *next* one ringed in `outline` so the
 * eye knows where the following digit lands, the rest in `outlineVariant`.
 */
@Composable
private fun PinPips(filled: Int) {
    val progress = stringResource(R.string.access_unlock_pin_progress, filled)
    Row(
        horizontalArrangement = Arrangement.spacedBy(15.dp),
        modifier = Modifier.semantics {
            liveRegion = LiveRegionMode.Polite
            contentDescription = progress
        },
    ) {
        repeat(PIN_LENGTH) { index ->
            val isFilled = index < filled
            val ring = when {
                isFilled -> Color.Transparent
                index == filled -> MaterialTheme.colorScheme.outline
                else -> MaterialTheme.colorScheme.outlineVariant
            }
            Box(
                modifier = Modifier
                    .size(PipSize)
                    .background(
                        if (isFilled) MaterialTheme.colorScheme.primary else Color.Transparent,
                        CircleShape,
                    )
                    .border(1.5.dp, ring, CircleShape),
            )
        }
    }
}

/**
 * The lockout notice. The ladder itself (3 free tries, 5 s → 30 s → 2 min →
 * 10 min → 1 h, wipe at 12) belongs to `PinRateLimiter`; all this does is make
 * the wait — and how close the vault is to erasing itself — readable.
 */
@Composable
private fun LockoutCard(lockoutMs: Long, attemptsLeft: Int?, decoy: Boolean) {
    EggCard(variant = CardVariant.Error) {
        Text(
            stringResource(R.string.access_unlock_locked_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            stringResource(R.string.access_unlock_locked_body, formatRemaining(lockoutMs)),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (attemptsLeft != null && attemptsLeft > 0) {
            Text(
                // The word "coffre" would betray that an encrypted vault sits
                // behind this screen — on the decoy skin, which presents itself
                // as a notes app's passcode gate, that is the one thing it must
                // never say. The warning stays, the noun goes.
                pluralStringResource(
                    if (decoy) {
                        R.plurals.access_unlock_attempts_left_neutral
                    } else {
                        R.plurals.access_unlock_attempts_left
                    },
                    attemptsLeft,
                    attemptsLeft,
                ),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.W600,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun formatRemaining(ms: Long): String {
    val total = ((ms + 999L) / 1000L).toInt()
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return when {
        hours > 0 -> stringResource(R.string.access_duration_h_min, hours, minutes)
        minutes > 0 -> stringResource(R.string.access_duration_min_s, minutes, seconds)
        else -> stringResource(R.string.access_duration_s, seconds)
    }
}

@Composable
private fun messageFor(
    state: UnlockViewModel.State,
    mode: VaultPrefs.Mode?,
    stage: Stage,
    keystoreUnusable: Boolean,
): String? = when {
    mode == null -> stringResource(R.string.unlock_not_initialized)
    state is UnlockViewModel.State.Failed ->
        // A wrong PIN is the one failure the user can act on, so it gets plain
        // language; anything else keeps the underlying reason for debugging.
        if (state.reason == UnlockViewModel.REASON_WRONG_PIN) {
            stringResource(R.string.access_unlock_wrong_pin)
        } else {
            stringResource(R.string.unlock_error_prefix, state.reason)
        }
    // Explains why the fingerprint tile just vanished from under them.
    stage == Stage.Recovery && keystoreUnusable ->
        stringResource(R.string.unlock_recovery_keystore_dead)
    stage == Stage.Working -> stringResource(R.string.unlock_in_progress)
    // The passphrase field already labels itself; only the biometric stage
    // needs a sentence to explain what the big fingerprint tile does.
    stage == Stage.Biometric -> stringResource(R.string.unlock_prompt_biometric)
    else -> null
}

@Composable
private fun PassphraseStage(onSubmit: (String) -> Unit) {
    var passphrase by remember { mutableStateOf("") }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        PasswordField(
            value = passphrase,
            onValueChange = { passphrase = it },
            label = stringResource(R.string.passphrase_label),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { onSubmit(passphrase) },
            enabled = passphrase.isNotEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) { Text(stringResource(R.string.action_unlock)) }
    }
}

/**
 * The recovery-key field: the one way in that never touches the Keystore, and
 * therefore the only one that still works once the biometric-bound key has been
 * destroyed by a fingerprint re-enrollment.
 */
@Composable
private fun RecoveryStage(onSubmit: (String) -> Unit, onBack: () -> Unit) {
    var secret by remember { mutableStateOf("") }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        PasswordField(
            value = secret,
            onValueChange = { secret = it },
            label = stringResource(R.string.unlock_recovery_label),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { onSubmit(secret) },
            enabled = secret.isNotEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) { Text(stringResource(R.string.unlock_recovery_submit)) }
        TextButton(onClick = onBack) {
            Text(stringResource(R.string.unlock_recovery_back))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BiometricStage(onRetry: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Surface(
            onClick = onRetry,
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.size(96.dp),
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.Fingerprint,
                    contentDescription = stringResource(R.string.unlock_biometric_cd),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp),
                )
            }
        }
        Button(
            onClick = onRetry,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) { Text(stringResource(R.string.unlock_action_biometric)) }
    }
}

@Composable
private fun Keypad(
    enabled: Boolean,
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    /** `null` removes the fingerprint key entirely (decoy skin). */
    onBiometric: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(KeypadGap),
    ) {
        listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
        ).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(KeypadGap)) {
                row.forEach { digit -> DigitKey(digit, enabled, onDigit) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(KeypadGap)) {
            // Special keys carry no background but keep the full 62 dp target,
            // so the grid stays square to the thumb even where it looks empty.
            //
            // With no biometric callback the slot is left blank rather than
            // disabled: a greyed-out fingerprint on a notes-app passcode gate
            // would still say "this phone has something else behind it".
            if (onBiometric != null) {
                FlatKey(onClick = onBiometric, enabled = enabled) {
                    Icon(
                        Icons.Filled.Fingerprint,
                        contentDescription = stringResource(R.string.unlock_biometric_cd),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(27.dp),
                    )
                }
            } else {
                Spacer(
                    Modifier
                        .weight(1f)
                        .height(KeyHeight)
                )
            }
            DigitKey("0", enabled, onDigit)
            FlatKey(onClick = onBackspace, enabled = enabled) {
                Icon(
                    Icons.Filled.Backspace,
                    contentDescription = stringResource(R.string.unlock_delete_cd),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(25.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RowScope.DigitKey(digit: String, enabled: Boolean, onDigit: (String) -> Unit) {
    Surface(
        onClick = { onDigit(digit) },
        enabled = enabled,
        modifier = Modifier
            .weight(1f)
            .height(KeyHeight),
        shape = RoundedCornerShape(KeyRadius),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                digit,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.W500,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RowScope.FlatKey(
    onClick: () -> Unit,
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .weight(1f)
            .height(KeyHeight),
        shape = RoundedCornerShape(KeyRadius),
        color = Color.Transparent,
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            content()
        }
    }
}

private const val PIN_LENGTH = 4
private val ScreenPad = 26.dp
private val LogoSize = 74.dp
private val PipSize = 14.dp
private val KeyHeight = 62.dp
private val KeyRadius = 22.dp
private val KeypadGap = 12.dp
