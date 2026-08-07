package com.douxev.eggshell.ui.hormones

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Date
import java.util.Locale
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
import com.douxev.eggshell.ui.common.ScreenHeader
import com.douxev.eggshell.ui.components.ActionBand
import com.douxev.eggshell.ui.components.CardRule
import com.douxev.eggshell.ui.components.CardVariant
import com.douxev.eggshell.ui.components.EggCard
import com.douxev.eggshell.ui.components.MicroLabel
import com.douxev.eggshell.ui.components.SectionTitle
import com.douxev.eggshell.ui.components.SkeletonBlock
import com.douxev.eggshell.ui.theme.EggDim
import com.douxev.eggshell.ui.theme.EggShapes
import uniffi.transition.NewHormoneMeasurement

/** Fichier → Lecture → Aperçu → Enregistré. The progress bar shows four
 *  segments and the caption row names the current one (§6.9). */
private const val OCR_STEPS = 4

@HiltViewModel
class ImportLabResultViewModel @Inject constructor(
    private val ocr: LabResultOcrService,
    private val repo: HormonesRepository,
    @ApplicationContext private val context: Context,
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
            /** Laboratory read off the letterhead; null when unrecognised. */
            val labName: String?,
            /** Set when a save attempt could not write everything it was
             *  given. The preview is kept intact so the user can retry. */
            val saveFailure: SaveFailure? = null,
        ) : Phase
        data class Done(val saved: Int) : Phase
        data class Error(val reason: String) : Phase
    }

    /** How a partly-failed save ended: what landed, and what did not. */
    data class SaveFailure(val saved: Int, val failed: Int)

    /** A parsed row in the preview list. The user can switch it off if the
     *  parser picked something wrong — and a doubtful read starts off. */
    data class EditableEntry(
        val hormone: String,
        val value: Double,
        val unit: String,
        val selected: Boolean,
        /** What the document literally showed, quoted back on a doubtful read. */
        val raw: String,
        val doubtful: Boolean,
    )

    private val _phase = MutableStateFlow<Phase>(Phase.Idle)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    fun process(uri: Uri) {
        _phase.value = Phase.Processing
        runRecognition(uri, null)
    }

    /** Retry recognition with the password the user just typed. The password
     *  is a parameter and nothing else: it is never held in state, never
     *  written to prefs, and gone as soon as this call returns. */
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
                            EditableEntry(
                                hormone = it.hormone,
                                value = it.value,
                                unit = it.unit,
                                // A doubtful read is opt-in: we never save a
                                // guess the user hasn't looked at.
                                selected = !it.doubtful,
                                raw = it.raw,
                                doubtful = it.doubtful,
                            )
                        },
                        rawText = text,
                        atMs = parsed.dateMs ?: System.currentTimeMillis(),
                        dateAutoDetected = parsed.dateMs != null,
                        labName = parsed.labName,
                    )
                }
                .onFailure { e ->
                    _phase.value = if (e is EncryptedPdfException) {
                        Phase.PasswordRequired(uri, wrongPassword = e.wrongPassword)
                    } else {
                        Phase.Error(e.message.orEmpty())
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
            // The user is composing a new attempt: the previous verdict no
            // longer describes what is about to be written.
            saveFailure = null,
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
        // Provenance (décisions D3): an imported reading always names where it
        // came from, so the doctor report can tell it apart from a value typed
        // in by hand. No new column — this is the existing `lab_name`.
        val provenance = cur.labName ?: context.getString(R.string.ocr_lab_fallback)
        _phase.value = Phase.Processing
        viewModelScope.launch {
            // Every write is accounted for. Swallowing the failures used to
            // land on « C'est enregistré · 0 valeur » with the parsed rows
            // thrown away — a success screen for a save that never happened.
            val written = HashSet<Int>()
            var failed = 0
            cur.entries.forEachIndexed { index, entry ->
                if (!entry.selected) return@forEachIndexed
                runCatching {
                    repo.add(
                        NewHormoneMeasurement(
                            atMs = atMs,
                            hormone = entry.hormone,
                            value = entry.value,
                            unit = entry.unit,
                            labName = provenance,
                            notes = null,
                        )
                    )
                }
                    .onSuccess { written.add(index) }
                    .onFailure { failed++ }
            }
            _phase.value = if (failed == 0 && written.isNotEmpty()) {
                Phase.Done(written.size)
            } else {
                // Back to the preview, values intact, so the user can retry.
                // What did land is unticked: a retry must not write it twice.
                cur.copy(
                    entries = cur.entries.mapIndexed { index, entry ->
                        if (index in written) entry.copy(selected = false) else entry
                    },
                    saveFailure = SaveFailure(saved = written.size, failed = failed),
                )
            }
        }
    }

    fun reset() { _phase.value = Phase.Idle }
}

