package com.douxev.eggshell.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.FileProvider
import androidx.core.os.LocaleListCompat
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.douxev.eggshell.R
import com.douxev.eggshell.data.AppAliasManager
import com.douxev.eggshell.data.BackupRepository
import com.douxev.eggshell.data.PdfReportExporter
import com.douxev.eggshell.data.SecurityPrefs
import com.douxev.eggshell.data.VaultRepository
import com.douxev.eggshell.security.DecoyVerifier
import com.douxev.eggshell.security.VaultPrefs

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val aliasManager: AppAliasManager,
    private val decoy: DecoyVerifier,
    private val backup: BackupRepository,
    private val pdf: PdfReportExporter,
    private val vault: VaultRepository,
    private val securityPrefs: SecurityPrefs,
) : ViewModel() {

    private val _currentVariant = MutableStateFlow(aliasManager.currentVariant())
    val currentVariant: StateFlow<AppAliasManager.Variant> = _currentVariant.asStateFlow()
    private val _currentMode = MutableStateFlow(vault.currentMode)
    val currentMode: StateFlow<VaultPrefs.Mode?> = _currentMode.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private val _decoyConfigured = MutableStateFlow(decoy.hasDecoyPin)
    val decoyConfigured: StateFlow<Boolean> = _decoyConfigured.asStateFlow()
    val blockScreenshots: StateFlow<Boolean> = securityPrefs.blockScreenshots

    fun setBlockScreenshots(value: Boolean) = securityPrefs.setBlockScreenshots(value)

    fun setVariant(v: AppAliasManager.Variant) {
        aliasManager.setVariant(v)
        _currentVariant.value = v
    }

    fun savePinPair(accessPin: String, decoyPin: String) {
        viewModelScope.launch {
            runCatching {
                val a = accessPin.takeIf { it.isNotBlank() }
                val d = decoyPin.takeIf { it.isNotBlank() }
                require((a == null) == (d == null)) { "les deux PIN sont requis ensemble" }
                if (a != null && d != null) {
                    require(a.length == 4 && d.length == 4) { "chaque PIN doit faire 4 chiffres" }
                    require(a.all(Char::isDigit) && d.all(Char::isDigit)) { "chiffres uniquement" }
                    require(a != d) { "le code d'accès et le PIN de leurre doivent différer" }
                }
                decoy.setPair(a, d)
            }
                .onSuccess {
                    _decoyConfigured.value = decoy.hasDecoyPin
                    _message.value = "OK"
                }
                .onFailure { _message.value = it.message }
        }
    }

    fun clearPinPair() {
        viewModelScope.launch {
            runCatching { decoy.setPair(null, null) }
                .onSuccess {
                    _decoyConfigured.value = decoy.hasDecoyPin
                    _message.value = "OK"
                }
                .onFailure { _message.value = it.message }
        }
    }

    fun changeMode(
        newMode: VaultPrefs.Mode,
        currentPassphrase: String?,
        newPassphrase: String?,
        activity: FragmentActivity?,
        biometricCopy: VaultRepository.BiometricCopy?,
    ) {
        viewModelScope.launch {
            when (val out = vault.changeMode(newMode, currentPassphrase, newPassphrase, activity, biometricCopy)) {
                VaultRepository.ChangeModeOutcome.Success -> {
                    _currentMode.value = vault.currentMode
                    _message.value = "Mode mis à jour."
                }
                VaultRepository.ChangeModeOutcome.RequiresRekey ->
                    _message.value = "Le mode parano nécessite une opération de re-chiffrement, à venir dans une future version."
                is VaultRepository.ChangeModeOutcome.Failed ->
                    _message.value = "Échec : ${out.reason}"
            }
        }
    }

    fun exportToFile(passphrase: String, onReady: (java.io.File) -> Unit) {
        viewModelScope.launch {
            runCatching { backup.exportToCache(passphrase) }
                .onSuccess { onReady(it); _message.value = "exported" }
                .onFailure { _message.value = it.message }
        }
    }

    fun importFromUri(uri: Uri, passphrase: String, onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching { backup.importFromUri(uri, passphrase) }
                .onSuccess { _message.value = "imported"; onDone() }
                .onFailure { _message.value = it.message }
        }
    }

    fun exportPdf(onReady: (java.io.File) -> Unit) {
        viewModelScope.launch {
            runCatching { pdf.generate() }
                .onSuccess { onReady(it) }
                .onFailure { _message.value = it.message }
        }
    }

    fun dismissMessage() { _message.value = null }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onOpenReminders: () -> Unit = {},
    vm: SettingsViewModel = hiltViewModel(),
) {
    val variant by vm.currentVariant.collectAsState()
    val mode by vm.currentMode.collectAsState()
    val message by vm.message.collectAsState()
    val context = LocalContext.current
    val activity = context as? FragmentActivity

    var accessPin by remember { mutableStateOf("") }
    var decoyPin by remember { mutableStateOf("") }
    var exportPass by remember { mutableStateOf("") }
    var importPass by remember { mutableStateOf("") }
    var modeDialog by remember { mutableStateOf<VaultPrefs.Mode?>(null) }

    // Decoy is now available in every security mode — when set, the lock
    // screen always presents a PIN keypad and Keystore-only / biometric modes
    // silently unwrap after the access PIN matches.
    val canUseDecoy = mode != null
    val decoyConfigured by vm.decoyConfigured.collectAsState()

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null && importPass.isNotBlank()) {
            vm.importFromUri(uri, importPass) {
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // -- Language --------------------------------------------------
            Text(stringResource(R.string.settings_section_language), style = MaterialTheme.typography.titleMedium)
            val currentTag: String = remember(LocalConfiguration.current) {
                AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore(',')
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LANGUAGE_OPTIONS.forEach { (tag, label) ->
                    FilterChip(
                        selected = currentTag.equals(tag, ignoreCase = true) ||
                            (tag.isEmpty() && currentTag.isEmpty()),
                        onClick = {
                            AppCompatDelegate.setApplicationLocales(
                                if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList()
                                else LocaleListCompat.forLanguageTags(tag)
                            )
                        },
                        label = { Text(stringResource(label)) },
                    )
                }
            }

            HorizontalDivider()

            // -- Reminders -------------------------------------------------
            Text(stringResource(R.string.settings_section_reminders), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.settings_reminders_hint),
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(
                onClick = onOpenReminders,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.settings_open_reminders)) }

            HorizontalDivider()

            // -- Privacy toggles --------------------------------------------
            Text(stringResource(R.string.settings_section_privacy), style = MaterialTheme.typography.titleMedium)
            val blockScreens by vm.blockScreenshots.collectAsState()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.settings_block_screenshots),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(R.string.settings_block_screenshots_sub),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                androidx.compose.material3.Switch(
                    checked = blockScreens,
                    onCheckedChange = vm::setBlockScreenshots,
                )
            }

            HorizontalDivider()

            // -- Security mode ---------------------------------------------
            Text(stringResource(R.string.settings_section_security), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.settings_current_mode_fmt, mode?.name ?: "—"),
                style = MaterialTheme.typography.bodyMedium,
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                VaultPrefs.Mode.values().forEach { m ->
                    ModeRow(
                        mode = m,
                        selected = m == mode,
                        onClick = { modeDialog = m },
                    )
                }
            }

            HorizontalDivider()

            // -- Masking ---------------------------------------------------
            Text(stringResource(R.string.settings_section_masking), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.settings_current_icon) + ": ${variant.name}", style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppAliasManager.Variant.values().forEach { v ->
                    FilterChip(
                        selected = v == variant,
                        onClick = { vm.setVariant(v) },
                        label = { Text(v.name) },
                    )
                }
            }

            HorizontalDivider()

            // -- Access + Decoy PIN pair (available in every mode) ----------
            Text(stringResource(R.string.settings_decoy_pin), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.settings_decoy_pin_hint), style = MaterialTheme.typography.bodySmall)
            // Honest-portee callout. The PIN here is a UX gate, not a
            // second crypto factor: in KEYSTORE-only / biometric modes the
            // master key is wrapped by Keystore and decryptable without the
            // PIN by anyone with root + Keystore access. The decoy still
            // protects against partner-grab "show me" scenarios; that's
            // the threat model it's designed for.
            androidx.compose.material3.Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.settings_decoy_pin_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp),
                )
            }
            OutlinedTextField(
                value = accessPin,
                onValueChange = { accessPin = it.filter(Char::isDigit).take(4) },
                label = { Text(stringResource(R.string.settings_access_pin_label)) },
                supportingText = { Text(stringResource(R.string.settings_access_pin_hint)) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, platformImeOptions = androidx.compose.ui.text.input.PlatformImeOptions("flagNoPersonalizedLearning")),
                enabled = canUseDecoy,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = decoyPin,
                onValueChange = { decoyPin = it.filter(Char::isDigit).take(4) },
                label = { Text(stringResource(R.string.settings_decoy_pin_label)) },
                supportingText = { Text(stringResource(R.string.settings_decoy_pin_hint_field)) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, platformImeOptions = androidx.compose.ui.text.input.PlatformImeOptions("flagNoPersonalizedLearning")),
                enabled = canUseDecoy,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick = { vm.savePinPair(accessPin, decoyPin) },
                enabled = canUseDecoy &&
                    accessPin.length == 4 && decoyPin.length == 4 && accessPin != decoyPin,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.settings_save_decoy_pin)) }
            OutlinedButton(
                onClick = { vm.clearPinPair(); accessPin = ""; decoyPin = "" },
                enabled = canUseDecoy && decoyConfigured,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.settings_clear_decoy_pin)) }

            HorizontalDivider()

            // -- Backup export ---------------------------------------------
            Text(stringResource(R.string.settings_section_backup), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.settings_export_hint), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = exportPass,
                onValueChange = { exportPass = it },
                label = { Text(stringResource(R.string.passphrase_label)) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, platformImeOptions = androidx.compose.ui.text.input.PlatformImeOptions("flagNoPersonalizedLearning")),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    if (exportPass.isBlank()) return@Button
                    vm.exportToFile(exportPass) { file ->
                        val uri = FileProvider.getUriForFile(
                            context, "${context.packageName}.fileprovider", file,
                        )
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "application/octet-stream"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(send, null))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.settings_export)) }

            HorizontalDivider()

            Text(stringResource(R.string.settings_import_hint), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = importPass,
                onValueChange = { importPass = it },
                label = { Text(stringResource(R.string.passphrase_label)) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, platformImeOptions = androidx.compose.ui.text.input.PlatformImeOptions("flagNoPersonalizedLearning")),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("*/*")) },
                enabled = importPass.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.settings_import)) }

            HorizontalDivider()

            Text("PDF export", style = MaterialTheme.typography.titleMedium)
            Button(
                onClick = {
                    vm.exportPdf { file ->
                        val uri = FileProvider.getUriForFile(
                            context, "${context.packageName}.fileprovider", file,
                        )
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "application/pdf"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(send, null))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Générer un PDF") }
        }
    }

    // Dialog for mode change — collects the secrets we need depending on
    // current vs target mode.
    modeDialog?.let { target ->
        ChangeModeDialog(
            currentMode = mode,
            targetMode = target,
            onDismiss = { modeDialog = null },
            onConfirm = { currentPass, newPass ->
                val copy = VaultRepository.BiometricCopy(
                    title = context.getString(R.string.biometric_setup_title),
                    subtitle = context.getString(R.string.biometric_setup_subtitle),
                    cancel = context.getString(R.string.action_cancel),
                )
                vm.changeMode(target, currentPass, newPass, activity, copy)
                modeDialog = null
            },
        )
    }

    message?.let {
        AlertDialog(
            onDismissRequest = { vm.dismissMessage() },
            confirmButton = { TextButton(onClick = { vm.dismissMessage() }) { Text("OK") } },
            text = { Text(it) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChangeModeDialog(
    currentMode: VaultPrefs.Mode?,
    targetMode: VaultPrefs.Mode,
    onDismiss: () -> Unit,
    onConfirm: (currentPassphrase: String?, newPassphrase: String?) -> Unit,
) {
    var currentPass by remember { mutableStateOf("") }
    var newPass by remember { mutableStateOf("") }
    val needsCurrentPass = currentMode == VaultPrefs.Mode.KEYSTORE_PASSPHRASE ||
        currentMode == VaultPrefs.Mode.PARANOID
    val needsNewPass = targetMode == VaultPrefs.Mode.KEYSTORE_PASSPHRASE ||
        targetMode == VaultPrefs.Mode.PARANOID

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_change_mode_to, modeLabel(targetMode))) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (needsCurrentPass) {
                    OutlinedTextField(
                        value = currentPass,
                        onValueChange = { currentPass = it },
                        label = { Text(stringResource(R.string.settings_current_passphrase)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, platformImeOptions = androidx.compose.ui.text.input.PlatformImeOptions("flagNoPersonalizedLearning")),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (needsNewPass) {
                    OutlinedTextField(
                        value = newPass,
                        onValueChange = { newPass = it },
                        label = { Text(stringResource(R.string.settings_new_passphrase)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, platformImeOptions = androidx.compose.ui.text.input.PlatformImeOptions("flagNoPersonalizedLearning")),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = (!needsCurrentPass || currentPass.isNotBlank()) &&
                    (!needsNewPass || newPass.length >= 8),
                onClick = {
                    onConfirm(
                        if (needsCurrentPass) currentPass else null,
                        if (needsNewPass) newPass else null,
                    )
                }
            ) { Text(stringResource(R.string.action_create)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun modeLabel(m: VaultPrefs.Mode): String = stringResource(
    when (m) {
        VaultPrefs.Mode.KEYSTORE_ONLY -> R.string.mode_keystore_only_title
        VaultPrefs.Mode.KEYSTORE_BIOMETRIC -> R.string.mode_keystore_biometric_title
        VaultPrefs.Mode.KEYSTORE_PASSPHRASE -> R.string.mode_keystore_passphrase_title
        VaultPrefs.Mode.PARANOID -> R.string.mode_paranoid_title
    }
)

@Composable
private fun modeForYou(m: VaultPrefs.Mode): String = stringResource(
    when (m) {
        VaultPrefs.Mode.KEYSTORE_ONLY -> R.string.mode_keystore_only_for
        VaultPrefs.Mode.KEYSTORE_BIOMETRIC -> R.string.mode_keystore_biometric_for
        VaultPrefs.Mode.KEYSTORE_PASSPHRASE -> R.string.mode_keystore_passphrase_for
        VaultPrefs.Mode.PARANOID -> R.string.mode_paranoid_for
    }
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeRow(
    mode: VaultPrefs.Mode,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurface,
        ),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(modeLabel(mode), style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    else MaterialTheme.colorScheme.primary,
                )
                Text(
                    "  " + modeForYou(mode),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.9f)
                    else MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

private val LANGUAGE_OPTIONS: List<Pair<String, Int>> = listOf(
    "" to R.string.settings_language_system,
    "fr" to R.string.settings_language_fr,
    "en" to R.string.settings_language_en,
)
