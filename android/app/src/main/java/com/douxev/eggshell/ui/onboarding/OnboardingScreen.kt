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
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import com.douxev.eggshell.R
import com.douxev.eggshell.data.AppAliasManager
import com.douxev.eggshell.data.VaultRepository
import com.douxev.eggshell.ui.common.EncryptionNoteCard
import com.douxev.eggshell.ui.common.PasswordField
import com.douxev.eggshell.security.VaultPrefs
import com.douxev.eggshell.ui.theme.AppTheme
import com.douxev.eggshell.ui.theme.themeSwatch

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

    // Ask for notifications once we reach a wizard page that can create a
    // reminder — otherwise the reminder is created but never fires.
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* schedule still works if denied, just silent */ }
    LaunchedEffect(step) {
        val page = (step as? OnboardingViewModel.Step.Config)?.page
        val reminderPage = page == OnboardingViewModel.ConfigPage.Medication ||
            page == OnboardingViewModel.ConfigPage.Labs ||
            page == OnboardingViewModel.ConfigPage.Photos ||
            page == OnboardingViewModel.ConfigPage.Voice
        if (reminderPage && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    if (step is OnboardingViewModel.Step.Done) {
        onComplete()
        return
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.onboarding_title)) }) }
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
                is OnboardingViewModel.Step.ChooseScenario -> ScenarioStep(
                    onNoCode = vm::chooseNoCode,
                    onBiometric = { vm.chooseBiometric(activity, biometricCopy) },
                    onAtRisk = vm::chooseAtRisk,
                    onAdvanced = vm::chooseAdvanced,
                    onBack = vm::back,
                )
                is OnboardingViewModel.Step.GuidedPassphrase -> PassphraseStep(
                    mode = VaultPrefs.Mode.PARANOID,
                    onSubmit = vm::submitGuidedPassphrase,
                    onBack = vm::back,
                )
                is OnboardingViewModel.Step.GuidedDecoy -> GuidedDecoyStep(
                    onConfirm = vm::submitGuidedDecoy,
                    onSkip = vm::skipDecoy,
                )
                is OnboardingViewModel.Step.GuidedIcon -> GuidedIconStep(
                    onPick = vm::chooseIcon,
                    onSkip = vm::skipIcon,
                )
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
                is OnboardingViewModel.Step.Config -> ConfigRouter(s.page, vm)
                is OnboardingViewModel.Step.Done -> {}
            }
            error?.let {
                Text(
                    stringResource(R.string.onboarding_error_prefix, it),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ConfigRouter(page: OnboardingViewModel.ConfigPage, vm: OnboardingViewModel) {
    when (page) {
        OnboardingViewModel.ConfigPage.Features -> ConfigFeaturesStep(vm)
        OnboardingViewModel.ConfigPage.Medication -> ConfigMedicationStep(vm)
        OnboardingViewModel.ConfigPage.Labs -> ConfigReminderStep(
            title = stringResource(R.string.onboarding_cfg_labs_title),
            body = stringResource(R.string.onboarding_cfg_labs_body),
            defaultLabel = stringResource(R.string.reminders_lab_default_label),
            defaultDays = "90",
            onActivate = vm::addLabReminder,
            onSkip = vm::nextWizardPage,
        )
        OnboardingViewModel.ConfigPage.Photos -> ConfigReminderStep(
            title = stringResource(R.string.onboarding_cfg_photos_title),
            body = stringResource(R.string.onboarding_cfg_photos_body),
            defaultLabel = stringResource(R.string.reminders_photo_default_label),
            defaultDays = "30",
            onActivate = vm::addPhotoReminder,
            onSkip = vm::nextWizardPage,
        )
        OnboardingViewModel.ConfigPage.Voice -> ConfigReminderStep(
            title = stringResource(R.string.onboarding_cfg_voice_title),
            body = stringResource(R.string.onboarding_cfg_voice_body),
            defaultLabel = stringResource(R.string.reminders_voice_default_label),
            defaultDays = "30",
            onActivate = vm::addVoiceReminder,
            onSkip = vm::nextWizardPage,
        )
        OnboardingViewModel.ConfigPage.Theme -> ConfigThemeStep(vm)
        OnboardingViewModel.ConfigPage.Backup -> ConfigBackupStep(onNext = vm::nextWizardPage)
        OnboardingViewModel.ConfigPage.Export -> ConfigExportStep(onNext = vm::nextWizardPage)
        OnboardingViewModel.ConfigPage.Recap -> ConfigRecapStep(onFinish = vm::finish)
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
private fun ScenarioStep(
    onNoCode: () -> Unit,
    onBiometric: () -> Unit,
    onAtRisk: () -> Unit,
    onAdvanced: () -> Unit,
    onBack: () -> Unit,
) {
    Text(stringResource(R.string.onboarding_scenario_title), style = MaterialTheme.typography.titleLarge)
    Text(stringResource(R.string.onboarding_scenario_body))

    ScenarioCard(
        icon = Icons.Filled.Bolt,
        title = stringResource(R.string.onboarding_scenario_safe_title),
        body = stringResource(R.string.onboarding_scenario_safe_body),
        onClick = onNoCode,
    )
    ScenarioCard(
        icon = Icons.Filled.Fingerprint,
        title = stringResource(R.string.onboarding_scenario_biometric_title),
        body = stringResource(R.string.onboarding_scenario_biometric_body),
        onClick = onBiometric,
    )
    ScenarioCard(
        icon = Icons.Filled.Lock,
        title = stringResource(R.string.onboarding_scenario_atrisk_title),
        body = stringResource(R.string.onboarding_scenario_atrisk_body),
        onClick = onAtRisk,
    )

    TextButton(onClick = onAdvanced, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.onboarding_advanced))
    }
    OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.action_back))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScenarioCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(10.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// -- Setup wizard pages -------------------------------------------------------

@Composable
private fun ConfigFeaturesStep(vm: OnboardingViewModel) {
    val meds by vm.medicationsOn.collectAsState()
    val journal by vm.journalOn.collectAsState()
    val hormones by vm.hormonesOn.collectAsState()
    val weight by vm.weightOn.collectAsState()
    val photos by vm.photosOn.collectAsState()
    val voice by vm.voiceOn.collectAsState()

    Text(stringResource(R.string.onboarding_features_title), style = MaterialTheme.typography.titleLarge)
    Text(stringResource(R.string.onboarding_features_body))

    FeatureToggle(R.string.feature_medications_title, R.string.feature_medications_sub, meds, vm::setMedications)
    FeatureToggle(R.string.feature_journal_title, R.string.feature_journal_sub, journal, vm::setJournal)
    FeatureToggle(R.string.feature_hormones_title, R.string.feature_hormones_sub, hormones, vm::setHormones)
    FeatureToggle(R.string.feature_weight_title, R.string.feature_weight_sub, weight, vm::setWeight)
    FeatureToggle(R.string.feature_photos_title, R.string.feature_photos_sub, photos, vm::setPhotos)
    FeatureToggle(R.string.feature_voice_title, R.string.feature_voice_sub, voice, vm::setVoice)

    Button(onClick = vm::proceedFromFeatures, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.action_continue))
    }
}

