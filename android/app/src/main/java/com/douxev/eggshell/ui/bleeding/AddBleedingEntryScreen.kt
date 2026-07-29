package com.douxev.eggshell.ui.bleeding

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
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
import com.douxev.eggshell.data.BleedingRepository
import com.douxev.eggshell.data.MetricsRepository
import com.douxev.eggshell.ui.common.DateRangePickerField
import com.douxev.eggshell.ui.common.DateTimePickerField
import com.douxev.eggshell.ui.common.MetricSliderStack
import com.douxev.eggshell.ui.common.clickToDismissKeyboard
import com.douxev.eggshell.ui.components.ActionBand
import com.douxev.eggshell.ui.components.CardVariant
import com.douxev.eggshell.ui.components.EggCard
import com.douxev.eggshell.ui.components.EggTopBar
import com.douxev.eggshell.ui.components.ErrorCard
import com.douxev.eggshell.ui.components.MicroLabel
import com.douxev.eggshell.ui.components.Segmented
import com.douxev.eggshell.ui.theme.EggDim
import com.douxev.eggshell.ui.theme.EggShapes
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import uniffi.transition.BleedingEntry
import uniffi.transition.MetricDefinition
import uniffi.transition.MetricValue
import uniffi.transition.NewBleedingEntry
import kotlin.math.roundToInt

