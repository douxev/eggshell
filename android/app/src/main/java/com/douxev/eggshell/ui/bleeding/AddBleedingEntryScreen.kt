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
import com.douxev.eggshell.ui.common.MetricSlidersColumn
import com.douxev.eggshell.ui.common.clickToDismissKeyboard
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
                // (which waits on `loaded`) sees a settled values map.
                runCatching { metrics.values(MetricsRepository.DOMAIN_BLEEDING, editingId) }
                    .onSuccess { v -> _values.value = v.associate { it.metricId to it.value } }
                runCatching { repo.get(editingId) }.onSuccess { _loaded.value = it }
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
            val result = runCatching {
                val saved = if (editingId > 0L) repo.update(editingId, entry) else repo.add(entry)
                metrics.replaceValues(MetricsRepository.DOMAIN_BLEEDING, saved.id, sliderValues)
            }
            result
                .onSuccess { _status.value = Status.Done }
                .onFailure { _status.value = Status.Error }
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
    var seeded by remember { mutableStateOf(false) }

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
                    vm.submit(
                        NewBleedingEntry(
                            atMs = loaded?.atMs ?: System.currentTimeMillis(),
                            isSpotting = isSpotting,
                            freeText = freeText.ifBlank { null },
                        ),
                        sliderValues,
                    )
                },
                enabled = status != AddBleedingEntryViewModel.Status.Submitting,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.bleeding_save)) }

            if (status == AddBleedingEntryViewModel.Status.Error) {
                Text(stringResource(R.string.bleeding_error), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
