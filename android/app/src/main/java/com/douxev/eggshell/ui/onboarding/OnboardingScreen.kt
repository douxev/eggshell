package com.douxev.eggshell.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.EnhancedEncryption
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import com.douxev.eggshell.R
import com.douxev.eggshell.data.AppAliasManager
import com.douxev.eggshell.data.VaultRepository
import com.douxev.eggshell.security.VaultPrefs
import com.douxev.eggshell.ui.common.EncryptionNoteCard
import com.douxev.eggshell.ui.common.MIN_PASSPHRASE_LEN
import com.douxev.eggshell.ui.common.PasswordField
import com.douxev.eggshell.ui.common.currentFragmentActivity
import com.douxev.eggshell.ui.components.ActionBand
import com.douxev.eggshell.ui.components.CardVariant
import com.douxev.eggshell.ui.components.EggCard
import com.douxev.eggshell.ui.components.ErrorCard
import com.douxev.eggshell.ui.components.SectionTitle
import com.douxev.eggshell.ui.theme.AppTheme
import com.douxev.eggshell.ui.theme.EggShapes
import com.douxev.eggshell.ui.theme.themeSwatch

/**
 * First run, in three segments (§6.14).
 *
 * Step 1 asks a situation ("qui peut prendre ton téléphone ?") rather than a
 * lock mode, and the answer configures the vault. Steps 2 and 3 carry what the
 * old seven-page wizard used to spread out: the modules and the first treatment
 * on one side, the theme, the secret-phrase warning and the closing note on the
 * other.
 *
 * Every form the flow can show has its state hoisted here, so the action band
 * at the bottom always drives the step in front of the user — the band is a
 * reserved strip, never a button floating over the content.
 */