@HiltViewModel
class AddBleedingEntryViewModel @Inject constructor(
    private val repo: BleedingRepository,
    private val metrics: MetricsRepository,
    state: SavedStateHandle,
) : ViewModel() {
    enum class Status { Idle, Submitting, Done, Error }

    val editingId: Long = state.get<Long>("id") ?: -1L

    private val _status = MutableStateFlow(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _loaded = MutableStateFlow<BleedingEntry?>(null)
    val loaded: StateFlow<BleedingEntry?> = _loaded.asStateFlow()

    /** The whole catalog, hidden indicators included — see the journal form:
     *  a hidden slider keeps the value it was last given. */
    private val _definitions = MutableStateFlow<List<MetricDefinition>>(emptyList())
    val definitions: StateFlow<List<MetricDefinition>> = _definitions.asStateFlow()

    private val _values = MutableStateFlow<Map<Long, UInt>>(emptyMap())
    val values: StateFlow<Map<Long, UInt>> = _values.asStateFlow()

    init {
        refreshDefinitions()
        if (editingId > 0L) {
            viewModelScope.launch {
                // Load slider values BEFORE the entry, so the screen's seed gate
                // (which waits on `loaded`) sees a settled values map. Either
                // load failing must block the form (`loaded` stays null, Save
                // stays disabled): saving an unseeded form would move the entry
                // to today and wipe its stored sliders.
                val vals = runCatching { metrics.values(MetricsRepository.DOMAIN_BLEEDING, editingId) }
                    .getOrNull()
                val entry = runCatching { repo.get(editingId) }.getOrNull()
                if (vals == null || entry == null) {
                    _status.value = Status.Error
                    return@launch
                }
                _values.value = vals.associate { it.metricId to it.value }
                _loaded.value = entry
            }
        }
    }

    fun refreshDefinitions() {
        viewModelScope.launch {
            runCatching { metrics.definitions(MetricsRepository.DOMAIN_BLEEDING) }
                .onSuccess { defs -> _definitions.value = defs }
        }
    }

    fun submit(entry: NewBleedingEntry, sliderValues: List<MetricValue>) {
        _status.value = Status.Submitting
        viewModelScope.launch {
            if (editingId > 0L) {
                // Editing is idempotent — safe to retry as one unit.
                runCatching {
                    val saved = repo.update(editingId, entry)
                    metrics.replaceValues(MetricsRepository.DOMAIN_BLEEDING, saved.id, sliderValues)
                }
                    .onSuccess { _status.value = Status.Done }
                    .onFailure { _status.value = Status.Error }
            } else {
                val saved = runCatching { repo.add(entry) }.getOrNull()
                if (saved == null) {
                    _status.value = Status.Error
                    return@launch
                }
                // Best-effort like submitMany: the entry is committed and a
                // retry after a slider-write failure would duplicate it.
                runCatching {
                    metrics.replaceValues(MetricsRepository.DOMAIN_BLEEDING, saved.id, sliderValues)
                }
                _status.value = Status.Done
            }
        }
    }

    /** Log a whole span of days in one action — one entry per day, all sharing
     *  the same kind/note/slider values. */
    fun submitMany(entries: List<NewBleedingEntry>, sliderValues: List<MetricValue>) {
        if (entries.isEmpty()) return
        _status.value = Status.Submitting
        viewModelScope.launch {
            val saved = runCatching { repo.addMany(entries) }.getOrNull()
            if (saved == null) {
                _status.value = Status.Error
                return@launch
            }
            // Slider writes are best-effort: the days are committed, and
            // surfacing an error here would invite a retry that duplicates
            // the whole span. Each day stays individually editable.
            runCatching {
                saved.forEach { metrics.replaceValues(MetricsRepository.DOMAIN_BLEEDING, it.id, sliderValues) }
            }
            _status.value = Status.Done
        }
    }

    fun delete() {
        if (editingId <= 0L) return
        _status.value = Status.Submitting
        viewModelScope.launch {
            runCatching { repo.delete(editingId) }
                .onSuccess { _status.value = Status.Done }
                .onFailure { _status.value = Status.Error }
        }
    }
}

/**
 * Noter mes menstruations — one day, or a whole span in a single gesture.
 *
 * It records what happened and nothing else: no cycle length, no prediction,
 * no « next period in N days ».
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddBleedingEntryScreen(
    onDone: () -> Unit,
    vm: AddBleedingEntryViewModel = hiltViewModel(),
) {
    val status by vm.status.collectAsState()
    val loaded by vm.loaded.collectAsState()
    val definitions by vm.definitions.collectAsState()
    val storedValues by vm.values.collectAsState()
    val isEditing = vm.editingId > 0L

    LaunchedEffect(Unit) { vm.refreshDefinitions() }
    LaunchedEffect(status) {
        if (status == AddBleedingEntryViewModel.Status.Done) onDone()
    }

    val values = remember { mutableStateMapOf<Long, Float>() }
    // null = unspecified, true = spotting, false = full bleed.
    var isSpotting by remember { mutableStateOf<Boolean?>(null) }
    var freeText by remember { mutableStateOf("") }
    // Editable date — defaults to now, so back-dating a forgotten day (or a
    // whole past cycle) is just a tap away.
    var atMs by remember { mutableStateOf(System.currentTimeMillis()) }
    // Range mode: log « cette semaine = règles » in one action.
    var rangeMode by remember { mutableStateOf(false) }
    var rangeStart by remember { mutableStateOf<LocalDate?>(null) }
    var rangeEnd by remember { mutableStateOf<LocalDate?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var seeded by remember { mutableStateOf(false) }
    val zone = remember { ZoneId.systemDefault() }

    LaunchedEffect(definitions, loaded, storedValues) {
        if (definitions.isEmpty()) return@LaunchedEffect
        if (seeded) return@LaunchedEffect
        if (isEditing && loaded == null) return@LaunchedEffect
        definitions.forEach { def ->
            val stored = storedValues[def.id]
            val mid = ((def.minValue.toInt() + def.maxValue.toInt()) / 2).toFloat()
            values[def.id] = stored?.toInt()?.toFloat() ?: mid
        }
        loaded?.let {
            isSpotting = it.isSpotting
            freeText = it.freeText.orEmpty()
            atMs = it.atMs
        }
        seeded = true
    }

    val visibleDefs = remember(definitions) { definitions.filter { it.enabled } }
    val canSave = status != AddBleedingEntryViewModel.Status.Submitting &&
        (!isEditing || loaded != null) &&
        (!rangeMode || isEditing || (rangeStart != null && rangeEnd != null))

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            EggTopBar(
                title = stringResource(
                    if (isEditing) R.string.bleeding_edit_title else R.string.bleeding_add_title
                ),
                onBack = onDone,
                backContentDescription = stringResource(R.string.action_back),
                actions = {
                    if (isEditing) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.action_delete),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            ActionBand(alignment = Alignment.Center) {
                Button(
                    onClick = {
                        val sliderValues = bleedingMetricValues(definitions, values, storedValues)
                        val s = rangeStart
                        val e = rangeEnd
                        if (rangeMode && !isEditing && s != null && e != null) {
                            val entries = generateSequence(s) { it.plusDays(1) }
                                .takeWhile { !it.isAfter(e) }
                                .map { day ->
                                    NewBleedingEntry(
                                        // Noon keeps the entry inside its calendar day
                                        // across every DST shift.
                                        atMs = day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli(),
                                        isSpotting = isSpotting,
                                        freeText = freeText.ifBlank { null },
                                    )
                                }
                                .toList()
                            vm.submitMany(entries, sliderValues)
                        } else {
                            vm.submit(
                                NewBleedingEntry(
                                    atMs = atMs,
                                    isSpotting = isSpotting,
                                    freeText = freeText.ifBlank { null },
                                ),
                                sliderValues,
                            )
                        }
                    },
                    // In edit mode, block Save until entry + sliders actually
                    // loaded — an unseeded save would rewrite the record.
                    enabled = canSave,
                    shape = EggShapes.Pill,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text(
                        stringResource(R.string.bleeding_save),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 8.dp),
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
                .padding(horizontal = EggDim.ScreenMargin)
                .padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (!isEditing) {
                Segmented(
                    options = listOf(
                        stringResource(R.string.bleeding_mode_single),
                        stringResource(R.string.bleeding_mode_range),
                    ),
                    selectedIndex = if (rangeMode) 1 else 0,
                    onSelect = { rangeMode = it == 1 },
                )
            }

            if (rangeMode && !isEditing) {
                DateRangePickerField(
                    label = stringResource(R.string.bleeding_range_label),
                    start = rangeStart,
                    end = rangeEnd,
                    onChange = { s, e -> rangeStart = s; rangeEnd = e },
                    allowFuture = false,
                    modifier = Modifier.fillMaxWidth(),
                )
                val s = rangeStart
                val e = rangeEnd
                if (s != null && e != null) {
                    Text(
                        stringResource(
                            R.string.bleeding_range_count_fmt,
                            ChronoUnit.DAYS.between(s, e).toInt() + 1,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                DateTimePickerField(
                    label = stringResource(R.string.bleeding_date_label),
                    atMs = atMs,
                    onChange = { atMs = it },
                    allowFuture = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MicroLabel(stringResource(R.string.feel_bleeding_kind_label))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KindChip(
                        label = stringResource(R.string.bleeding_kind_bleed),
                        selected = isSpotting == false,
                        // Tapping the selected chip again clears back to
                        // « non précisé » — the kind is never forced.
                        onClick = { isSpotting = if (isSpotting == false) null else false },
                    )
                    KindChip(
                        label = stringResource(R.string.bleeding_kind_spotting),
                        selected = isSpotting == true,
                        onClick = { isSpotting = if (isSpotting == true) null else true },
                    )
                }
            }

            if (visibleDefs.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MicroLabel(stringResource(R.string.feel_bleeding_symptoms_label))
                    EggCard(variant = CardVariant.Low, padding = PaddingValues(18.dp)) {
                        MetricSliderStack(definitions = visibleDefs, values = values)
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, EggShapes.Note)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                MicroLabel(stringResource(R.string.feel_bleeding_note_label))
                Box(modifier = Modifier.padding(top = 6.dp)) {
                    if (freeText.isEmpty()) {
                        Text(
                            stringResource(R.string.feel_bleeding_note_ghost),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    BasicTextField(
                        value = freeText,
                        onValueChange = { freeText = it },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 44.dp),
                    )
                }
            }

            if (status == AddBleedingEntryViewModel.Status.Error) {
                ErrorCard(
                    message = stringResource(
                        if (isEditing && loaded == null) R.string.bleeding_load_error
                        else R.string.bleeding_error
                    ),
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.feel_bleeding_delete_title)) },
            text = { Text(stringResource(R.string.feel_bleeding_delete_body)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.delete() }) {
                    Text(
                        stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KindChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        shape = RoundedCornerShape(10.dp),
        leadingIcon = if (selected) {
            {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                )
            }
        } else {
            null
        },
    )
}

/**
 * What to persist: the sliders the form shows, plus the stored values of the
 * indicators it doesn't — `replaceValues` is a full replacement, so leaving a
 * hidden indicator out would silently delete what the user once recorded.
 */
private fun bleedingMetricValues(
    definitions: List<MetricDefinition>,
    values: Map<Long, Float>,
    stored: Map<Long, UInt>,
): List<MetricValue> {
    val shown = definitions.filter { it.enabled }
    val shownIds = shown.map { it.id }.toSet()
    val fromForm = shown.mapNotNull { def ->
        values[def.id]?.let { MetricValue(metricId = def.id, value = it.roundToInt().toUInt()) }
    }
    val preserved = stored
        .filterKeys { it !in shownIds }
        .map { (id, value) -> MetricValue(metricId = id, value = value) }
    return fromForm + preserved
}