@Composable
private fun FeatureToggle(titleRes: Int, subRes: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) },
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(titleRes), style = MaterialTheme.typography.titleMedium)
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
private fun ConfigMedicationStep(vm: OnboardingViewModel) {
    var name by rememberSaveable { mutableStateOf("") }
    var dose by rememberSaveable { mutableStateOf("") }
    var unit by rememberSaveable { mutableStateOf("") }
    var remind by rememberSaveable { mutableStateOf(false) }
    var hourStr by rememberSaveable { mutableStateOf("8") }
    var minuteStr by rememberSaveable { mutableStateOf("0") }

    Text(stringResource(R.string.onboarding_cfg_med_title), style = MaterialTheme.typography.titleLarge)
    Text(stringResource(R.string.onboarding_cfg_med_body))

    OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { Text(stringResource(R.string.med_field_name)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = dose,
            onValueChange = { dose = it.filter { c -> c.isDigit() || c == '.' || c == ',' } },
            label = { Text(stringResource(R.string.med_field_default_dose)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = unit,
            onValueChange = { unit = it },
            label = { Text(stringResource(R.string.med_field_dose_unit)) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.onboarding_cfg_med_remind), modifier = Modifier.weight(1f))
        Switch(checked = remind, onCheckedChange = { remind = it })
    }
    if (remind) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = hourStr,
                onValueChange = { hourStr = it.filter(Char::isDigit).take(2) },
                label = { Text(stringResource(R.string.schedule_field_hour)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = minuteStr,
                onValueChange = { minuteStr = it.filter(Char::isDigit).take(2) },
                label = { Text(stringResource(R.string.schedule_field_minute)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
    }
    Button(
        enabled = name.isNotBlank(),
        onClick = {
            val h = if (remind) hourStr.toIntOrNull()?.takeIf { it in 0..23 } else null
            val m = if (remind) minuteStr.toIntOrNull()?.takeIf { it in 0..59 } else null
            vm.addMedication(
                name = name,
                dose = dose.replace(',', '.').toDoubleOrNull(),
                unit = unit.ifBlank { null },
                reminderHour = if (remind) h else null,
                reminderMinute = if (remind) m else null,
            )
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.onboarding_cfg_med_add)) }
    SkipButton(onSkip = vm::nextWizardPage)
}

@Composable
private fun ConfigReminderStep(
    title: String,
    body: String,
    defaultLabel: String,
    defaultDays: String,
    onActivate: (label: String, days: Int) -> Unit,
    onSkip: () -> Unit,
) {
    var daysStr by rememberSaveable(defaultDays) { mutableStateOf(defaultDays) }
    Text(title, style = MaterialTheme.typography.titleLarge)
    Text(body)
    OutlinedTextField(
        value = daysStr,
        onValueChange = { daysStr = it.filter(Char::isDigit).take(4) },
        label = { Text(stringResource(R.string.reminders_lab_interval_days)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        enabled = daysStr.toIntOrNull()?.let { it > 0 } == true,
        onClick = { daysStr.toIntOrNull()?.let { onActivate(defaultLabel, it) } },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.onboarding_cfg_reminder_activate)) }
    SkipButton(onSkip = onSkip)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConfigThemeStep(vm: OnboardingViewModel) {
    val selected by vm.selectedTheme.collectAsState()
    Text(stringResource(R.string.onboarding_cfg_theme_title), style = MaterialTheme.typography.titleLarge)
    Text(stringResource(R.string.onboarding_cfg_theme_body))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        AppTheme.entries.forEach { theme ->
            ThemeSwatchCard(
                theme = theme,
                selected = theme == selected,
                onClick = { vm.setTheme(theme) },
            )
        }
    }
    Button(onClick = vm::nextWizardPage, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.action_continue))
    }
}

@Composable
private fun ThemeSwatchCard(theme: AppTheme, selected: Boolean, onClick: () -> Unit) {
    val (bg, primary) = themeSwatch(theme)
    val ring = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(96.dp).clickable(onClick = onClick),
    ) {
        val shape = RoundedCornerShape(12.dp)
        Box(
            modifier = Modifier
                .size(width = 96.dp, height = 56.dp)
                .then(
                    if (selected) {
                        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
                    } else {
                        Modifier
                    }
                )
                .background(bg, shape),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(primary, CircleShape),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            theme.displayName,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.W700 else FontWeight.W400,
            color = ring,
            maxLines = 1,
        )
    }
}

@Composable
private fun ConfigBackupStep(onNext: () -> Unit) {
    Text(stringResource(R.string.onboarding_cfg_backup_title), style = MaterialTheme.typography.titleLarge)
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            stringResource(R.string.onboarding_cfg_backup_body),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp),
        )
    }
    Text(
        stringResource(R.string.onboarding_cfg_backup_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.onboarding_cfg_backup_ack))
    }
}

