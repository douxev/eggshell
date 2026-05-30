package com.douxev.eggshell.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import com.douxev.eggshell.R
import com.douxev.eggshell.data.VaultRepository
import com.douxev.eggshell.security.VaultPrefs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    vm: OnboardingViewModel = hiltViewModel(),
) {
    val step by vm.step.collectAsState()
    val error by vm.error.collectAsState()
    val activity = LocalContext.current as FragmentActivity
    val biometricCopy = VaultRepository.BiometricCopy(
        title = stringResource(R.string.biometric_setup_title),
        subtitle = stringResource(R.string.biometric_setup_subtitle),
        cancel = stringResource(R.string.action_cancel),
    )

    if (step is OnboardingViewModel.Step.Done) {
        onComplete()
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.onboarding_title)) })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            when (val s = step) {
                is OnboardingViewModel.Step.Welcome -> WelcomeStep(onNext = vm::next)
                is OnboardingViewModel.Step.PickMode -> PickModeStep(
                    onModePicked = { vm.pickMode(it, activity, biometricCopy) },
                    onBack = vm::back,
                )
                is OnboardingViewModel.Step.SetupPassphrase -> PassphraseStep(
                    mode = s.mode,
                    onSubmit = vm::submitPassphrase,
                    onBack = vm::back,
                )
                is OnboardingViewModel.Step.Confirming -> LoadingStep()
                is OnboardingViewModel.Step.Done -> {}
            }
            error?.let {
                Text(
                    stringResource(R.string.onboarding_error_prefix, it),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun WelcomeStep(onNext: () -> Unit) {
    Text(stringResource(R.string.onboarding_welcome_title), style = MaterialTheme.typography.headlineSmall)
    Text(stringResource(R.string.onboarding_welcome_body))
    Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.action_start))
    }
}

@Composable
private fun PickModeStep(
    onModePicked: (VaultPrefs.Mode) -> Unit,
    onBack: () -> Unit,
) {
    Text(stringResource(R.string.onboarding_pick_mode_title), style = MaterialTheme.typography.titleLarge)
    Text(stringResource(R.string.onboarding_pick_mode_body))

    ModeCard(
        title = stringResource(R.string.mode_keystore_only_title),
        body = stringResource(R.string.mode_keystore_only_body),
        forYou = stringResource(R.string.mode_keystore_only_for),
        onClick = { onModePicked(VaultPrefs.Mode.KEYSTORE_ONLY) },
    )
    ModeCard(
        title = stringResource(R.string.mode_keystore_biometric_title),
        body = stringResource(R.string.mode_keystore_biometric_body),
        forYou = stringResource(R.string.mode_keystore_biometric_for),
        onClick = { onModePicked(VaultPrefs.Mode.KEYSTORE_BIOMETRIC) },
    )
    ModeCard(
        title = stringResource(R.string.mode_keystore_passphrase_title),
        body = stringResource(R.string.mode_keystore_passphrase_body),
        forYou = stringResource(R.string.mode_keystore_passphrase_for),
        onClick = { onModePicked(VaultPrefs.Mode.KEYSTORE_PASSPHRASE) },
    )
    ModeCard(
        title = stringResource(R.string.mode_paranoid_title),
        body = stringResource(R.string.mode_paranoid_body),
        forYou = stringResource(R.string.mode_paranoid_for),
        onClick = { onModePicked(VaultPrefs.Mode.PARANOID) },
    )
    OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.action_back))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeCard(title: String, body: String, forYou: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium)
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.padding(top = 6.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                androidx.compose.material3.Icon(
                    Icons.Filled.Info,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "  " + forYou,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PassphraseStep(
    mode: VaultPrefs.Mode,
    onSubmit: (String) -> Unit,
    onBack: () -> Unit,
) {
    // Use plain `remember`, not `rememberSaveable`: the latter would
    // serialise the passphrase into the saved-state Bundle, where it
    // would persist across process death + be visible via `dumpsys`.
    var pass1 by remember { mutableStateOf("") }
    var pass2 by remember { mutableStateOf("") }
    val mismatch = pass1.isNotEmpty() && pass2.isNotEmpty() && pass1 != pass2
    val tooShort = pass1.isNotEmpty() && pass1.length < MIN_PASSPHRASE_LEN
    val canSubmit = pass1.isNotEmpty() && pass1 == pass2 && !tooShort

    Text(
        stringResource(
            if (mode == VaultPrefs.Mode.PARANOID) R.string.passphrase_step_title_paranoid
            else R.string.passphrase_step_title_passphrase
        ),
        style = MaterialTheme.typography.titleLarge,
    )
    Text(stringResource(R.string.passphrase_step_body, MIN_PASSPHRASE_LEN))

    OutlinedTextField(
        value = pass1,
        onValueChange = { pass1 = it },
        label = { Text(stringResource(R.string.passphrase_label)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, platformImeOptions = androidx.compose.ui.text.input.PlatformImeOptions("flagNoPersonalizedLearning")),
        visualTransformation = PasswordVisualTransformation(),
        isError = tooShort,
        supportingText = if (tooShort) {
            { Text(stringResource(R.string.passphrase_too_short, MIN_PASSPHRASE_LEN)) }
        } else null,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = pass2,
        onValueChange = { pass2 = it },
        label = { Text(stringResource(R.string.passphrase_confirm_label)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, platformImeOptions = androidx.compose.ui.text.input.PlatformImeOptions("flagNoPersonalizedLearning")),
        visualTransformation = PasswordVisualTransformation(),
        isError = mismatch,
        supportingText = if (mismatch) {
            { Text(stringResource(R.string.passphrase_mismatch)) }
        } else null,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        enabled = canSubmit,
        onClick = { onSubmit(pass1) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.action_create))
    }
    OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.action_back))
    }
}

@Composable
private fun LoadingStep() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CircularProgressIndicator()
        Text(stringResource(R.string.onboarding_creating))
    }
}

// Below this, brute-force is fast enough that Argon2id alone can't save us.
private const val MIN_PASSPHRASE_LEN = 8
