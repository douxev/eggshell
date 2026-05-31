package com.douxev.eggshell.ui.journal

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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import com.douxev.eggshell.data.JournalRepository
import com.douxev.eggshell.ui.common.clickToDismissKeyboard
import uniffi.transition.JournalEntry
import uniffi.transition.NewJournalEntry

@HiltViewModel
class AddJournalEntryViewModel @Inject constructor(
    private val repo: JournalRepository,
    state: SavedStateHandle,
) : ViewModel() {
    enum class Status { Idle, Submitting, Done, Error }

    /** Negative or -1L means "new entry". Positive id triggers edit mode. */
    val editingId: Long = state.get<Long>("id") ?: -1L

    private val _status = MutableStateFlow(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _loaded = MutableStateFlow<JournalEntry?>(null)
    val loaded: StateFlow<JournalEntry?> = _loaded.asStateFlow()

    init {
        if (editingId > 0L) {
            viewModelScope.launch {
                runCatching { repo.get(editingId) }.onSuccess { _loaded.value = it }
            }
        }
    }

    fun submit(entry: NewJournalEntry) {
        _status.value = Status.Submitting
        viewModelScope.launch {
            val result = if (editingId > 0L) runCatching { repo.replace(editingId, entry) }
            else runCatching { repo.add(entry) }
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
fun AddJournalEntryScreen(
    onDone: () -> Unit,
    vm: AddJournalEntryViewModel = hiltViewModel(),
) {
    val status by vm.status.collectAsState()
    val loaded by vm.loaded.collectAsState()
    val isEditing = vm.editingId > 0L

    if (status == AddJournalEntryViewModel.Status.Done) {
        onDone()
        return
    }

    var hasMood by mutableStateOfDefault(loaded?.mood != null || !isEditing)
    var moodVal by mutableFloatStateOfDefault((loaded?.mood?.toFloat()) ?: 5f)
    var hasDysphoria by mutableStateOfDefault(loaded?.dysphoria != null)
    var dysphoriaVal by mutableFloatStateOfDefault((loaded?.dysphoria?.toFloat()) ?: 0f)
    var hasEuphoria by mutableStateOfDefault(loaded?.euphoria != null)
    var euphoriaVal by mutableFloatStateOfDefault((loaded?.euphoria?.toFloat()) ?: 0f)
    var hasLibido by mutableStateOfDefault(loaded?.libido != null)
    var libidoVal by mutableFloatStateOfDefault((loaded?.libido?.toFloat()) ?: 5f)
    var hasEnergy by mutableStateOfDefault(loaded?.energy != null)
    var energyVal by mutableFloatStateOfDefault((loaded?.energy?.toFloat()) ?: 5f)
    var freeText by mutableStateOfDefault(loaded?.freeText.orEmpty())
    var sideEffects by mutableStateOfDefault(loaded?.sideEffects.orEmpty())

    // When editing, the entry loads asynchronously — re-seed local state once
    // it arrives so the form reflects the stored values.
    LaunchedEffect(loaded) {
        loaded?.let { e ->
            hasMood = e.mood != null
            moodVal = e.mood?.toFloat() ?: 5f
            hasDysphoria = e.dysphoria != null
            dysphoriaVal = e.dysphoria?.toFloat() ?: 0f
            hasEuphoria = e.euphoria != null
            euphoriaVal = e.euphoria?.toFloat() ?: 0f
            hasLibido = e.libido != null
            libidoVal = e.libido?.toFloat() ?: 5f
            hasEnergy = e.energy != null
            energyVal = e.energy?.toFloat() ?: 5f
            freeText = e.freeText.orEmpty()
            sideEffects = e.sideEffects.orEmpty()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (isEditing) R.string.journal_edit_title
                            else R.string.journal_add_title
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
            GaugeRow(stringResource(R.string.gauge_mood), hasMood, moodVal,
                { hasMood = it }, { moodVal = it },
                leftEmoji = "😞", rightEmoji = "😊") // 😞 → 😊
            GaugeRow(stringResource(R.string.gauge_dysphoria), hasDysphoria, dysphoriaVal,
                { hasDysphoria = it }, { dysphoriaVal = it },
                leftEmoji = "😌", rightEmoji = "😣") // 😌 → 😣
            GaugeRow(stringResource(R.string.gauge_euphoria), hasEuphoria, euphoriaVal,
                { hasEuphoria = it }, { euphoriaVal = it },
                leftEmoji = "😐", rightEmoji = "😄") // 😐 → 😄
            GaugeRow(stringResource(R.string.gauge_libido), hasLibido, libidoVal,
                { hasLibido = it }, { libidoVal = it },
                leftEmoji = "💤", rightEmoji = "🔥") // 💤 → 🔥
            GaugeRow(stringResource(R.string.gauge_energy), hasEnergy, energyVal,
                { hasEnergy = it }, { energyVal = it },
                leftEmoji = "🥱", rightEmoji = "⚡") // 🥱 → ⚡

            OutlinedTextField(
                value = freeText,
                onValueChange = { freeText = it },
                label = { Text(stringResource(R.string.journal_field_text)) },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = sideEffects,
                onValueChange = { sideEffects = it },
                label = { Text(stringResource(R.string.journal_field_side_effects)) },
                supportingText = { Text(stringResource(R.string.journal_field_side_effects_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = {
                    vm.submit(
                        NewJournalEntry(
                            atMs = loaded?.atMs ?: System.currentTimeMillis(),
                            mood = if (hasMood) moodVal.toInt().toUInt() else null,
                            dysphoria = if (hasDysphoria) dysphoriaVal.toInt().toUInt() else null,
                            euphoria = if (hasEuphoria) euphoriaVal.toInt().toUInt() else null,
                            libido = if (hasLibido) libidoVal.toInt().toUInt() else null,
                            energy = if (hasEnergy) energyVal.toInt().toUInt() else null,
                            freeText = freeText.ifBlank { null },
                            sideEffects = sideEffects.ifBlank { null },
                        )
                    )
                },
                enabled = status != AddJournalEntryViewModel.Status.Submitting,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.journal_save)) }

            if (status == AddJournalEntryViewModel.Status.Error) {
                Text(
                    stringResource(R.string.journal_error),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun GaugeRow(
    label: String,
    enabled: Boolean,
    value: Float,
    onEnabledChange: (Boolean) -> Unit,
    onValueChange: (Float) -> Unit,
    leftEmoji: String,
    rightEmoji: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }
        if (enabled) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(leftEmoji, style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = value,
                    onValueChange = onValueChange,
                    valueRange = 0f..10f,
                    steps = 9,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                        .semantics { stateDescription = "${value.toInt()} sur 10" },
                )
                Text(rightEmoji, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

// Small helper so we can avoid repeating `by remember { mutableStateOf(...) }`
// on a long list of state vars in the screen body.
@Composable
private fun mutableStateOfDefault(initial: Boolean) =
    androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(initial) }

@Composable
private fun mutableFloatStateOfDefault(initial: Float) =
    androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(initial) }

@Composable
private fun mutableStateOfDefault(initial: String) =
    androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(initial) }