@Composable
private fun ConfigExportStep(onNext: () -> Unit) {
    Text(stringResource(R.string.onboarding_cfg_export_title), style = MaterialTheme.typography.titleLarge)
    Text(stringResource(R.string.onboarding_cfg_export_body))
    Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.action_continue))
    }
}

@Composable
private fun ConfigRecapStep(onFinish: () -> Unit) {
    Text(stringResource(R.string.onboarding_cfg_recap_title), style = MaterialTheme.typography.headlineSmall)
    Text(stringResource(R.string.onboarding_cfg_recap_body))
    Button(onClick = onFinish, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.onboarding_cfg_recap_finish))
    }
}

@Composable
private fun SkipButton(onSkip: () -> Unit) {
    TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.action_later))
    }
}

// -- Guided at-risk + advanced (unchanged) ------------------------------------

@Composable
private fun GuidedDecoyStep(
    onConfirm: (accessPin: String, decoyPin: String) -> Unit,
    onSkip: () -> Unit,
) {
    var accessPin by remember { mutableStateOf("") }
    var decoyPin by remember { mutableStateOf("") }
    val bothFilled = accessPin.length == 4 && decoyPin.length == 4
    val differ = accessPin != decoyPin
    val canConfirm = bothFilled && differ

    Text(stringResource(R.string.onboarding_guided_decoy_title), style = MaterialTheme.typography.titleLarge)
    Text(stringResource(R.string.onboarding_guided_decoy_body))

    OutlinedTextField(
        value = accessPin,
        onValueChange = { accessPin = it.filter(Char::isDigit).take(4) },
        label = { Text(stringResource(R.string.settings_access_pin_label)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = decoyPin,
        onValueChange = { decoyPin = it.filter(Char::isDigit).take(4) },
        label = { Text(stringResource(R.string.settings_decoy_pin_label)) },
        isError = bothFilled && !differ,
        supportingText = if (bothFilled && !differ) {
            { Text(stringResource(R.string.onboarding_guided_decoy_must_differ)) }
        } else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        enabled = canConfirm,
        onClick = { onConfirm(accessPin, decoyPin) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.onboarding_guided_decoy_set))
    }
    TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.onboarding_guided_decoy_skip))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GuidedIconStep(
    onPick: (AppAliasManager.Variant) -> Unit,
    onSkip: () -> Unit,
) {
    Text(stringResource(R.string.onboarding_guided_icon_title), style = MaterialTheme.typography.titleLarge)
    Text(stringResource(R.string.onboarding_guided_icon_body))

    val options = listOf(
        AppAliasManager.Variant.CALCULATOR to R.string.alias_calculator,
        AppAliasManager.Variant.NOTES to R.string.alias_notes,
        AppAliasManager.Variant.WEATHER to R.string.alias_weather,
    )
    options.forEach { (variant, labelRes) ->
        Card(
            onClick = { onPick(variant) },
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Text(
                stringResource(labelRes),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(20.dp),
            )
        }
    }
    TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.onboarding_guided_icon_keep))
    }
}

@Composable
private fun PickModeStep(
    onModePicked: (VaultPrefs.Mode) -> Unit,
    onBack: () -> Unit,
) {
    Text(stringResource(R.string.onboarding_pick_mode_title), style = MaterialTheme.typography.titleLarge)
    Text(stringResource(R.string.onboarding_pick_mode_body))

    EncryptionNoteCard()

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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium)
            Row(modifier = Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
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
    // Plain `remember`, not `rememberSaveable`: the latter would serialise the
    // passphrase into the saved-state Bundle (visible via dumpsys).
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

    PasswordField(
        value = pass1,
        onValueChange = { pass1 = it },
        label = stringResource(R.string.passphrase_label),
        isError = tooShort,
        supportingText = if (tooShort) {
            { Text(stringResource(R.string.passphrase_too_short, MIN_PASSPHRASE_LEN)) }
        } else null,
        modifier = Modifier.fillMaxWidth(),
    )
    PasswordField(
        value = pass2,
        onValueChange = { pass2 = it },
        label = stringResource(R.string.passphrase_confirm_label),
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
