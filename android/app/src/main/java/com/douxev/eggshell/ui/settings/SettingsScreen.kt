package com.douxev.eggshell.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.PlatformImeOptions
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.douxev.eggshell.R
import com.douxev.eggshell.data.AppAliasManager
import com.douxev.eggshell.data.BackupRepository
import com.douxev.eggshell.data.SecurityPrefs
import com.douxev.eggshell.data.VaultRepository
import com.douxev.eggshell.security.DecoyVerifier
import com.douxev.eggshell.security.VaultPrefs
import com.douxev.eggshell.ui.common.EncryptionNoteCard
import com.douxev.eggshell.ui.common.MIN_PASSPHRASE_LEN
import com.douxev.eggshell.ui.common.PasswordField
import com.douxev.eggshell.ui.common.ScreenHeader
import com.douxev.eggshell.ui.components.CardVariant
import com.douxev.eggshell.ui.components.EggCard
import com.douxev.eggshell.ui.components.ListRow
import com.douxev.eggshell.ui.components.SectionTitle
import com.douxev.eggshell.ui.theme.EggDim
import com.douxev.eggshell.ui.theme.EggShapes
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val aliasManager: AppAliasManager,
    private val decoy: DecoyVerifier,
    private val backup: BackupRepository,
    private val vault: VaultRepository,
    private val securityPrefs: SecurityPrefs,
    private val moduleShortcuts: com.douxev.eggshell.modules.ModuleShortcuts,
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
        // Putting the mask on has to withdraw the module shortcuts in the same
        // breath, and taking it off restores them: a long-press menu naming
        // « Médics · Analyses » under a calculator icon undoes the disguise the
        // user just chose.
        runCatching { moduleShortcuts.refresh(decoyActive = decoy.hasDecoyPin) }
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

    /**
     * True while the vault is being re-encrypted under a new passphrase. The
     * screen turns this into a modal the user cannot dismiss: in paranoid mode
     * the work is a full rewrite of the database and every sealed blob, and
     * walking away mid-way is the one thing that makes it expensive to
     * recover from.
     */
    private val _changingPassphrase = MutableStateFlow(false)
    val changingPassphrase: StateFlow<Boolean> = _changingPassphrase.asStateFlow()

    /** Whether this vault has a passphrase at all — the section is hidden otherwise. */
    val usesPassphrase: Boolean
        get() = vault.currentMode == VaultPrefs.Mode.KEYSTORE_PASSPHRASE ||
            vault.currentMode == VaultPrefs.Mode.PARANOID

    /**
     * The outcome of the last passphrase change, as a value rather than as
     * prose. The screen turns it into copy — a ViewModel holding a
     * `Resources` would pick the strings once, at call time, and keep showing
     * them in the previous language after a switch.
     */
    private val _passphraseResult =
        MutableStateFlow<VaultRepository.ChangePassphraseOutcome?>(null)
    val passphraseResult: StateFlow<VaultRepository.ChangePassphraseOutcome?> =
        _passphraseResult.asStateFlow()

    fun dismissPassphraseResult() { _passphraseResult.value = null }

    fun changePassphrase(current: String, new: String) {
        viewModelScope.launch {
            _changingPassphrase.value = true
            // Deliberately not cancelled with the screen: the repository runs
            // the rewrite under NonCancellable, so the only thing a teardown
            // could lose is this flag, and the modal that reads it.
            val out = vault.changePassphrase(current, new)
            _changingPassphrase.value = false
            _passphraseResult.value = out
            if (out is VaultRepository.ChangePassphraseOutcome.Success) {
                _currentMode.value = vault.currentMode
            }
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
                VaultRepository.ChangeModeOutcome.AlreadyInThisMode ->
                    _message.value = "C'est déjà ton mode actuel."
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

    fun dismissMessage() { _message.value = null }
}

/**
 * Porte « Sécurité » — everything it takes to open the vault, and everything a
 * glance over the shoulder may see: lock mode, access PIN + decoy PIN, icon
 * alias, screenshot blocking, encrypted backup and restore.
 *
 * Restyled only. Not one security rule is rewritten here: the mode change
 * still goes through [VaultRepository.changeMode], the PIN pair through
 * [DecoyVerifier.setPair], and the restore still kills the process so the new
 * key is picked up from a clean start.
 *
 * The language picker left for « Apparence & langue » and the PDF export for
 * Rendez-vous; the reminders hub is now reached from the settings hub itself.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    /**
     * Retained because the fixed nav-host call passes it. Reminders became a
     * section of the settings hub, so this door no longer links to them.
     */
    @Suppress("UNUSED_PARAMETER") onOpenReminders: () -> Unit = {},
    /**
     * Null means "just go back": the nav host registers its own back callback,
     * so dispatching a system back press pops this screen exactly like the
     * hardware gesture does.
     */
    onBack: (() -> Unit)? = null,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val variant by vm.currentVariant.collectAsState()
    val mode by vm.currentMode.collectAsState()
    val message by vm.message.collectAsState()
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val goBack: () -> Unit = onBack ?: { backDispatcher?.onBackPressed() }

    var accessPin by remember { mutableStateOf("") }
    var decoyPin by remember { mutableStateOf("") }
    var exportPass by remember { mutableStateOf("") }
    var importPass by remember { mutableStateOf("") }
    var modeDialog by remember { mutableStateOf<VaultPrefs.Mode?>(null) }
    var passphraseDialog by remember { mutableStateOf(false) }
    val changingPassphrase by vm.changingPassphrase.collectAsState()
    val passphraseResult by vm.passphraseResult.collectAsState()

    // Decoy is available in every security mode — when set, the lock screen
    // always presents a PIN keypad and Keystore-only / biometric modes
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

    Scaffold(containerColor = MaterialTheme.colorScheme.surface) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(
                start = EggDim.ScreenMargin,
                end = EggDim.ScreenMargin,
                bottom = 40.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(EggDim.RowGap),
        ) {
            item {
                ScreenHeader(title = stringResource(R.string.set_door_security), onBack = goBack)
            }
            item {
                EggCard(variant = CardVariant.Low) {
                    Text(
                        stringResource(R.string.set_sec_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // -- Lock mode ---------------------------------------------------
            item { SectionSpacer(stringResource(R.string.set_sec_section_lock)) }
            item { EncryptionNoteCard() }
            item {
                Text(
                    stringResource(
                        R.string.set_sec_current_mode_fmt,
                        mode?.let { modeLabel(it) } ?: "—",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            items(VaultPrefs.Mode.entries.size) { index ->
                val m = VaultPrefs.Mode.entries[index]
                ModeRow(
                    mode = m,
                    selected = m == mode,
                    // Tapping the mode you are already in used to open the
                    // change-mode dialog, which asked for an old and a new
                    // passphrase and then did nothing with either. Changing a
                    // passphrase has its own section below; this row only ever
                    // means "switch".
                    onClick = { if (m == mode) passphraseDialog = vm.usesPassphrase else modeDialog = m },
                )
            }

            // -- Passphrase ---------------------------------------------------
            if (vm.usesPassphrase) {
                item { SectionSpacer(stringResource(R.string.set_sec_section_pass)) }
                item {
                    ListRow(
                        title = stringResource(R.string.set_sec_change_pass),
                        subtitle = stringResource(R.string.set_sec_change_pass_sub),
                        onClick = { passphraseDialog = true },
                    )
                }
            }

            // -- Access + decoy PIN pair (available in every mode) ------------
            item { SectionSpacer(stringResource(R.string.set_sec_section_pins)) }
            item {
                EggCard(variant = CardVariant.Low) {
                    Text(
                        stringResource(R.string.settings_decoy_pin_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    // Honest-scope callout. The PIN is a UX gate, not a second
                    // crypto factor: in Keystore-only / biometric modes the
                    // master key is wrapped by Keystore and decryptable without
                    // the PIN by anyone with root + Keystore access. The decoy
                    // protects against partner-grab "show me" scenarios; that
                    // is the threat model it is designed for.
                    EggCard(
                        variant = CardVariant.Outlined,
                        padding = PaddingValues(12.dp),
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Icon(
                                Icons.Filled.Info,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                stringResource(R.string.settings_decoy_pin_warning),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = accessPin,
                        onValueChange = { accessPin = it.filter(Char::isDigit).take(4) },
                        label = { Text(stringResource(R.string.settings_access_pin_label)) },
                        supportingText = { Text(stringResource(R.string.settings_access_pin_hint)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.NumberPassword,
                            platformImeOptions = PlatformImeOptions("flagNoPersonalizedLearning"),
                        ),
                        enabled = canUseDecoy,
                        singleLine = true,
                        shape = EggShapes.Field,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = decoyPin,
                        onValueChange = { decoyPin = it.filter(Char::isDigit).take(4) },
                        label = { Text(stringResource(R.string.settings_decoy_pin_label)) },
                        supportingText = { Text(stringResource(R.string.settings_decoy_pin_hint_field)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.NumberPassword,
                            platformImeOptions = PlatformImeOptions("flagNoPersonalizedLearning"),
                        ),
                        enabled = canUseDecoy,
                        singleLine = true,
                        shape = EggShapes.Field,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { vm.savePinPair(accessPin, decoyPin) },
                        enabled = canUseDecoy &&
                            accessPin.length == 4 && decoyPin.length == 4 && accessPin != decoyPin,
                        shape = EggShapes.Pill,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.settings_save_decoy_pin)) }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { vm.clearPinPair(); accessPin = ""; decoyPin = "" },
                        enabled = canUseDecoy && decoyConfigured,
                        shape = EggShapes.Pill,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.settings_clear_decoy_pin)) }
                }
            }

            // -- Icon alias ---------------------------------------------------
            item { SectionSpacer(stringResource(R.string.set_sec_section_masking)) }
            item {
                EggCard(variant = CardVariant.Low) {
                    Text(
                        stringResource(R.string.set_sec_masking_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppAliasManager.Variant.entries.forEach { v ->
                            FilterChip(
                                selected = v == variant,
                                onClick = { vm.setVariant(v) },
                                label = { Text(aliasLabel(v)) },
                                leadingIcon = if (v == variant) {
                                    {
                                        Icon(
                                            Icons.Filled.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                } else {
                                    null
                                },
                                shape = RoundedCornerShape(10.dp),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                ),
                            )
                        }
                    }
                }
            }

            // -- Screenshot blocking -------------------------------------------
            item { SectionSpacer(stringResource(R.string.set_sec_section_screenshots)) }
            item {
                val blockScreens by vm.blockScreenshots.collectAsState()
                val label = stringResource(R.string.settings_block_screenshots)
                ListRow(
                    title = label,
                    subtitle = stringResource(R.string.settings_block_screenshots_sub),
                    trailing = {
                        Switch(
                            checked = blockScreens,
                            onCheckedChange = vm::setBlockScreenshots,
                            modifier = Modifier.semanticsLabel(label),
                        )
                    },
                )
            }

            // -- Encrypted backup ----------------------------------------------
            item { SectionSpacer(stringResource(R.string.set_sec_section_backup)) }
            item {
                EggCard(variant = CardVariant.Low) {
                    Text(
                        stringResource(R.string.set_sec_export_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(R.string.settings_export_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    Text(
                        stringResource(R.string.set_sec_backup_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    PasswordField(
                        value = exportPass,
                        onValueChange = { exportPass = it },
                        label = stringResource(R.string.passphrase_label),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
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
                        enabled = exportPass.isNotBlank(),
                        shape = EggShapes.Pill,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.settings_export)) }
                }
            }
            item {
                EggCard(variant = CardVariant.Low) {
                    Text(
                        stringResource(R.string.set_sec_import_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(R.string.settings_import_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    Text(
                        stringResource(R.string.set_sec_import_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    PasswordField(
                        value = importPass,
                        onValueChange = { importPass = it },
                        label = stringResource(R.string.passphrase_label),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = { importLauncher.launch(arrayOf("*/*")) },
                        enabled = importPass.isNotBlank(),
                        shape = EggShapes.Pill,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.settings_import)) }
                }
            }

            item { Spacer(Modifier.height(8.dp)) }
        }
    }

    // Dialog for mode change — collects the secrets we need depending on
    // current vs target mode.
    modeDialog?.let { target ->
        // Resolved here, in composition, rather than from the captured Context
        // inside onConfirm: a Context does not carry the configuration forward,
        // so a language switch would put the prompt back in the old one.
        val copy = VaultRepository.BiometricCopy(
            title = stringResource(R.string.biometric_setup_title),
            subtitle = stringResource(R.string.biometric_setup_subtitle),
            cancel = stringResource(R.string.action_cancel),
        )
        ChangeModeDialog(
            currentMode = mode,
            targetMode = target,
            onDismiss = { modeDialog = null },
            onConfirm = { currentPass, newPass ->
                vm.changeMode(target, currentPass, newPass, activity, copy)
                modeDialog = null
            },
        )
    }

    if (passphraseDialog) {
        ChangePassphraseDialog(
            paranoid = mode == VaultPrefs.Mode.PARANOID,
            onDismiss = { passphraseDialog = false },
            onConfirm = { current, new ->
                passphraseDialog = false
                vm.changePassphrase(current, new)
            },
        )
    }

    // Not dismissable, and no cancel: the rewrite is already running under
    // NonCancellable in the repository, so a cancel button would only lie.
    if (changingPassphrase) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text(stringResource(R.string.set_sec_change_pass_working)) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(
                        stringResource(R.string.set_sec_change_pass_warn_paranoid),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            },
        )
    }

    passphraseResult?.let { out ->
        AlertDialog(
            onDismissRequest = { vm.dismissPassphraseResult() },
            confirmButton = {
                TextButton(onClick = { vm.dismissPassphraseResult() }) { Text("OK") }
            },
            text = { Text(passphraseResultCopy(out)) },
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

/** A [SectionTitle] with the breathing room a settings section needs above it. */
@Composable
private fun SectionSpacer(text: String) {
    Column {
        Spacer(Modifier.height(8.dp))
        SectionTitle(text, modifier = Modifier.padding(horizontal = 4.dp))
    }
}

/** Names a switch for TalkBack: eight bare "on/off" nodes tell nobody anything. */
private fun Modifier.semanticsLabel(label: String): Modifier =
    this.then(
        Modifier.semantics { contentDescription = label },
    )

@Composable
private fun aliasLabel(v: AppAliasManager.Variant): String = stringResource(
    when (v) {
        AppAliasManager.Variant.DEFAULT -> R.string.set_sec_alias_default
        AppAliasManager.Variant.NOTES -> R.string.alias_notes
        AppAliasManager.Variant.CALCULATOR -> R.string.alias_calculator
        AppAliasManager.Variant.WEATHER -> R.string.alias_weather
    },
)

/** The last passphrase change, in words. See [SettingsViewModel.passphraseResult]. */
@Composable
private fun passphraseResultCopy(out: VaultRepository.ChangePassphraseOutcome): String =
    when (out) {
        is VaultRepository.ChangePassphraseOutcome.Success ->
            if (out.mediaLeftBehind == 0u) stringResource(R.string.set_sec_change_pass_done)
            else stringResource(
                R.string.set_sec_change_pass_done_partial, out.mediaLeftBehind.toInt(),
            )
        VaultRepository.ChangePassphraseOutcome.WrongPassphrase ->
            stringResource(R.string.set_sec_change_pass_wrong)
        VaultRepository.ChangePassphraseOutcome.Unchanged ->
            stringResource(R.string.set_sec_change_pass_same)
        VaultRepository.ChangePassphraseOutcome.NotApplicable ->
            stringResource(R.string.set_sec_change_pass_na)
        is VaultRepository.ChangePassphraseOutcome.Failed ->
            stringResource(R.string.set_sec_change_pass_failed, out.reason)
    }

/**
 * Current passphrase, new one, and the new one again.
 *
 * The confirmation field is not ceremony. In paranoid mode this rewrites the
 * database under a key derived from what was typed here; a typo that both
 * fields share is at least a typo the user made twice, and a typo in only one
 * of them never reaches the vault at all.
 */
@Composable
private fun ChangePassphraseDialog(
    paranoid: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (current: String, new: String) -> Unit,
) {
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val longEnough = new.length >= MIN_PASSPHRASE_LEN
    val matches = new == confirm

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.set_sec_change_pass)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (paranoid) {
                    Text(
                        stringResource(R.string.set_sec_change_pass_warn_paranoid),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                PasswordField(
                    value = current,
                    onValueChange = { current = it },
                    label = stringResource(R.string.settings_current_passphrase),
                    modifier = Modifier.fillMaxWidth(),
                )
                PasswordField(
                    value = new,
                    onValueChange = { new = it },
                    label = stringResource(R.string.settings_new_passphrase),
                    modifier = Modifier.fillMaxWidth(),
                )
                PasswordField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    label = stringResource(R.string.set_sec_change_pass_confirm),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (new.isNotEmpty() && !longEnough) {
                    Text(
                        stringResource(R.string.passphrase_too_short, MIN_PASSPHRASE_LEN),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (confirm.isNotEmpty() && !matches) {
                    Text(
                        stringResource(R.string.passphrase_mismatch),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = current.isNotBlank() && longEnough && matches,
                onClick = { onConfirm(current, new) },
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

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
                    PasswordField(
                        value = currentPass,
                        onValueChange = { currentPass = it },
                        label = stringResource(R.string.settings_current_passphrase),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (needsNewPass) {
                    PasswordField(
                        value = newPass,
                        onValueChange = { newPass = it },
                        label = stringResource(R.string.settings_new_passphrase),
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

@Composable
private fun ModeRow(
    mode: VaultPrefs.Mode,
    selected: Boolean,
    onClick: () -> Unit,
) {
    // The selected mode is carried by a word ("Actif") and a glyph as well as
    // by the container colour — never by colour alone (§10).
    EggCard(
        variant = if (selected) CardVariant.Primary else CardVariant.Low,
        padding = PaddingValues(16.dp),
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                modeLabel(mode),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier.padding(top = 4.dp),
        ) {
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