@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    vm: OnboardingViewModel = hiltViewModel(),
) {
    val step by vm.step.collectAsState()
    val error by vm.error.collectAsState()
    val answer by vm.answer.collectAsState()
    // A Paranoid key derivation takes seconds on purpose, and « Passer » would
    // otherwise start a second, concurrent vault creation on top of it.
    val provisioning by vm.provisioning.collectAsState()
    val activity = currentFragmentActivity()
    val biometricCopy = VaultRepository.BiometricCopy(
        title = stringResource(R.string.biometric_setup_title),
        subtitle = stringResource(R.string.biometric_setup_subtitle),
        cancel = stringResource(R.string.action_cancel),
    )

    // Plain `remember` for anything secret: `rememberSaveable` would serialise
    // the passphrase and the PINs into the saved-state Bundle.
    var pass1 by remember { mutableStateOf("") }
    var pass2 by remember { mutableStateOf("") }
    var accessPin by remember { mutableStateOf("") }
    var decoyPin by remember { mutableStateOf("") }
    var iconVariant by remember { mutableStateOf(AppAliasManager.Variant.DEFAULT) }
    var pickedMode by remember { mutableStateOf<VaultPrefs.Mode?>(null) }

    var medName by rememberSaveable { mutableStateOf("") }
    var medDose by rememberSaveable { mutableStateOf("") }
    var medUnit by rememberSaveable { mutableStateOf("") }
    var medRemind by rememberSaveable { mutableStateOf(false) }
    var medHour by rememberSaveable { mutableStateOf("8") }
    var medMinute by rememberSaveable { mutableStateOf("0") }
    var labNudge by rememberSaveable { mutableStateOf(false) }
    var photoNudge by rememberSaveable { mutableStateOf(false) }
    var voiceNudge by rememberSaveable { mutableStateOf(false) }
    var askSkip by rememberSaveable { mutableStateOf(false) }

    // A reminder the user can't see fire is worse than no reminder: ask for the
    // notification permission the moment they turn one on.
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* the schedule still exists if denied, it just stays silent */ }
    val requestNotifications: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Leaving a step clears whatever secret its form was holding.
    LaunchedEffect(step) {
        if (step !is OnboardingViewModel.Step.GuidedPassphrase &&
            step !is OnboardingViewModel.Step.SetupPassphrase
        ) {
            pass1 = ""
            pass2 = ""
        }
        if (step !is OnboardingViewModel.Step.GuidedDecoy) {
            accessPin = ""
            decoyPin = ""
        }
    }

    if (step is OnboardingViewModel.Step.Done) {
        onComplete()
        return
    }

    val labLabel = stringResource(R.string.reminders_lab_default_label)
    val photoLabel = stringResource(R.string.reminders_photo_default_label)
    val voiceLabel = stringResource(R.string.reminders_voice_default_label)

    val band: BandAction? = when (val s = step) {
        is OnboardingViewModel.Step.Security -> BandAction(
            label = stringResource(R.string.action_continue),
            enabled = answer != null,
            onClick = { vm.commitAnswer(activity, biometricCopy) },
        )
        is OnboardingViewModel.Step.GuidedPassphrase,
        is OnboardingViewModel.Step.SetupPassphrase -> BandAction(
            label = stringResource(R.string.action_create),
            enabled = pass1.length >= MIN_PASSPHRASE_LEN && pass1 == pass2,
            onClick = {
                if (s is OnboardingViewModel.Step.GuidedPassphrase) {
                    vm.submitGuidedPassphrase(pass1)
                } else {
                    vm.submitPassphrase(pass1)
                }
            },
        )
        is OnboardingViewModel.Step.GuidedDecoy -> BandAction(
            label = stringResource(R.string.onboarding_guided_decoy_set),
            enabled = accessPin.length == 4 && decoyPin.length == 4 && accessPin != decoyPin,
            onClick = { vm.submitGuidedDecoy(accessPin, decoyPin) },
        )
        is OnboardingViewModel.Step.GuidedIcon -> BandAction(
            label = stringResource(R.string.action_continue),
            enabled = true,
            onClick = {
                if (iconVariant == AppAliasManager.Variant.DEFAULT) vm.skipIcon()
                else vm.chooseIcon(iconVariant)
            },
        )
        is OnboardingViewModel.Step.PickMode -> BandAction(
            label = stringResource(R.string.action_continue),
            enabled = pickedMode != null,
            onClick = { pickedMode?.let { vm.pickMode(it, activity, biometricCopy) } },
        )
        is OnboardingViewModel.Step.Modules -> BandAction(
            label = stringResource(R.string.action_continue),
            enabled = true,
            onClick = {
                val hour = medHour.toIntOrNull()?.takeIf { it in 0..23 }
                val minute = medMinute.toIntOrNull()?.takeIf { it in 0..59 }
                vm.proceedFromModules(
                    medication = medName.takeIf { it.isNotBlank() }?.let {
                        OnboardingViewModel.FirstMedication(
                            name = it,
                            dose = medDose.replace(',', '.').toDoubleOrNull(),
                            unit = medUnit.ifBlank { null },
                            reminderHour = if (medRemind) hour else null,
                            reminderMinute = if (medRemind) minute else null,
                        )
                    },
                    labReminder = if (labNudge) {
                        OnboardingViewModel.ReminderRequest(labLabel, LAB_INTERVAL_DAYS)
                    } else null,
                    photoReminder = if (photoNudge) {
                        OnboardingViewModel.ReminderRequest(photoLabel, MEDIA_INTERVAL_DAYS)
                    } else null,
                    voiceReminder = if (voiceNudge) {
                        OnboardingViewModel.ReminderRequest(voiceLabel, MEDIA_INTERVAL_DAYS)
                    } else null,
                )
            },
        )
        is OnboardingViewModel.Step.Look -> BandAction(
            label = stringResource(R.string.access_onboarding_start),
            enabled = true,
            onClick = vm::finish,
        )
        else -> null
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        bottomBar = {
            if (band != null) {
                ActionBand(alignment = Alignment.Center) {
                    Button(
                        onClick = band.onClick,
                        enabled = band.enabled,
                        shape = EggShapes.Pill,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                    ) { Text(band.label, style = MaterialTheme.typography.titleMedium) }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            ProgressRow(
                index = vm.progressIndex(step),
                canGoBack = vm.canGoBack(step),
                canSkip = !provisioning,
                onBack = vm::back,
                onSkip = { askSkip = true },
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = SidePad),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Spacer(Modifier.height(16.dp))
                error?.let {
                    ErrorCard(message = stringResource(R.string.onboarding_error_prefix, it))
                }
                when (val s = step) {
                    is OnboardingViewModel.Step.Security -> SecurityStep(
                        selected = answer,
                        onSelect = vm::selectAnswer,
                        onManual = vm::chooseAdvanced,
                    )
                    is OnboardingViewModel.Step.GuidedPassphrase -> PassphraseForm(
                        mode = VaultPrefs.Mode.PARANOID,
                        pass1 = pass1,
                        pass2 = pass2,
                        onPass1 = { pass1 = it },
                        onPass2 = { pass2 = it },
                    )
                    is OnboardingViewModel.Step.SetupPassphrase -> PassphraseForm(
                        mode = s.mode,
                        pass1 = pass1,
                        pass2 = pass2,
                        onPass1 = { pass1 = it },
                        onPass2 = { pass2 = it },
                    )
                    is OnboardingViewModel.Step.GuidedDecoy -> DecoyForm(
                        accessPin = accessPin,
                        decoyPin = decoyPin,
                        onAccessPin = { accessPin = it },
                        onDecoyPin = { decoyPin = it },
                        onSkip = vm::skipDecoy,
                    )
                    is OnboardingViewModel.Step.GuidedIcon -> IconForm(
                        selected = iconVariant,
                        onSelect = { iconVariant = it },
                    )
                    is OnboardingViewModel.Step.PickMode -> ModePickerForm(
                        selected = pickedMode,
                        onSelect = { pickedMode = it },
                    )
                    is OnboardingViewModel.Step.Confirming -> ConfirmingStep()
                    is OnboardingViewModel.Step.Modules -> ModulesStep(
                        vm = vm,
                        medName = medName,
                        onMedName = { medName = it },
                        medDose = medDose,
                        onMedDose = { medDose = it },
                        medUnit = medUnit,
                        onMedUnit = { medUnit = it },
                        medRemind = medRemind,
                        onMedRemind = { medRemind = it; if (it) requestNotifications() },
                        medHour = medHour,
                        onMedHour = { medHour = it },
                        medMinute = medMinute,
                        onMedMinute = { medMinute = it },
                        labNudge = labNudge,
                        onLabNudge = { labNudge = it; if (it) requestNotifications() },
                        photoNudge = photoNudge,
                        onPhotoNudge = { photoNudge = it; if (it) requestNotifications() },
                        voiceNudge = voiceNudge,
                        onVoiceNudge = { voiceNudge = it; if (it) requestNotifications() },
                    )
                    is OnboardingViewModel.Step.Look -> LookStep(vm)
                    is OnboardingViewModel.Step.Done -> Unit
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    if (askSkip) {
        AlertDialog(
            onDismissRequest = { askSkip = false },
            title = { Text(stringResource(R.string.access_onboarding_skip_confirm_title)) },
            text = { Text(stringResource(R.string.access_onboarding_skip_confirm_body)) },
            confirmButton = {
                TextButton(onClick = { askSkip = false; vm.skipAll() }) {
                    Text(stringResource(R.string.access_onboarding_skip_confirm_yes))
                }
            },
            dismissButton = {
                TextButton(onClick = { askSkip = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

private data class BandAction(
    val label: String,
    val enabled: Boolean,
    val onClick: () -> Unit,
)

// -- Chrome -------------------------------------------------------------------

@Composable
private fun ProgressRow(
    index: Int,
    canGoBack: Boolean,
    /** False while a vault is being created — skipping would start a second one. */
    canSkip: Boolean,
    onBack: () -> Unit,
    onSkip: () -> Unit,
) {
    val progress = stringResource(R.string.access_onboarding_progress_cd, index + 1)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = SidePad, top = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (canGoBack) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.access_onboarding_back_cd),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Spacer(Modifier.width(SidePad - 4.dp))
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            modifier = Modifier.semantics { contentDescription = progress },
        ) {
            repeat(STEP_COUNT) { i ->
                Box(
                    modifier = Modifier
                        .width(24.dp)
                        .height(4.dp)
                        .background(
                            if (i <= index) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            },
                            EggShapes.Pill,
                        ),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onSkip, enabled = canSkip) {
            Text(
                stringResource(R.string.access_onboarding_skip),
                style = MaterialTheme.typography.labelSmall,
                color = if (canSkip) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
            )
        }
    }
}

@Composable
private fun StepTitle(title: String, body: String) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

// -- Step 1 -------------------------------------------------------------------

@Composable
private fun SecurityStep(
    selected: OnboardingViewModel.Answer?,
    onSelect: (OnboardingViewModel.Answer) -> Unit,
    onManual: () -> Unit,
) {
    StepTitle(
        title = stringResource(R.string.access_onboarding_who_title),
        body = stringResource(R.string.access_onboarding_who_body),
    )
    Spacer(Modifier.height(16.dp))
    AnswerCard(
        icon = Icons.Filled.CheckCircle,
        title = stringResource(R.string.access_onboarding_who_calm_title),
        body = stringResource(R.string.access_onboarding_who_calm_body),
        variant = CardVariant.Low,
        selected = selected == OnboardingViewModel.Answer.Calm,
        onClick = { onSelect(OnboardingViewModel.Answer.Calm) },
    )
    AnswerCard(
        icon = Icons.Filled.Fingerprint,
        title = stringResource(R.string.access_onboarding_who_close_title),
        body = stringResource(R.string.access_onboarding_who_close_body),
        variant = CardVariant.Low,
        selected = selected == OnboardingViewModel.Answer.CloseCircle,
        onClick = { onSelect(OnboardingViewModel.Answer.CloseCircle) },
    )
    AnswerCard(
        icon = Icons.Filled.EnhancedEncryption,
        title = stringResource(R.string.access_onboarding_who_forced_title),
        body = stringResource(R.string.access_onboarding_who_forced_body),
        variant = CardVariant.Primary,
        selected = selected == OnboardingViewModel.Answer.CanBeForced,
        onClick = { onSelect(OnboardingViewModel.Answer.CanBeForced) },
        endorsement = stringResource(R.string.access_onboarding_who_forced_endorsement),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        TextButton(onClick = onManual) {
            Text(
                stringResource(R.string.access_onboarding_manual),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun AnswerCard(
    icon: ImageVector,
    title: String,
    body: String,
    variant: CardVariant,
    selected: Boolean,
    onClick: () -> Unit,
    endorsement: String? = null,
) {
    val primary = variant == CardVariant.Primary
    val tileContainer = if (primary) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val tileContent = if (primary) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    EggCard(
        variant = variant,
        padding = PaddingValues(18.dp),
        onClick = onClick,
        modifier = if (selected) {
            Modifier.border(2.dp, MaterialTheme.colorScheme.primary, EggShapes.Card)
        } else {
            Modifier
        },
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(tileContainer, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = tileContent,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (primary) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            // A glyph as well as the ring: selection is never carried by colour
            // alone (§10).
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        if (endorsement != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                modifier = Modifier.padding(top = 12.dp),
            ) {
                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(17.dp))
                Text(endorsement, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

// -- Step 1, guided branch and manual picker ----------------------------------

@Composable
private fun PassphraseForm(
    mode: VaultPrefs.Mode,
    pass1: String,
    pass2: String,
    onPass1: (String) -> Unit,
    onPass2: (String) -> Unit,
) {
    val mismatch = pass1.isNotEmpty() && pass2.isNotEmpty() && pass1 != pass2
    val tooShort = pass1.isNotEmpty() && pass1.length < MIN_PASSPHRASE_LEN

    StepTitle(
        title = stringResource(
            if (mode == VaultPrefs.Mode.PARANOID) R.string.passphrase_step_title_paranoid
            else R.string.passphrase_step_title_passphrase
        ),
        body = stringResource(R.string.passphrase_step_body, MIN_PASSPHRASE_LEN),
    )
    Spacer(Modifier.height(8.dp))
    PasswordField(
        value = pass1,
        onValueChange = onPass1,
        label = stringResource(R.string.passphrase_label),
        isError = tooShort,
        supportingText = if (tooShort) {
            { Text(stringResource(R.string.passphrase_too_short, MIN_PASSPHRASE_LEN)) }
        } else null,
        modifier = Modifier.fillMaxWidth(),
    )
    PasswordField(
        value = pass2,
        onValueChange = onPass2,
        label = stringResource(R.string.passphrase_confirm_label),
        isError = mismatch,
        supportingText = if (mismatch) {
            { Text(stringResource(R.string.passphrase_mismatch)) }
        } else null,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun DecoyForm(
    accessPin: String,
    decoyPin: String,
    onAccessPin: (String) -> Unit,
    onDecoyPin: (String) -> Unit,
    onSkip: () -> Unit,
) {
    val bothFilled = accessPin.length == 4 && decoyPin.length == 4
    val same = bothFilled && accessPin == decoyPin

    StepTitle(
        title = stringResource(R.string.onboarding_guided_decoy_title),
        body = stringResource(R.string.onboarding_guided_decoy_body),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = accessPin,
        onValueChange = { onAccessPin(it.filter(Char::isDigit).take(4)) },
        label = { Text(stringResource(R.string.settings_access_pin_label)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        singleLine = true,
        shape = EggShapes.Field,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = decoyPin,
        onValueChange = { onDecoyPin(it.filter(Char::isDigit).take(4)) },
        label = { Text(stringResource(R.string.settings_decoy_pin_label)) },
        isError = same,
        supportingText = if (same) {
            { Text(stringResource(R.string.onboarding_guided_decoy_must_differ)) }
        } else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        singleLine = true,
        shape = EggShapes.Field,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        TextButton(onClick = onSkip) {
            Text(
                stringResource(R.string.onboarding_guided_decoy_skip),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun IconForm(
    selected: AppAliasManager.Variant,
    onSelect: (AppAliasManager.Variant) -> Unit,
) {
    StepTitle(
        title = stringResource(R.string.onboarding_guided_icon_title),
        body = stringResource(R.string.onboarding_guided_icon_body),
    )
    Spacer(Modifier.height(8.dp))
    val options = listOf(
        AppAliasManager.Variant.DEFAULT to R.string.onboarding_guided_icon_keep,
        AppAliasManager.Variant.NOTES to R.string.alias_notes,
        AppAliasManager.Variant.CALCULATOR to R.string.alias_calculator,
        AppAliasManager.Variant.WEATHER to R.string.alias_weather,
    )
    options.forEach { (variant, labelRes) ->
        ChoiceRow(
            title = stringResource(labelRes),
            selected = variant == selected,
            onClick = { onSelect(variant) },
        )
    }
}

@Composable
private fun ModePickerForm(
    selected: VaultPrefs.Mode?,
    onSelect: (VaultPrefs.Mode) -> Unit,
) {
    StepTitle(
        title = stringResource(R.string.onboarding_pick_mode_title),
        body = stringResource(R.string.onboarding_pick_mode_body),
    )
    Spacer(Modifier.height(8.dp))
    EncryptionNoteCard()
    ChoiceRow(
        title = stringResource(R.string.mode_keystore_only_title),
        subtitle = stringResource(R.string.mode_keystore_only_for),
        selected = selected == VaultPrefs.Mode.KEYSTORE_ONLY,
        onClick = { onSelect(VaultPrefs.Mode.KEYSTORE_ONLY) },
    )
    ChoiceRow(
        title = stringResource(R.string.mode_keystore_biometric_title),
        subtitle = stringResource(R.string.mode_keystore_biometric_for),
        selected = selected == VaultPrefs.Mode.KEYSTORE_BIOMETRIC,
        onClick = { onSelect(VaultPrefs.Mode.KEYSTORE_BIOMETRIC) },
    )
    ChoiceRow(
        title = stringResource(R.string.mode_keystore_passphrase_title),
        subtitle = stringResource(R.string.mode_keystore_passphrase_for),
        selected = selected == VaultPrefs.Mode.KEYSTORE_PASSPHRASE,
        onClick = { onSelect(VaultPrefs.Mode.KEYSTORE_PASSPHRASE) },
    )
    ChoiceRow(
        title = stringResource(R.string.mode_paranoid_title),
        subtitle = stringResource(R.string.mode_paranoid_for),
        selected = selected == VaultPrefs.Mode.PARANOID,
        onClick = { onSelect(VaultPrefs.Mode.PARANOID) },
    )
}

@Composable
private fun ChoiceRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    subtitle: String? = null,
) {
    EggCard(
        variant = CardVariant.Low,
        padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        onClick = onClick,
        modifier = if (selected) {
            Modifier.border(2.dp, MaterialTheme.colorScheme.primary, EggShapes.Card)
        } else {
            Modifier
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun ConfirmingStep() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LinearProgressIndicator(modifier = Modifier.width(140.dp))
        Text(
            stringResource(R.string.onboarding_creating),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// -- Step 2 -------------------------------------------------------------------

@Composable
private fun ModulesStep(
    vm: OnboardingViewModel,
    medName: String,
    onMedName: (String) -> Unit,
    medDose: String,
    onMedDose: (String) -> Unit,
    medUnit: String,
    onMedUnit: (String) -> Unit,
    medRemind: Boolean,
    onMedRemind: (Boolean) -> Unit,
    medHour: String,
    onMedHour: (String) -> Unit,
    medMinute: String,
    onMedMinute: (String) -> Unit,
    labNudge: Boolean,
    onLabNudge: (Boolean) -> Unit,
    photoNudge: Boolean,
    onPhotoNudge: (Boolean) -> Unit,
    voiceNudge: Boolean,
    onVoiceNudge: (Boolean) -> Unit,
) {
    val meds by vm.medicationsOn.collectAsState()
    val journal by vm.journalOn.collectAsState()
    val hormones by vm.hormonesOn.collectAsState()
    val weight by vm.weightOn.collectAsState()
    val photos by vm.photosOn.collectAsState()
    val voice by vm.voiceOn.collectAsState()
    val bleeding by vm.bleedingOn.collectAsState()
    val appointments by vm.appointmentsOn.collectAsState()
    val notes by vm.notesOn.collectAsState()

    StepTitle(
        title = stringResource(R.string.access_onboarding_track_title),
        body = stringResource(R.string.access_onboarding_track_body),
    )
    Spacer(Modifier.height(14.dp))
    SectionTitle(stringResource(R.string.access_onboarding_modules_section))
    EggCard(variant = CardVariant.Low, padding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
        ToggleRow(Icons.Filled.Medication, R.string.feature_medications_title, R.string.feature_medications_sub, meds, vm::setMedications)
        ToggleRow(Icons.Filled.Mood, R.string.feature_journal_title, R.string.feature_journal_sub, journal, vm::setJournal)
        ToggleRow(Icons.Filled.Science, R.string.feature_hormones_title, R.string.feature_hormones_sub, hormones, vm::setHormones)
        ToggleRow(Icons.Filled.Straighten, R.string.feature_weight_title, R.string.feature_weight_sub, weight, vm::setWeight)
        ToggleRow(Icons.Filled.PhotoLibrary, R.string.feature_photos_title, R.string.feature_photos_sub, photos, vm::setPhotos)
        ToggleRow(Icons.Filled.Mic, R.string.feature_voice_title, R.string.feature_voice_sub, voice, vm::setVoice)
        ToggleRow(Icons.Filled.WaterDrop, R.string.feature_bleeding_title, R.string.feature_bleeding_sub, bleeding, vm::setBleeding)
        ToggleRow(Icons.Filled.Event, R.string.feature_appointments_title, R.string.feature_appointments_sub, appointments, vm::setAppointments)
        ToggleRow(Icons.Filled.Description, R.string.feature_notes_title, R.string.feature_notes_sub, notes, vm::setNotes)
    }

    if (meds) {
        Spacer(Modifier.height(8.dp))
        SectionTitle(stringResource(R.string.access_onboarding_first_med_section))
        EggCard(variant = CardVariant.Low) {
            Text(
                stringResource(R.string.access_onboarding_first_med_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = medName,
                onValueChange = onMedName,
                label = { Text(stringResource(R.string.med_field_name)) },
                singleLine = true,
                shape = EggShapes.Field,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = medDose,
                    onValueChange = { v -> onMedDose(v.filter { it.isDigit() || it == '.' || it == ',' }) },
                    label = { Text(stringResource(R.string.med_field_default_dose)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    shape = EggShapes.Field,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = medUnit,
                    onValueChange = onMedUnit,
                    label = { Text(stringResource(R.string.med_field_dose_unit)) },
                    singleLine = true,
                    shape = EggShapes.Field,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(4.dp))
            SwitchLine(
                title = stringResource(R.string.access_onboarding_first_med_remind),
                checked = medRemind,
                onChange = onMedRemind,
            )
            if (medRemind) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = medHour,
                        onValueChange = { onMedHour(it.filter(Char::isDigit).take(2)) },
                        label = { Text(stringResource(R.string.schedule_field_hour)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        shape = EggShapes.Field,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = medMinute,
                        onValueChange = { onMedMinute(it.filter(Char::isDigit).take(2)) },
                        label = { Text(stringResource(R.string.schedule_field_minute)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        shape = EggShapes.Field,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    // The old wizard gave the lab / photo / voice nudges a screen each. They are
    // one switch apiece here, and only for the modules actually turned on.
    if (hormones || photos || voice) {
        Spacer(Modifier.height(8.dp))
        SectionTitle(stringResource(R.string.access_onboarding_reminders_section))
        EggCard(variant = CardVariant.Low, padding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
            if (hormones) {
                SwitchLine(
                    title = stringResource(R.string.access_onboarding_reminder_labs),
                    subtitle = stringResource(R.string.access_onboarding_reminder_labs_sub),
                    checked = labNudge,
                    onChange = onLabNudge,
                )
            }
            if (photos) {
                SwitchLine(
                    title = stringResource(R.string.access_onboarding_reminder_photos),
                    subtitle = stringResource(R.string.access_onboarding_reminder_photos_sub),
                    checked = photoNudge,
                    onChange = onPhotoNudge,
                )
            }
            if (voice) {
                SwitchLine(
                    title = stringResource(R.string.access_onboarding_reminder_voice),
                    subtitle = stringResource(R.string.access_onboarding_reminder_voice_sub),
                    checked = voiceNudge,
                    onChange = onVoiceNudge,
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(
    icon: ImageVector,
    titleRes: Int,
    subRes: Int,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 10.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(titleRes), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(subRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SwitchLine(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// -- Step 3 -------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LookStep(vm: OnboardingViewModel) {
    val selected by vm.selectedTheme.collectAsState()

    StepTitle(
        title = stringResource(R.string.access_onboarding_look_title),
        body = stringResource(R.string.access_onboarding_look_body),
    )
    Spacer(Modifier.height(14.dp))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        AppTheme.entries.forEach { theme ->
            ThemeSwatch(
                theme = theme,
                selected = theme == selected,
                onClick = { vm.setTheme(theme) },
            )
        }
    }

    // Only passphrase-derived vaults can be lost for good, so the warning only
    // shows where losing the phrase really means losing everything.
    if (vm.needsBackup) {
        Spacer(Modifier.height(8.dp))
        EggCard(variant = CardVariant.Error) {
            Text(
                stringResource(R.string.onboarding_cfg_backup_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                stringResource(R.string.onboarding_cfg_backup_body),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                stringResource(R.string.onboarding_cfg_backup_hint),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }

    Spacer(Modifier.height(8.dp))
    EggCard(variant = CardVariant.Tertiary) {
        Text(
            stringResource(R.string.access_onboarding_ready_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            stringResource(R.string.access_onboarding_ready_body),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun ThemeSwatch(theme: AppTheme, selected: Boolean, onClick: () -> Unit) {
    val (background, primary) = themeSwatch(theme)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(96.dp)
            .clickable(onClick = onClick),
    ) {
        val shape = RoundedCornerShape(14.dp)
        Box(
            modifier = Modifier
                .width(96.dp)
                .height(56.dp)
                .background(background, shape)
                .then(
                    if (selected) {
                        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
                    } else {
                        Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(modifier = Modifier.size(26.dp).background(primary, CircleShape))
        }
        // The tick sits on the app's own surface, not on the foreign swatch, so
        // it stays legible whatever palette the swatch is showing.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
                )
            }
            Text(
                theme.displayName,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (selected) FontWeight.W700 else FontWeight.W500,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
            )
        }
    }
}

private val SidePad = 20.dp
private const val STEP_COUNT = 3
private const val LAB_INTERVAL_DAYS = 90
private const val MEDIA_INTERVAL_DAYS = 30

// Below this, brute-force is fast enough that Argon2id alone can't save us.
