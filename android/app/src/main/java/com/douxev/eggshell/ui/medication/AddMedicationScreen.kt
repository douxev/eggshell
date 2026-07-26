package com.douxev.eggshell.ui.medication

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.douxev.eggshell.R
import com.douxev.eggshell.data.MedicationRepository
import com.douxev.eggshell.reminders.MedAliasPrefs
import com.douxev.eggshell.ui.common.ScreenHeader
import com.douxev.eggshell.ui.common.clickToDismissKeyboard
import com.douxev.eggshell.ui.components.ActionBand
import com.douxev.eggshell.ui.components.SectionTitle
import com.douxev.eggshell.ui.theme.EggDim
import com.douxev.eggshell.ui.theme.EggShapes
import uniffi.transition.Medication
import uniffi.transition.NewMedication
import uniffi.transition.NewTreatmentChange

@HiltViewModel
class AddMedicationViewModel @Inject constructor(
    private val repo: MedicationRepository,
    private val medAlias: MedAliasPrefs,
    state: SavedStateHandle,
) : ViewModel() {
    enum class Status { Idle, Submitting, Done, Error }

    /** -1 = create a new medication; positive = edit that medication. */
    val editingId: Long = state.get<Long>("id") ?: -1L
    val isEditing: Boolean = editingId > 0L

    private val _status = MutableStateFlow(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    /** Id of the medication just created/edited, so the caller can chain into
     *  its schedule setup (create) or pop back (edit). */
    private val _newId = MutableStateFlow<Long?>(null)
    val newId: StateFlow<Long?> = _newId.asStateFlow()

    private val _loaded = MutableStateFlow<Medication?>(null)
    val loaded: StateFlow<Medication?> = _loaded.asStateFlow()
    private val _alias = MutableStateFlow<String?>(null)
    val alias: StateFlow<String?> = _alias.asStateFlow()

    init {
        if (isEditing) {
            viewModelScope.launch {
                runCatching { repo.get(editingId) }.onSuccess { _loaded.value = it }
                _alias.value = medAlias.get(editingId)
            }
        }
    }

    fun submit(med: NewMedication, notifAlias: String?) {
        _status.value = Status.Submitting
        _error.value = null
        viewModelScope.launch {
            runCatching {
                if (isEditing) {
                    repo.update(editingId, med)
                    logTreatmentChanges(_loaded.value, med)
                    medAlias.set(editingId, notifAlias)
                    editingId
                } else {
                    val created = repo.add(med)
                    // Alias lives in plain prefs keyed by med id (it's a fake
                    // name) so the locked reminder path can read it.
                    medAlias.set(created.id, notifAlias)
                    created.id
                }
            }
                .onSuccess {
                    _newId.value = it
                    _status.value = Status.Done
                }
                .onFailure {
                    _error.value = it.message ?: it::class.simpleName.orEmpty()
                    _status.value = Status.Error
                }
        }
    }

    /** Record dose/unit/route edits as timestamped audit rows for the
     *  correlation timeline. No-op when nothing dose-related changed. */
    private suspend fun logTreatmentChanges(old: Medication?, new: NewMedication) {
        old ?: return
        val now = System.currentTimeMillis()
        suspend fun change(field: String, oldV: String?, newV: String?) {
            if (oldV != newV) {
                runCatching {
                    repo.logTreatmentChange(
                        NewTreatmentChange(
                            medicationId = editingId,
                            atMs = now,
                            field = field,
                            oldValue = oldV,
                            newValue = newV,
                            note = null,
                        )
                    )
                }
            }
        }
        change("dose", old.defaultDose?.toString(), new.defaultDose?.toString())
        change("unit", old.defaultDoseUnit, new.defaultDoseUnit)
        change("route", old.route, new.route)
    }
}

@Composable
fun AddMedicationScreen(
    onDone: (Long) -> Unit,
    onBack: (() -> Unit)? = null,
    vm: AddMedicationViewModel = hiltViewModel(),
) {
    val status by vm.status.collectAsState()
    val error by vm.error.collectAsState()
    val newId by vm.newId.collectAsState()
    val loaded by vm.loaded.collectAsState()
    val loadedAlias by vm.alias.collectAsState()

    if (status == AddMedicationViewModel.Status.Done && newId != null) {
        onDone(newId!!)
        return
    }

    var name by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(MedicationCatalog.KINDS.first()) }
    var route by rememberSaveable { mutableStateOf(MedicationCatalog.ROUTES.first()) }
    var dose by rememberSaveable { mutableStateOf("") }
    var unit by rememberSaveable { mutableStateOf("") }
    var notes by rememberSaveable { mutableStateOf("") }
    var notifAlias by rememberSaveable { mutableStateOf("") }
    var color by rememberSaveable { mutableStateOf<Long?>(null) }
    var seeded by rememberSaveable { mutableStateOf(false) }

    // Prefill once the existing medication loads (edit mode only).
    androidx.compose.runtime.LaunchedEffect(loaded, loadedAlias) {
        val m = loaded
        if (vm.isEditing && m != null && !seeded) {
            name = m.name
            kind = m.kind
            route = m.route
            dose = m.defaultDose?.let { formatDoseValue(it) }.orEmpty()
            unit = m.defaultDoseUnit.orEmpty()
            notes = m.notes.orEmpty()
            notifAlias = loadedAlias.orEmpty()
            color = m.color
            seeded = true
        }
    }

    val canSubmit = name.isNotBlank() && status != AddMedicationViewModel.Status.Submitting
    // No caller passes a back callback yet: the pushed screen falls back to the
    // system dispatcher, which pops exactly the same way the arrow should.
    val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val goBack: () -> Unit = onBack ?: { dispatcher?.onBackPressed() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        bottomBar = {
            ActionBand(alignment = Alignment.Center) {
                Button(
                    enabled = canSubmit,
                    shape = EggShapes.Pill,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        vm.submit(
                            NewMedication(
                                name = name.trim(),
                                kind = kind,
                                route = route,
                                defaultDose = dose.replace(',', '.').toDoubleOrNull(),
                                defaultDoseUnit = unit.ifBlank { null },
                                color = color,
                                notes = notes.ifBlank { null },
                            ),
                            notifAlias = notifAlias.ifBlank { null },
                        )
                    },
                ) {
                    Text(
                        stringResource(
                            if (vm.isEditing) R.string.action_save else R.string.med_create
                        )
                    )
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .clickToDismissKeyboard()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = EggDim.ScreenMargin),
            verticalArrangement = Arrangement.spacedBy(EggDim.BlockGap),
        ) {
            ScreenHeader(
                title = stringResource(
                    if (vm.isEditing) R.string.med_edit_title else R.string.med_add_title
                ),
                onBack = goBack,
            )

            SectionTitle(stringResource(R.string.meds_section_identity))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.med_field_name)) },
                singleLine = true,
                shape = EggShapes.Field,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                stringResource(R.string.med_field_kind),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ChipGroup(
                options = MedicationCatalog.KINDS,
                selected = kind,
                labelOf = { stringResource(MedicationCatalog.kindLabelRes(it)) },
                onSelected = { kind = it },
            )

            Text(
                stringResource(R.string.med_field_route),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ChipGroup(
                options = MedicationCatalog.ROUTES,
                selected = route,
                labelOf = { stringResource(MedicationCatalog.routeLabelRes(it)) },
                onSelected = { route = it },
            )

            SectionTitle(stringResource(R.string.meds_section_dose))
            OutlinedTextField(
                value = dose,
                onValueChange = { dose = it.filter { c -> c.isDigit() || c == '.' || c == ',' } },
                label = { Text(stringResource(R.string.med_field_default_dose)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                shape = EggShapes.Field,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = unit,
                onValueChange = { unit = it },
                label = { Text(stringResource(R.string.med_field_dose_unit)) },
                singleLine = true,
                shape = EggShapes.Field,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text(stringResource(R.string.med_field_notes)) },
                shape = EggShapes.Field,
                modifier = Modifier.fillMaxWidth(),
            )

            SectionTitle(stringResource(R.string.meds_section_notification))
            OutlinedTextField(
                value = notifAlias,
                onValueChange = { notifAlias = it.take(40) },
                label = { Text(stringResource(R.string.med_field_notif_alias)) },
                supportingText = { Text(stringResource(R.string.med_field_notif_alias_hint)) },
                singleLine = true,
                shape = EggShapes.Field,
                modifier = Modifier.fillMaxWidth(),
            )

            SectionTitle(stringResource(R.string.meds_section_color))
            ColorSwatchPicker(selected = color, onSelected = { color = it })

            error?.let {
                Text(
                    stringResource(R.string.med_error_prefix, it),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** Preset accent colours, stored as opaque ARGB (0xFFRRGGBB) so the value is
 *  rendered identically on iOS (Color from ARGB) from the shared DB. */
private val MED_COLOR_SWATCHES: List<Long> = listOf(
    0xFFE57373, 0xFFBA68C8, 0xFF9575CD, 0xFF7986CB, 0xFF4FC3F7,
    0xFF4DB6AC, 0xFF81C784, 0xFFFFD54F, 0xFFFFB74D, 0xFF90A4AE,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorSwatchPicker(selected: Long?, onSelected: (Long?) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        // "None" option.
        val noneLabel = stringResource(R.string.meds_color_none_cd)
        Box(
            modifier = Modifier
                .size(EggDim.TouchTarget)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .border(
                    width = if (selected == null) 3.dp else 1.dp,
                    color = if (selected == null) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                    shape = CircleShape,
                )
                .clickable { onSelected(null) }
                .semantics { contentDescription = noneLabel },
            contentAlignment = Alignment.Center,
        ) {
            Text("∅", style = MaterialTheme.typography.bodyMedium)
        }
        MED_COLOR_SWATCHES.forEachIndexed { index, swatch ->
            // A swatch has no name a translator could write, so the accessible
            // label numbers them — the value itself is the user's own choice.
            val label = stringResource(R.string.meds_color_swatch_cd, index + 1)
            Box(
                modifier = Modifier
                    .size(EggDim.TouchTarget)
                    .clip(CircleShape)
                    .background(Color(swatch))
                    .border(
                        width = if (selected == swatch) 3.dp else 1.dp,
                        color = if (selected == swatch) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant,
                        shape = CircleShape,
                    )
                    .clickable { onSelected(swatch) }
                    .semantics { contentDescription = label },
            )
        }
    }
}

/**
 * The filter-chip row of the Médics forms. D4: a filter chip is a different
 * species from a period pill — radius 10, not 100.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChipGroup(
    options: List<String>,
    selected: String,
    labelOf: @Composable (String) -> String,
    onSelected: (String) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        options.forEach { opt ->
            FilterChip(
                selected = opt == selected,
                onClick = { onSelected(opt) },
                label = { Text(labelOf(opt)) },
                shape = ChipGroupShape,
            )
        }
    }
}

private val ChipGroupShape = RoundedCornerShape(10.dp)