/**
 * Import a lab result in four steps, entirely on the device.
 *
 * [onManualEntry] defaults to [onDone]: when the read fails we offer to type
 * the values in, and the honest fallback is to go back to Mesures where the
 * « Ajouter » button lives.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportLabResultScreen(
    onDone: () -> Unit,
    onManualEntry: () -> Unit = onDone,
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
    val pick = { picker.launch(arrayOf("application/pdf", "image/*")) }

    val step = when (phase) {
        ImportLabResultViewModel.Phase.Idle,
        is ImportLabResultViewModel.Phase.PasswordRequired -> 1
        ImportLabResultViewModel.Phase.Processing,
        is ImportLabResultViewModel.Phase.Error -> 2
        is ImportLabResultViewModel.Phase.Preview -> 3
        is ImportLabResultViewModel.Phase.Done -> OCR_STEPS
    }

    Scaffold(
        bottomBar = {
            // A full-width action reserves its own band and never floats over
            // the list; the hairline separates it from the scrolling content.
            Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ActionBand(alignment = Alignment.Center) {
                    when (val p = phase) {
                        ImportLabResultViewModel.Phase.Idle -> BandButton(
                            label = stringResource(R.string.ocr_pick),
                            icon = Icons.Filled.FileUpload,
                            onClick = { pick() },
                        )

                        ImportLabResultViewModel.Phase.Processing -> BandButton(
                            label = stringResource(R.string.ocr_step2_title),
                            icon = null,
                            enabled = false,
                            onClick = {},
                        )

                        is ImportLabResultViewModel.Phase.PasswordRequired -> Unit

                        is ImportLabResultViewModel.Phase.Preview -> {
                            val kept = p.entries.count { it.selected }
                            BandButton(
                                label = if (kept == 0) {
                                    stringResource(R.string.ocr_save_none)
                                } else {
                                    pluralStringResource(R.plurals.ocr_save, kept, kept)
                                },
                                icon = Icons.Filled.Check,
                                enabled = kept > 0,
                                onClick = vm::save,
                            )
                        }

                        is ImportLabResultViewModel.Phase.Done -> BandButton(
                            label = stringResource(R.string.ocr_step4_done),
                            icon = null,
                            onClick = onDone,
                        )

                        is ImportLabResultViewModel.Phase.Error -> BandButton(
                            label = stringResource(R.string.ocr_retry),
                            icon = Icons.Filled.FileUpload,
                            onClick = { vm.reset(); pick() },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = EggDim.ScreenMargin),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ScreenHeader(title = stringResource(R.string.ocr_title), onBack = onDone)

            StepProgress(step = step)
            StepCaption(
                caption = stringResource(
                    when (step) {
                        1 -> R.string.ocr_step1_caption
                        2 -> R.string.ocr_step2_caption
                        3 -> R.string.ocr_step3_caption
                        else -> R.string.ocr_step4_caption
                    },
                ),
            )

            when (val p = phase) {
                ImportLabResultViewModel.Phase.Idle -> PickStep()

                ImportLabResultViewModel.Phase.Processing -> ReadingStep()

                is ImportLabResultViewModel.Phase.PasswordRequired -> LockedStep(
                    wrongPassword = p.wrongPassword,
                    onUnlock = vm::submitPassword,
                    onPickAnother = { vm.reset(); pick() },
                )

                is ImportLabResultViewModel.Phase.Preview ->
                    if (p.entries.isEmpty()) {
                        FailedStep(
                            titleRes = R.string.ocr_failed_none_title,
                            bodyRes = R.string.ocr_failed_none_body,
                            onManualEntry = onManualEntry,
                        )
                    } else {
                        ReviewStep(
                            entries = p.entries,
                            atMs = p.atMs,
                            failure = p.saveFailure,
                            onToggle = vm::toggleEntry,
                            onSetDate = vm::setDate,
                        )
                    }

                is ImportLabResultViewModel.Phase.Done -> SavedStep(saved = p.saved)

                is ImportLabResultViewModel.Phase.Error -> FailedStep(
                    titleRes = R.string.ocr_failed_title,
                    bodyRes = R.string.ocr_failed_body,
                    onManualEntry = onManualEntry,
                )
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// Chrome shared by the four steps
// ---------------------------------------------------------------------------

/** Four segments, filled up to the step we are on. */
@Composable
private fun StepProgress(step: Int) {
    val label = stringResource(R.string.ocr_progress_a11y_fmt, step)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        repeat(OCR_STEPS) { index ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .background(
                        color = if (index < step) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                        shape = EggShapes.Pill,
                    ),
            )
        }
    }
}

