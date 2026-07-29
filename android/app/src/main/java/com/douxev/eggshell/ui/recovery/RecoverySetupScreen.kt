package com.douxev.eggshell.ui.recovery

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.platform.LocalContext
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.douxev.eggshell.R
import com.douxev.eggshell.data.VaultRepository
import com.douxev.eggshell.ui.common.PasswordField
import com.douxev.eggshell.ui.components.CardVariant
import com.douxev.eggshell.ui.components.EggCard

/**
 * The gate that stands between unlocking and the app for anyone in biometric
 * mode with no second wrap of the master key.
 *
 * Deliberately not skippable, and deliberately not a banner. The failure it
 * prevents is silent and total — Android destroys the Keystore key on the next
 * fingerprint enrollment, and the vault becomes unreadable with no warning and
 * no way back. A dismissible notice would be tapped away by exactly the people
 * who most need it, so this one has no dismiss, no back, and no "later".
 *
 * The user is not trapped, though: this appears *after* a successful unlock, so
 * the alternative to setting a key here is closing the app, not losing access.
 */
@Composable
fun RecoverySetupScreen(
    onDone: () -> Unit,
    vm: RecoverySetupViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    val activity = LocalContext.current as FragmentActivity
    val biometricCopy = VaultRepository.BiometricCopy(
        title = stringResource(R.string.recovery_setup_confirm_title),
        subtitle = stringResource(R.string.recovery_setup_confirm_subtitle),
        cancel = stringResource(R.string.action_cancel),
    )

    // Swallow the system back gesture: this screen has exactly one exit.
    BackHandler(enabled = true) { }

    if (state is RecoverySetupViewModel.State.Done) { onDone(); return }

    var secret by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }

    val tooShort = secret.isNotEmpty() && secret.length < RecoverySetupViewModel.MIN_LENGTH
    val mismatch = confirm.isNotEmpty() && confirm != secret
    val canSubmit = secret.length >= RecoverySetupViewModel.MIN_LENGTH &&
        confirm == secret &&
        state !is RecoverySetupViewModel.State.InProgress

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 26.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Filled.Key,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(44.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.recovery_setup_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(22.dp))
            EggCard(variant = CardVariant.Error) {
                Text(
                    stringResource(R.string.recovery_setup_why_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    stringResource(R.string.recovery_setup_why_body),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            Spacer(Modifier.height(14.dp))
            Text(
                stringResource(R.string.recovery_setup_why_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(26.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                PasswordField(
                    value = secret,
                    onValueChange = { secret = it },
                    label = stringResource(R.string.recovery_setup_field_secret),
                    isError = tooShort,
                    supportingText = {
                        Text(
                            stringResource(
                                R.string.recovery_setup_min_hint,
                                RecoverySetupViewModel.MIN_LENGTH,
                            )
                        )
                    },
                )
                PasswordField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    label = stringResource(R.string.recovery_setup_field_confirm),
                    isError = mismatch,
                    supportingText = if (mismatch) {
                        { Text(stringResource(R.string.recovery_setup_mismatch)) }
                    } else null,
                )
            }

            (state as? RecoverySetupViewModel.State.Failed)?.let { failed ->
                Spacer(Modifier.height(14.dp))
                Text(
                    stringResource(R.string.recovery_setup_error, failed.reason),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                )
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { vm.submit(secret, activity, biometricCopy) },
                enabled = canSubmit,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (state is RecoverySetupViewModel.State.InProgress) {
                        stringResource(R.string.recovery_setup_working)
                    } else {
                        stringResource(R.string.recovery_setup_action)
                    }
                )
            }
        }
    }
}

@HiltViewModel
class RecoverySetupViewModel @Inject constructor(
    private val repo: VaultRepository,
) : ViewModel() {

    sealed interface State {
        data object Editing : State
        data object InProgress : State
        data object Done : State
        data class Failed(val reason: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Editing)
    val state: StateFlow<State> = _state.asStateFlow()

    fun submit(
        secret: String,
        activity: FragmentActivity?,
        biometricCopy: VaultRepository.BiometricCopy?,
    ) {
        if (_state.value is State.InProgress) return
        _state.value = State.InProgress
        viewModelScope.launch {
            _state.value = when (val out = repo.setRecoverySecret(secret, activity, biometricCopy)) {
                is VaultRepository.RecoveryOutcome.Success -> State.Done
                is VaultRepository.RecoveryOutcome.Failed -> State.Failed(out.reason)
            }
        }
    }

    companion object {
        /**
         * Short enough not to push people towards writing it on a sticky note,
         * long enough that Argon2id has something to work with — this wrap is
         * the one that has no Keystore layer in front of it.
         */
        const val MIN_LENGTH = 8
    }
}
