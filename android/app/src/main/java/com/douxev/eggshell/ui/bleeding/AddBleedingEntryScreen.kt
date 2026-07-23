package com.douxev.eggshell.ui.bleeding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import com.douxev.eggshell.ui.common.MetricSlidersColumn
import com.douxev.eggshell.ui.common.clickToDismissKeyboard
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import uniffi.transition.BleedingEntry
import uniffi.transition.MetricDefinition
import uniffi.transition.MetricValue
import uniffi.transition.NewBleedingEntry

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
                .onSuccess { defs -> _definitions.value = defs.filter { it.enabled } }
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

    if (status == AddBleedingEntryViewModel.Status.Done) {
        onDone()
        return
    }

    val enabled = remember { mutableStateMapOf<Long, Boolean>() }
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
    var seeded by remember { mutableStateOf(false) }
    val zone = remember { ZoneId.systemDefault() }

    LaunchedEffect(definitions, loaded, storedValues) {
        if (definitions.isEmpty()) return@LaunchedEffect
        if (seeded) return@LaunchedEffect
        if (isEditing && loaded == null) return@LaunchedEffect
        definitions.forEach { def ->
            val stored = storedValues[def.id]
            enabled[def.id] = if (isEditing) stored != null else def.builtin
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (isEditing) R.string.bleeding_edit_title else R.string.bleeding_add_title
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    if (isEditing) {
                        IconButton(onClick = vm::delete) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.action_delete),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .clickToDismissKeyboard()
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!isEditing) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !rangeMode,
                        onClick = { rangeMode = false },
                        label = { Text(stringResource(R.string.bleeding_mode_single)) },
                    )
                    FilterChip(
                        selected = rangeMode,
                        onClick = { rangeMode = true },
                        label = { Text(stringResource(R.string.bleeding_mode_range)) },
                    )
                }
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
                    val dayCount = ChronoUnit.DAYS.between(s, e).toInt() + 1
                    Text(
                        stringResource(R.string.bleeding_range_count_fmt, dayCount),
                        style = MaterialTheme.typography.bodySmall,
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

            Text(stringResource(R.string.bleeding_kind_label), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = isSpotting == false,
                    onClick = { isSpotting = if (isSpotting == false) null else false },
                    label = { Text(stringResource(R.string.bleeding_kind_bleed)) },
                )
                FilterChip(
                    selected = isSpotting == true,
                    onClick = { isSpotting = if (isSpotting == true) null else true },
                    label = { Text(stringResource(R.string.bleeding_kind_spotting)) },
                )
            }

            MetricSlidersColumn(definitions = definitions, enabled = enabled, values = values)

            OutlinedTextField(
                value = freeText,
                onValueChange = { freeText = it },
                label = { Text(stringResource(R.string.bleeding_field_note)) },
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = {
                    val sliderValues = definitions
                        .filter { enabled[it.id] == true }
                        .mapNotNull { def ->
                            values[def.id]?.let { MetricValue(metricId = def.id, value = it.toInt().toUInt()) }
                        }
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
                enabled = status != AddBleedingEntryViewModel.Status.Submitting &&
                    (!isEditing || loaded != null) &&
                    (!rangeMode || isEditing || (rangeStart != null && rangeEnd != null)),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.bleeding_save)) }

            if (status == AddBleedingEntryViewModel.Status.Error) {
                Text(
                    stringResource(
                        if (isEditing && loaded == null) R.string.bleeding_load_error
                        else R.string.bleeding_error
                    ),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