/** « 3 / 4 · VÉRIFIE CE QU’ON A LU » on the left, « Hors ligne » on the right. */
@Composable
private fun StepCaption(caption: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        MicroLabel(caption, color = MaterialTheme.colorScheme.primary)
        MicroLabel(stringResource(R.string.ocr_offline))
    }
}

@Composable
private fun BandButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = EggShapes.Pill,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(8.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

/** The `encrypted` inset that closes every step: what was read, and what was
 *  not kept. Same wording on all four so the promise never wavers. */
@Composable
private fun PrivacyInset(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, EggShapes.Note)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Filled.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(19.dp),
        )
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------------
// 1 / 4 — Fichier
// ---------------------------------------------------------------------------

@Composable
private fun ColumnScope.PickStep() {
    EggCard(variant = CardVariant.Primary) {
        Text(
            stringResource(R.string.ocr_step1_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            stringResource(R.string.ocr_step1_body),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
    Text(
        stringResource(R.string.ocr_step1_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    PrivacyInset(stringResource(R.string.ocr_privacy))
}

// ---------------------------------------------------------------------------
// 2 / 4 — Lecture
// ---------------------------------------------------------------------------

@Composable
private fun ColumnScope.ReadingStep() {
    Text(
        stringResource(R.string.ocr_step2_title),
        style = MaterialTheme.typography.titleMedium,
    )
    Text(
        stringResource(R.string.ocr_step2_body),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // Skeletons shaped like the review step that follows — never a spinner
    // over the whole page (§5.3).
    SkeletonBlock(height = 64.dp, shape = EggShapes.Card)
    SkeletonBlock(height = 168.dp, shape = EggShapes.Card)
    SkeletonBlock(height = 72.dp, shape = EggShapes.Note)
}

// ---------------------------------------------------------------------------
// 3 / 4 — Aperçu
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColumnScope.ReviewStep(
    entries: List<ImportLabResultViewModel.EditableEntry>,
    atMs: Long,
    failure: ImportLabResultViewModel.SaveFailure?,
    onToggle: (Int) -> Unit,
    onSetDate: (Long) -> Unit,
) {
    var datePickerOpen by rememberSaveable { mutableStateOf(false) }
    val dateFmt = remember { SimpleDateFormat("d MMMM yyyy", Locale.getDefault()) }
    val dateText = dateFmt.format(Date(atMs))
    val dateLabel = stringResource(R.string.ocr_date_a11y_fmt, dateText)

    // A failed save keeps the user on this step rather than on a success
    // screen; the card says what happened and the band still offers a retry.
    if (failure != null) {
        EggCard(variant = CardVariant.Error) {
            Text(
                stringResource(R.string.ocr_save_failed_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.ocr_save_failed_body),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (failure.saved > 0) {
                Text(
                    pluralStringResource(
                        R.plurals.ocr_save_failed_partial,
                        failure.saved,
                        failure.saved,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }

    EggCard(
        variant = CardVariant.Low,
        padding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
        onClick = { datePickerOpen = true },
        modifier = Modifier.semantics { contentDescription = dateLabel },
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.CalendarMonth,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.ocr_date_label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    dateText,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                stringResource(R.string.ocr_date_edit),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }

    SectionTitle(pluralStringResource(R.plurals.ocr_detected, entries.size, entries.size))

    EggCard(
        variant = CardVariant.Low,
        padding = PaddingValues(horizontal = 18.dp, vertical = 6.dp),
    ) {
        entries.forEachIndexed { index, entry ->
            AnalyteRow(entry = entry, onToggle = { onToggle(index) })
            if (index < entries.lastIndex) CardRule()
        }
    }

    PrivacyInset(stringResource(R.string.ocr_privacy))

    if (datePickerOpen) {
        // Material speaks UTC midnight on both sides of this dialog, so the day
        // is converted in and out of the local zone — otherwise a reading taken
        // west of Greenwich is filed on the previous day.
        val zone = remember { ZoneId.systemDefault() }
        val state = rememberDatePickerState(
            initialSelectedDateMillis = Instant.ofEpochMilli(atMs).atZone(zone)
                .toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { datePickerOpen = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { picked ->
                        val day = Instant.ofEpochMilli(picked).atZone(ZoneOffset.UTC).toLocalDate()
                        val previous = Instant.ofEpochMilli(atMs).atZone(zone)
                        onSetDate(
                            day.atTime(previous.hour, previous.minute)
                                .atZone(zone).toInstant().toEpochMilli(),
                        )
                    }
                    datePickerOpen = false
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { datePickerOpen = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        ) { DatePicker(state = state) }
    }
}

@Composable
private fun AnalyteRow(
    entry: ImportLabResultViewModel.EditableEntry,
    onToggle: () -> Unit,
) {
    val name = HormoneCatalog.kindLabel(entry.hormone)
    val valueText = if (entry.doubtful) {
        stringResource(R.string.ocr_uncertain_fmt, entry.raw)
    } else {
        stringResource(R.string.measures_reading_original_fmt, trimDouble(entry.value), entry.unit)
    }
    // One node for the whole row: TalkBack announces the intent, the reading
    // and the switch state in one breath instead of three separate stops.
    val label = stringResource(R.string.measures_reading_sub_fmt, stringResource(R.string.ocr_keep_fmt, name), valueText)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = entry.selected,
                role = Role.Switch,
                onValueChange = { onToggle() },
            )
            .semantics(mergeDescendants = true) { contentDescription = label }
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                valueText,
                style = MaterialTheme.typography.bodySmall,
                color = if (entry.doubtful) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Switch(checked = entry.selected, onCheckedChange = null)
    }
}

// ---------------------------------------------------------------------------
// 4 / 4 — Enregistré
// ---------------------------------------------------------------------------

@Composable
private fun ColumnScope.SavedStep(saved: Int) {
    EggCard(variant = CardVariant.Primary) {
        Text(
            stringResource(R.string.ocr_step4_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            pluralStringResource(R.plurals.ocr_step4_body, saved, saved),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
    PrivacyInset(stringResource(R.string.ocr_step4_note))
}

// ---------------------------------------------------------------------------
// The two distinct failures
// ---------------------------------------------------------------------------

/** A locked PDF is not a broken read: we ask for the key, use it once, and
 *  forget it. The field is deliberately plain `remember` — `rememberSaveable`
 *  would put the password in the saved-instance bundle. */
@Composable
private fun ColumnScope.LockedStep(
    wrongPassword: Boolean,
    onUnlock: (String) -> Unit,
    onPickAnother: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    EggCard(variant = if (wrongPassword) CardVariant.Error else CardVariant.Low) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Column {
                Text(
                    stringResource(R.string.ocr_locked_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(R.string.ocr_locked_body),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        PasswordField(
            value = password,
            onValueChange = { password = it },
            label = stringResource(R.string.ocr_locked_label),
            isError = wrongPassword,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp),
            supportingText = if (wrongPassword) {
                { Text(stringResource(R.string.ocr_locked_wrong)) }
            } else {
                null
            },
        )
        Button(
            onClick = { onUnlock(password) },
            enabled = password.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .height(EggDim.TouchTarget),
            shape = EggShapes.Pill,
        ) { Text(stringResource(R.string.ocr_locked_unlock)) }
        TextButton(
            onClick = onPickAnother,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.ocr_retry)) }
    }
    PrivacyInset(stringResource(R.string.ocr_privacy))
}

/** The other failure: the document opened but we got nothing usable out of it.
 *  Offer the sure thing — typing the values in. */
@Composable
private fun ColumnScope.FailedStep(
    titleRes: Int,
    bodyRes: Int,
    onManualEntry: () -> Unit,
) {
    EggCard(variant = CardVariant.Error) {
        Text(
            stringResource(titleRes),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            stringResource(bodyRes),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 6.dp),
        )
        TextButton(
            onClick = onManualEntry,
            modifier = Modifier.padding(top = 4.dp),
        ) { Text(stringResource(R.string.ocr_manual)) }
    }
}

/**
 * Deliberately NOT the significant-figure rendering the curves use: this row
 * exists to be checked against the paper sheet it was read off, so it must show
 * what the parser actually captured, digit for digit.
 */
private fun trimDouble(v: Double): String = com.douxev.eggshell.ui.common.ValueFormat.plain(v)
