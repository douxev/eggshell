package com.douxev.eggshell.ui.hormones

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
import com.douxev.eggshell.data.HormonesRepository
import com.douxev.eggshell.data.lab.EncryptedPdfException
import com.douxev.eggshell.data.lab.LabResultOcrService
import com.douxev.eggshell.data.lab.LabResultParser
import com.douxev.eggshell.ui.common.PasswordField
import uniffi.transition.NewHormoneMeasurement

@HiltViewModel
class ImportLabResultViewModel @Inject constructor(
    private val ocr: LabResultOcrService,
    private val repo: HormonesRepository,
) : ViewModel() {

    sealed interface Phase {
        data object Idle : Phase
        data object Processing : Phase
        /** The picked PDF is encrypted; we need a password to continue. */
        data class PasswordRequired(val uri: Uri, val wrongPassword: Boolean) : Phase
        data class Preview(
            val entries: List<EditableEntry>,
            val rawText: String,
            val atMs: Long,
            val dateAutoDetected: Boolean,
        ) : Phase
        data class Done(val saved: Int) : Phase
        data class Error(val reason: String) : Phase
    }

    /** A parsed hormone row in the preview list. The user can toggle it
     *  off if the parser picked something wrong. */
    data class EditableEntry(
        val hormone: String,
        val value: Double,
        val unit: String,
        val selected: Boolean,
    )

    private val _phase = MutableStateFlow<Phase>(Phase.Idle)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    fun process(uri: Uri) {
        _phase.value = Phase.Processing
        runRecognition(uri, null)
    }

    /** Retry recognition with the password the user just entered. */
    fun submitPassword(password: String) {
        val uri = (_phase.value as? Phase.PasswordRequired)?.uri ?: return
        if (password.isBlank()) return
        _phase.value = Phase.Processing
        runRecognition(uri, password)
    }

    private fun runRecognition(uri: Uri, password: String?) {
        viewModelScope.launch {
            runCatching { ocr.recognize(uri, password) }
                .onSuccess { text ->
                    val parsed = LabResultParser.parse(text)
                    _phase.value = Phase.Preview(
                        entries = parsed.values.map {
                            EditableEntry(it.hormone, it.value, it.unit, selected = true)
                        },
                        rawText = text,
                        atMs = parsed.dateMs ?: System.currentTimeMillis(),
                        dateAutoDetected = parsed.dateMs != null,
                    )
                }
                .onFailure { e ->
                    _phase.value = if (e is EncryptedPdfException) {
                        Phase.PasswordRequired(uri, wrongPassword = e.wrongPassword)
                    } else {
                        Phase.Error(e.message ?: "OCR failed")
                    }
                }
        }
    }

    fun toggleEntry(index: Int) {
        val cur = _phase.value
        if (cur !is Phase.Preview) return
        _phase.value = cur.copy(
            entries = cur.entries.toMutableList().also {
                it[index] = it[index].copy(selected = !it[index].selected)
            },
        )
    }

    fun setDate(newMs: Long) {
        val cur = _phase.value
        if (cur !is Phase.Preview) return
        // Once the user edits the date, we drop the "auto-detected" flag
        // so the UI badge stops claiming we parsed it from the document.
        _phase.value = cur.copy(atMs = newMs, dateAutoDetected = false)
    }

    fun save() {
        val cur = _phase.value
        if (cur !is Phase.Preview) return
        val selected = cur.entries.filter { it.selected }
        if (selected.isEmpty()) return
        val atMs = cur.atMs
        _phase.value = Phase.Processing
        viewModelScope.launch {
            var saved = 0
            selected.forEach { entry ->
                runCatching {
                    repo.add(
                        NewHormoneMeasurement(
                            atMs = atMs,
                            hormone = entry.hormone,
                            value = entry.value,
                            unit = entry.unit,
                            labName = null,
                            notes = null,
                        )
                    )
                }.onSuccess { saved++ }
            }
            _phase.value = Phase.Done(saved)
        }
    }

    fun reset() { _phase.value = Phase.Idle }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportLabResultScreen(
    onDone: () -> Unit,
    vm: ImportLabResultViewModel = hiltViewModel(),
) {
    val phase by vm.phase.collectAsState()

    // OpenDocument accepts both image MIMEs and application/pdf — lab
    // reports in France ship as PDFs much more often than as photos, so
    // PickVisualMedia (which is image-only) was rejecting the most common
    // input. PdfRenderer in LabResultOcrService handles the rasterisation
    // on the OCR side.
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { vm.process(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.import_lab_title)) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val p = phase) {
                ImportLabResultViewModel.Phase.Idle -> IdleStep(
                    onPickImage = {
                        // MIME-filter to PDF + every image type. The user's
                        // system file picker presents them with a chooser
                        // that surfaces both their Photos gallery and any
                        // PDF storage (Files, Drive, Nextcloud…).
                        picker.launch(arrayOf("application/pdf", "image/*"))
                    },
                )

                ImportLabResultViewModel.Phase.Processing -> ProcessingStep()

                is ImportLabResultViewModel.Phase.PasswordRequired -> PasswordStep(
                    wrongPassword = p.wrongPassword,
                    onUnlock = vm::submitPassword,
                    onCancel = vm::reset,
                )

                is ImportLabResultViewModel.Phase.Preview -> PreviewStep(
                    entries = p.entries,
                    atMs = p.atMs,
                    dateAutoDetected = p.dateAutoDetected,
                    onToggle = vm::toggleEntry,
                    onSetDate = vm::setDate,
                    onSave = vm::save,
                    onRetry = vm::reset,
                )

                is ImportLabResultViewModel.Phase.Done -> {
                    DoneStep(saved = p.saved, onContinue = onDone)
                }

                is ImportLabResultViewModel.Phase.Error -> ErrorStep(
                    reason = p.reason,
                    onRetry = vm::reset,
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.IdleStep(onPickImage: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.tertiary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.CloudUpload,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiary,
                )
            }
            Column(modifier = Modifier
                .padding(start = 14.dp)
                .fillMaxWidth(),
            ) {
                Text(
                    stringResource(R.string.import_lab_intro_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.import_lab_intro_sub),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.import_lab_supported_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.weight(1f))
    Button(
        onClick = onPickImage,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(50),
    ) {
        Icon(Icons.Filled.CloudUpload, contentDescription = null)
        Spacer(Modifier.size(8.dp))
        Text(stringResource(R.string.import_lab_pick))
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun ColumnScope.ProcessingStep() {
    Spacer(Modifier.weight(1f))
    Column(
        modifier = Modifier
            .fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator()
        Text(
            stringResource(R.string.import_lab_processing),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    Spacer(Modifier.weight(1f))
}

@Composable
private fun ColumnScope.PasswordStep(
    wrongPassword: Boolean,
    onUnlock: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var password by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf("")
    }
    Spacer(Modifier.height(8.dp))
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.import_lab_password_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.import_lab_password_sub),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PasswordField(
                value = password,
                onValueChange = { password = it },
                label = stringResource(R.string.import_lab_password_label),
                isError = wrongPassword,
                modifier = Modifier.fillMaxWidth(),
                supportingText = if (wrongPassword) {
                    { Text(stringResource(R.string.import_lab_password_wrong)) }
                } else null,
            )
        }
    }
    Spacer(Modifier.weight(1f))
    Button(
        onClick = { onUnlock(password) },
        enabled = password.isNotBlank(),
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(50),
    ) { Text(stringResource(R.string.import_lab_unlock)) }
    androidx.compose.material3.TextButton(
        onClick = onCancel,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.action_cancel)) }
    Spacer(Modifier.height(8.dp))
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ColumnScope.PreviewStep(
    entries: List<ImportLabResultViewModel.EditableEntry>,
    atMs: Long,
    dateAutoDetected: Boolean,
    onToggle: (Int) -> Unit,
    onSetDate: (Long) -> Unit,
    onSave: () -> Unit,
    onRetry: () -> Unit,
) {
    var datePickerOpen by androidx.compose.runtime.saveable.rememberSaveable {
        androidx.compose.runtime.mutableStateOf(false)
    }
    val dateFmt = androidx.compose.runtime.remember {
        java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault())
    }

    if (entries.isEmpty()) {
        Spacer(Modifier.weight(1f))
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(R.string.import_lab_no_match),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        Button(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(50),
        ) { Text(stringResource(R.string.import_lab_retry)) }
        Spacer(Modifier.height(8.dp))
        return
    }

    Text(
        stringResource(R.string.import_lab_review_title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
    Text(
        stringResource(R.string.import_lab_review_sub),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { datePickerOpen = true }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.import_lab_date_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    dateFmt.format(java.util.Date(atMs)),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(
                        if (dateAutoDetected) R.string.import_lab_date_auto
                        else R.string.import_lab_date_manual,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            androidx.compose.material3.TextButton(onClick = { datePickerOpen = true }) {
                Text(stringResource(R.string.import_lab_date_change))
            }
        }
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(entries.size) { idx ->
            val e = entries[idx]
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle(idx) },
            ) {
                Row(
                    modifier = Modifier.padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = e.selected,
                        onCheckedChange = { onToggle(idx) },
                    )
                    Column(modifier = Modifier
                        .padding(start = 4.dp)
                        .fillMaxWidth()
                        .weight(1f)) {
                        Text(
                            HormoneCatalog.kindLabel(e.hormone),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(
                        "${trimDouble(e.value)} ${e.unit}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
    val anySelected = entries.any { it.selected }
    Button(
        onClick = onSave,
        enabled = anySelected,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(50),
    ) {
        Text(
            stringResource(
                R.string.import_lab_save_fmt,
                entries.count { it.selected },
            )
        )
    }
    Spacer(Modifier.height(8.dp))

    if (datePickerOpen) {
        val state = androidx.compose.material3.rememberDatePickerState(initialSelectedDateMillis = atMs)
        androidx.compose.material3.DatePickerDialog(
            onDismissRequest = { datePickerOpen = false },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    state.selectedDateMillis?.let(onSetDate)
                    datePickerOpen = false
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { datePickerOpen = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        ) {
            androidx.compose.material3.DatePicker(state = state)
        }
    }
}

@Composable
private fun ColumnScope.DoneStep(saved: Int, onContinue: () -> Unit) {
    Spacer(Modifier.weight(1f))
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.import_lab_done_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.import_lab_done_fmt, saved),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
    Spacer(Modifier.weight(1f))
    Button(
        onClick = onContinue,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(50),
    ) { Text(stringResource(R.string.import_lab_close)) }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun ColumnScope.ErrorStep(reason: String, onRetry: () -> Unit) {
    Spacer(Modifier.weight(1f))
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                stringResource(R.string.import_lab_error_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
    Spacer(Modifier.weight(1f))
    Button(
        onClick = onRetry,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(50),
    ) { Text(stringResource(R.string.import_lab_retry)) }
    Spacer(Modifier.height(8.dp))
}

private fun trimDouble(v: Double): String {
    val s = v.toString()
    return if (s.endsWith(".0")) s.dropLast(2) else s
}

