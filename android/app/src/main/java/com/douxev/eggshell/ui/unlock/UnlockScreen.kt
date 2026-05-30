package com.douxev.eggshell.ui.unlock

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.delay
import com.douxev.eggshell.R
import com.douxev.eggshell.data.VaultRepository
import com.douxev.eggshell.security.VaultPrefs

/**
 * Two-stage lock screen.
 *
 *  - Auto-unlock (Keystore-only / Keystore-biometric): biometric prompt or
 *    silent Keystore unwrap, no input.
 *  - Passphrase mode + decoy ENABLED: 4-digit PIN keypad. Access PIN reveals
 *    the passphrase text field; decoy PIN routes to the calculator; anything
 *    else surfaces a "PIN incorrect" message and lets the user retry.
 *  - Passphrase mode + decoy DISABLED: plain passphrase text field (any
 *    length, any characters), with a biometric button for keystore-based
 *    flows where applicable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnlockScreen(
    onUnlocked: () -> Unit,
    vm: UnlockViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    val activity = LocalContext.current as FragmentActivity
    val biometricCopy = VaultRepository.BiometricCopy(
        title = stringResource(R.string.biometric_unlock_title),
        subtitle = stringResource(R.string.biometric_unlock_subtitle),
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

    // When a decoy PIN is configured we re-skin the lock screen with the
    // exact same neutral teal palette the decoy notes app uses. That way
    // the lock screen → notes transition (when the snooper enters the
    // decoy PIN) shows no jump in colour, no Transition lavender flash, no
    // "wait that's a different app" tell. The branded header (heart +
    // "Transition") is also swapped for a generic lock icon + the literal
    // label "Notes" so the whole screen reads as the notes app's
    // passcode gate.
    val unlockContent: @Composable () -> Unit = {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxSize(),
            ) {
                Spacer(modifier = Modifier.weight(1f))
                Header(decoy = vm.hasDecoy)
                Subtitle(state, vm.mode)
                Spacer(modifier = Modifier.height(30.dp))

                when (val s = state) {
                    UnlockViewModel.State.AwaitingPin ->
                        PinGate(onSubmit = vm::submitPin, onBiometric = { vm.attemptAutoUnlock(activity, biometricCopy) })
                    UnlockViewModel.State.AwaitingPassphrase ->
                        PassphraseStage(
                            onSubmit = { pp -> vm.submitPassphrase(pp, activity, biometricCopy) },
                            showBiometricButton = vm.mode == VaultPrefs.Mode.KEYSTORE_PASSPHRASE,
                            onBiometric = { vm.attemptAutoUnlock(activity, biometricCopy) },
                        )
                    UnlockViewModel.State.AccessGranted,
                    UnlockViewModel.State.InProgress ->
                        CircularProgressIndicator()
                    is UnlockViewModel.State.Failed ->
                        Text(
                            stringResource(R.string.unlock_error_prefix, s.reason),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    is UnlockViewModel.State.Throttled ->
                        Text(
                            stringResource(
                                R.string.unlock_throttled_fmt,
                                ((s.remainingMs + 999L) / 1000L),
                            ),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    UnlockViewModel.State.Idle -> Unit
                    UnlockViewModel.State.Success,
                    UnlockViewModel.State.Wiped,
                    UnlockViewModel.State.Decoy -> Unit // handled above
                }

                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }

    if (vm.hasDecoy) {
        MaterialTheme(
            colorScheme = DecoyColors,
            typography = MaterialTheme.typography,
            content = unlockContent,
        )
    } else {
        unlockContent()
    }
}

@Composable
private fun Header(decoy: Boolean) {
    Box(
        modifier = Modifier
            .size(72.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(22.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            // Lock icon when a decoy is active so the header matches the
            // generic "Notes" identity; the branded heart only shows on the
            // real, unmasked app lock screen.
            if (decoy) Icons.Filled.Lock else Icons.Filled.Favorite,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(38.dp),
        )
    }
    Spacer(modifier = Modifier.height(18.dp))
    Text(
        stringResource(if (decoy) R.string.alias_notes else R.string.app_name),
        style = MaterialTheme.typography.headlineSmall,
    )
}

@Composable
private fun Subtitle(state: UnlockViewModel.State, mode: VaultPrefs.Mode?) {
    val text = when {
        mode == null -> stringResource(R.string.unlock_not_initialized)
        mode == VaultPrefs.Mode.KEYSTORE_ONLY ||
            mode == VaultPrefs.Mode.KEYSTORE_BIOMETRIC ->
            stringResource(R.string.unlock_prompt_auto)
        state == UnlockViewModel.State.AwaitingPin ->
            stringResource(R.string.unlock_prompt_pin)
        state == UnlockViewModel.State.AwaitingPassphrase ->
            stringResource(R.string.unlock_prompt_passphrase)
        state == UnlockViewModel.State.InProgress ->
            stringResource(R.string.unlock_in_progress)
        else -> ""
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun PinGate(onSubmit: (String) -> Unit, onBiometric: () -> Unit) {
    var pin by remember { mutableStateOf("") }

    // Auto-submit when we reach 4 digits.
    LaunchedEffect(pin) {
        if (pin.length == 4) {
            onSubmit(pin)
            pin = ""
        }
    }

    PinDots(filled = pin.length, capacity = 4)
    Spacer(modifier = Modifier.height(40.dp))
    Keypad(
        onDigit = { d -> if (pin.length < 4) pin += d },
        onBackspace = { if (pin.isNotEmpty()) pin = pin.dropLast(1) },
        onBiometric = onBiometric,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PassphraseStage(
    onSubmit: (String) -> Unit,
    showBiometricButton: Boolean,
    onBiometric: () -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = passphrase,
            onValueChange = { passphrase = it },
            label = { Text(stringResource(R.string.passphrase_label)) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, platformImeOptions = androidx.compose.ui.text.input.PlatformImeOptions("flagNoPersonalizedLearning")),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { onSubmit(passphrase) },
            enabled = passphrase.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.action_unlock)) }
        if (showBiometricButton) {
            Surface(
                onClick = onBiometric,
                shape = CircleShape,
                color = Color.Transparent,
                modifier = Modifier.size(56.dp),
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.Fingerprint,
                        contentDescription = stringResource(R.string.unlock_biometric_cd),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PinDots(filled: Int, capacity: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        repeat(capacity) { i ->
            val isFilled = i < filled
            Box(
                modifier = Modifier
                    .size(15.dp)
                    .background(
                        if (isFilled) MaterialTheme.colorScheme.primary else Color.Transparent,
                        CircleShape,
                    )
                    .border(
                        width = 2.dp,
                        color = if (isFilled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        shape = CircleShape,
                    ),
            )
        }
    }
}

@Composable
private fun Keypad(
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    onBiometric: () -> Unit,
) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("bio", "0", "del"),
    )
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                row.forEach { k ->
                    when (k) {
                        "bio" -> Key(onClick = onBiometric, background = Color.Transparent) {
                            Icon(
                                Icons.Filled.Fingerprint,
                                contentDescription = stringResource(R.string.unlock_biometric_cd),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(30.dp),
                            )
                        }
                        "del" -> Key(onClick = onBackspace, background = Color.Transparent) {
                            Icon(
                                Icons.Filled.Backspace,
                                contentDescription = stringResource(R.string.unlock_delete_cd),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(26.dp),
                            )
                        }
                        else -> Key(
                            onClick = { onDigit(k) },
                            background = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            Text(
                                k,
                                fontSize = 28.sp,
                                fontWeight = FontWeight.W500,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Key(
    onClick: () -> Unit,
    background: Color,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = background,
        modifier = Modifier.size(72.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            content()
        }
    }
}
