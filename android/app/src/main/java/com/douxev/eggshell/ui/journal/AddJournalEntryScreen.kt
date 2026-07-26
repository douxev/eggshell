package com.douxev.eggshell.ui.journal

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
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
import com.douxev.eggshell.data.JournalRepository
import com.douxev.eggshell.data.MetricsRepository
import com.douxev.eggshell.ui.common.MetricSliderStack
import com.douxev.eggshell.ui.common.clickToDismissKeyboard
import com.douxev.eggshell.ui.components.ActionBand
import com.douxev.eggshell.ui.components.CardVariant
import com.douxev.eggshell.ui.components.EggCard
import com.douxev.eggshell.ui.components.EggTopBar
import com.douxev.eggshell.ui.components.ErrorCard
import com.douxev.eggshell.ui.components.MicroLabel
import com.douxev.eggshell.ui.theme.EggDim
import com.douxev.eggshell.ui.theme.EggShapes
import uniffi.transition.JournalEntry
import uniffi.transition.MetricDefinition
import uniffi.transition.MetricValue
import uniffi.transition.NewJournalEntry

@HiltViewModel
class AddJournalEntryViewModel @Inject constructor(
    private val repo: JournalRepository,
    private val metrics: MetricsRepository,
    state: SavedStateHandle,
) : ViewModel() {
    enum class Status { Idle, Submitting, Done, Error }

    /** Negative or -1L means "new entry". Positive id triggers edit mode. */
    val editingId: Long = state.get<Long>("id") ?: -1L

    private val _status = MutableStateFlow(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _loaded = MutableStateFlow<JournalEntry?>(null)
    val loaded: StateFlow<JournalEntry?> = _loaded.asStateFlow()

    /**
     * The whole non-archived catalog, in order — **including the indicators the
     * user has hidden**. The form only draws the enabled ones, but the hidden
     * ones must stay in hand at save time: their stored value is carried over
     * untouched instead of being wiped (§6.2, "les valeurs déjà saisies sont
     * toujours conservées").
     */
    private val _definitions = MutableStateFlow<List<MetricDefinition>>(emptyList())
    val definitions: StateFlow<List<MetricDefinition>> = _definitions.asStateFlow()

    /** Stored custom slider values (metric_id -> value) when editing. */
    private val _customValues = MutableStateFlow<Map<Long, UInt>>(emptyMap())
    val customValues: StateFlow<Map<Long, UInt>> = _customValues.asStateFlow()

    /** Effects already used in the journal, most frequent first — the chips. */
    private val _effectSuggestions = MutableStateFlow<List<String>>(emptyList())
    val effectSuggestions: StateFlow<List<String>> = _effectSuggestions.asStateFlow()

    init {
        refreshDefinitions()
        loadEffectSuggestions()
        if (editingId > 0L) {
            viewModelScope.launch {
                // Load custom values BEFORE the entry, so the screen's seed gate
                // (which waits on `loaded`) sees a settled customValues map.
                runCatching { metrics.values(MetricsRepository.DOMAIN_JOURNAL, editingId) }
                    .onSuccess { vals -> _customValues.value = vals.associate { it.metricId to it.value } }
                runCatching { repo.get(editingId) }.onSuccess { _loaded.value = it }
            }
        }
    }

    /** Reloaded on resume so edits made in the metric editor show up. */
    fun refreshDefinitions() {
        viewModelScope.launch {
            runCatching { metrics.definitions(MetricsRepository.DOMAIN_JOURNAL) }
                .onSuccess { defs -> _definitions.value = defs }
        }
    }

    /**
     * The chip row offers what the user already writes rather than a canned
     * clinical list: the effects seen in past entries, most used first.
     */
    private fun loadEffectSuggestions() {
        viewModelScope.launch {
            val past = runCatching { repo.list(0, 200) }.getOrDefault(emptyList())
            val counts = LinkedHashMap<String, Pair<String, Int>>()
            past.asSequence()
                .mapNotNull { it.sideEffects }
                .flatMap { splitEffects(it).asSequence() }
                .forEach { raw ->
                    val key = normalizeEffect(raw)
                    val current = counts[key]
                    counts[key] = (current?.first ?: raw) to ((current?.second ?: 0) + 1)
                }
            _effectSuggestions.value = counts.values
                .sortedByDescending { it.second }
                .take(8)
                .map { it.first }
        }
    }

    fun submit(entry: NewJournalEntry, customValues: List<MetricValue>) {
        _status.value = Status.Submitting
        viewModelScope.launch {
            val result = runCatching {
                val saved = if (editingId > 0L) repo.replace(editingId, entry) else repo.add(entry)
                metrics.replaceValues(MetricsRepository.DOMAIN_JOURNAL, saved.id, customValues)
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

/** `sideEffects` is comma-separated free text — that is how the chips read it. */
fun splitEffects(raw: String): List<String> =
    raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }

/** Compare effects without case or accents so « Fatigue » and « fatigué » don't
 *  both end up in the suggestion row as if they were different things. */
fun normalizeEffect(raw: String): String =
    java.text.Normalizer.normalize(raw.trim().lowercase(Locale.ROOT), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")

/**
 * The two collections of the form need a [Saver]: « Personnaliser les
 * indicateurs » pushes a destination on top of this one, which disposes it.
 * With a plain `remember` the note, the chosen effects and every slider already
 * moved are gone by the time the user comes back from the editor.
 */
private val EffectListSaver = listSaver<SnapshotStateList<String>, String>(
    save = { it.toList() },
    restore = { it.toMutableStateList() },
)

/** A map is not a bundle type, so it travels flattened: id, value, id, value… */
private val MetricValuesSaver = listSaver<SnapshotStateMap<Long, Float>, Any>(
    save = { map -> map.entries.flatMap { listOf<Any>(it.key, it.value) } },
    restore = { flat ->
        mutableStateMapOf<Long, Float>().apply {
            flat.chunked(2).forEach { pair -> put(pair[0] as Long, pair[1] as Float) }
        }
    },
)

/**
 * Journal complet (§6.2) — the long form behind « Noter en détail ».
 *
 * One card of sliders, a free note, the effects, and a save band. The sliders
 * are whatever the catalog says they are: hiding, reordering and creating one
 * happens in the metric editor, reachable from the link at the bottom.
 *
 * [onBack] is what abandoning the form does; [onDone] is what a *successful*
 * save does. They must stay distinct: the host announces « Ressenti
 * enregistré » on [onDone], and nothing was written when the user simply left.
 * It defaults to [onDone] so a host that only wires the save path still
 * compiles.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddJournalEntryScreen(
    onDone: () -> Unit,
    onCustomize: () -> Unit,
    onBack: () -> Unit = onDone,
    vm: AddJournalEntryViewModel = hiltViewModel(),
) {
    val status by vm.status.collectAsState()
    val loaded by vm.loaded.collectAsState()
    val definitions by vm.definitions.collectAsState()
    val customValues by vm.customValues.collectAsState()
    val suggestions by vm.effectSuggestions.collectAsState()
    val isEditing = vm.editingId > 0L

    LaunchedEffect(Unit) { vm.refreshDefinitions() }
    LaunchedEffect(status) {
        if (status == AddJournalEntryViewModel.Status.Done) onDone()
    }

    val values = rememberSaveable(saver = MetricValuesSaver) { mutableStateMapOf<Long, Float>() }
    val effects = rememberSaveable(saver = EffectListSaver) { mutableStateListOf<String>() }
    var freeText by rememberSaveable { mutableStateOf("") }
    var atMs by rememberSaveable { mutableStateOf(System.currentTimeMillis()) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var showAddEffect by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    // Saveable like the fields it guards: were it not, coming back from the
    // metric editor would re-seed the form and overwrite what was restored.
    var seeded by rememberSaveable { mutableStateOf(false) }

    // Seed the form once both the catalog and (for edits) the stored values have
    // arrived. Built-in values come from the entry columns, custom ones from the
    // metric_values map; anything absent starts at the neutral midpoint.
    LaunchedEffect(definitions, loaded, customValues) {
        if (definitions.isEmpty()) return@LaunchedEffect
        if (isEditing && loaded == null) return@LaunchedEffect
        // Per-slider rather than all-or-nothing: an indicator created in the
        // metric editor arrives *after* the form was seeded, and it still needs
        // a starting value — while the sliders the user already moved keep it.
        definitions.forEach { def ->
            if (values.containsKey(def.id)) return@forEach
            val stored: UInt? = if (def.columnName != null) {
                columnValue(loaded, def.columnName!!)
            } else {
                customValues[def.id]
            }
            val mid = ((def.minValue.toInt() + def.maxValue.toInt()) / 2).toFloat()
            values[def.id] = stored?.toInt()?.toFloat() ?: mid
        }
        if (seeded) return@LaunchedEffect
        loaded?.let { entry ->
            freeText = entry.freeText.orEmpty()
            effects.clear()
            effects.addAll(splitEffects(entry.sideEffects.orEmpty()))
            atMs = entry.atMs
        }
        seeded = true
    }

    val visibleDefs = remember(definitions) { definitions.filter { it.enabled } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            EggTopBar(
                title = stringResource(R.string.feel_full_title),
                onBack = onBack,
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
        // The band is reserved, never floating: the last chip row stays readable.
        bottomBar = {
            ActionBand(alignment = Alignment.Center) {
                Button(
                    onClick = {
                        vm.submit(
                            NewJournalEntry(
                                atMs = atMs,
                                mood = columnState(definitions, values, loaded, "mood"),
                                dysphoria = columnState(definitions, values, loaded, "dysphoria"),
                                euphoria = columnState(definitions, values, loaded, "euphoria"),
                                libido = columnState(definitions, values, loaded, "libido"),
                                energy = columnState(definitions, values, loaded, "energy"),
                                freeText = freeText.ifBlank { null },
                                sideEffects = effects.joinToString(", ").ifBlank { null },
                            ),
                            customMetricValues(definitions, values, customValues),
                        )
                    },
                    enabled = status != AddJournalEntryViewModel.Status.Submitting,
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
                        stringResource(R.string.journal_save),
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
            DateLine(atMs = atMs, onEdit = { showDatePicker = true })

            EggCard(variant = CardVariant.Low, padding = PaddingValues(18.dp)) {
                if (visibleDefs.isEmpty()) {
                    Text(
                        stringResource(R.string.feel_no_sliders),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    MetricSliderStack(definitions = visibleDefs, values = values)
                }
            }

            NoteBox(value = freeText, onValueChange = { freeText = it })

            EffectChips(
                selected = effects,
                suggestions = suggestions,
                onToggle = { effect ->
                    val key = normalizeEffect(effect)
                    val existing = effects.firstOrNull { normalizeEffect(it) == key }
                    if (existing != null) effects.remove(existing) else effects.add(effect)
                },
                onAdd = { showAddEffect = true },
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(EggShapes.Pill)
                    .clickable(onClick = onCustomize)
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    stringResource(R.string.metric_editor_open),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            if (status == AddJournalEntryViewModel.Status.Error) {
                ErrorCard(message = stringResource(R.string.journal_error))
            }
        }
    }

    if (showDatePicker) {
        EntryDatePicker(
            atMs = atMs,
            onDismiss = { showDatePicker = false },
            onPicked = {
                atMs = it
                showDatePicker = false
                showTimePicker = true
            },
        )
    }
    if (showTimePicker) {
        EntryTimePicker(
            atMs = atMs,
            onDismiss = { showTimePicker = false },
            onPicked = { atMs = it; showTimePicker = false },
        )
    }
    if (showAddEffect) {
        AddEffectDialog(
            onDismiss = { showAddEffect = false },
            onAdd = { label ->
                val key = normalizeEffect(label)
                if (effects.none { normalizeEffect(it) == key }) effects.add(label)
                showAddEffect = false
            },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.feel_delete_title)) },
            text = { Text(stringResource(R.string.feel_delete_body)) },
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

/** « Aujourd'hui · 21:34 », with « Modifier » opening the pickers. */
@Composable
private fun DateLine(atMs: Long, onEdit: () -> Unit) {
    val zone = remember { ZoneId.systemDefault() }
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val dayFmt = remember { SimpleDateFormat("EEE d MMM", Locale.getDefault()) }
    val isToday = remember(atMs) {
        Instant.ofEpochMilli(atMs).atZone(zone).toLocalDate() == LocalDate.now(zone)
    }
    val label = if (isToday) {
        stringResource(R.string.feel_date_today_fmt, timeFmt.format(Date(atMs)))
    } else {
        stringResource(
            R.string.feel_date_other_fmt,
            dayFmt.format(Date(atMs)),
            timeFmt.format(Date(atMs)),
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(EggShapes.Note)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onEdit)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Schedule,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(19.dp),
        )
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            stringResource(R.string.action_edit),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** Bordered note box with its micro-label and ghost text (§6.2). */
@Composable
private fun NoteBox(value: String, onValueChange: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, EggShapes.Note)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        MicroLabel(stringResource(R.string.feel_note_label))
        Box(modifier = Modifier.padding(top = 6.dp)) {
            if (value.isEmpty()) {
                Text(
                    stringResource(R.string.feel_note_ghost),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
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
}

/** Selectable effect chips plus the « Ajouter » chip (§6.2). */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun EffectChips(
    selected: List<String>,
    suggestions: List<String>,
    onToggle: (String) -> Unit,
    onAdd: () -> Unit,
) {
    val pool = remember(selected.toList(), suggestions) {
        val keys = selected.map { normalizeEffect(it) }.toMutableSet()
        selected + suggestions.filter { keys.add(normalizeEffect(it)) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        MicroLabel(stringResource(R.string.feel_effects_label))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            pool.forEach { effect ->
                val isOn = selected.any { normalizeEffect(it) == normalizeEffect(effect) }
                FilterChip(
                    selected = isOn,
                    onClick = { onToggle(effect) },
                    label = { Text(effect) },
                    shape = RoundedCornerShape(10.dp),
                    leadingIcon = if (isOn) {
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
            AssistChip(
                onClick = onAdd,
                label = { Text(stringResource(R.string.feel_effect_add)) },
                shape = RoundedCornerShape(10.dp),
                leadingIcon = {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = null,
                        modifier = Modifier.size(AssistChipDefaults.IconSize),
                    )
                },
            )
        }
    }
}

@Composable
private fun AddEffectDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.feel_effect_add_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(40) },
                placeholder = { Text(stringResource(R.string.feel_effect_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(text.trim()) },
                enabled = text.isNotBlank(),
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * Date half of the picker chain. A feeling can be back-dated but never
 * post-dated: the picker reports UTC midnight, so the picked day is
 * re-expressed at the local zone to avoid a day-shift in negative offsets.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntryDatePicker(atMs: Long, onDismiss: () -> Unit, onPicked: (Long) -> Unit) {
    val zone = remember { ZoneId.systemDefault() }
    val selectableDates = remember {
        object : SelectableDates {
            private val maxExclusive = LocalDate.now(zone).plusDays(1)
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis < maxExclusive
            override fun isSelectableYear(year: Int) = year <= LocalDate.now(zone).year
        }
    }
    val initialUtcMidnight = Instant.ofEpochMilli(atMs).atZone(zone)
        .toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val dateState = rememberDatePickerState(
        initialSelectedDateMillis = initialUtcMidnight,
        selectableDates = selectableDates,
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val picked = dateState.selectedDateMillis
                if (picked == null) {
                    onDismiss()
                } else {
                    val day = Instant.ofEpochMilli(picked).atZone(ZoneOffset.UTC).toLocalDate()
                    val prev = Instant.ofEpochMilli(atMs).atZone(zone)
                    onPicked(
                        day.atTime(prev.hour, prev.minute).atZone(zone)
                            .toInstant().toEpochMilli()
                            .coerceAtMost(System.currentTimeMillis()),
                    )
                }
            }) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
        colors = DatePickerDefaults.colors(),
    ) { DatePicker(state = dateState) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntryTimePicker(atMs: Long, onDismiss: () -> Unit, onPicked: (Long) -> Unit) {
    val zone = remember { ZoneId.systemDefault() }
    val cur = Instant.ofEpochMilli(atMs).atZone(zone)
    val is24h = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
    val timeState = rememberTimePickerState(
        initialHour = cur.hour,
        initialMinute = cur.minute,
        is24Hour = is24h,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onPicked(
                    cur.toLocalDate().atTime(timeState.hour, timeState.minute)
                        .atZone(zone).toInstant().toEpochMilli()
                        .coerceAtMost(System.currentTimeMillis()),
                )
            }) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
        title = { Text(stringResource(R.string.datetime_pick_time), textAlign = TextAlign.Start) },
        text = {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                TimePicker(state = timeState)
            }
        },
    )
}

/** Read a built-in gauge value off the loaded entry by its backing column. */
private fun columnValue(entry: JournalEntry?, column: String): UInt? = when (column) {
    "mood" -> entry?.mood
    "dysphoria" -> entry?.dysphoria
    "euphoria" -> entry?.euphoria
    "libido" -> entry?.libido
    "energy" -> entry?.energy
    else -> null
}

/**
 * Value to persist into a built-in journal column. A hidden indicator is not
 * drawn, so it has no slider to read: its previously recorded value is carried
 * over verbatim rather than nulled out.
 */
private fun columnState(
    definitions: List<MetricDefinition>,
    values: SnapshotStateMap<Long, Float>,
    loaded: JournalEntry?,
    column: String,
): UInt? {
    val def = definitions.firstOrNull { it.columnName == column } ?: return columnValue(loaded, column)
    if (!def.enabled) return columnValue(loaded, column)
    return values[def.id]?.toInt()?.toUInt() ?: columnValue(loaded, column)
}

/**
 * Custom (non-column-backed) slider values to persist: what the form shows,
 * plus the stored values of every indicator it doesn't show — `replaceValues`
 * is a full replacement, so omitting them would delete them.
 */
private fun customMetricValues(
    definitions: List<MetricDefinition>,
    values: SnapshotStateMap<Long, Float>,
    stored: Map<Long, UInt>,
): List<MetricValue> {
    val shown = definitions.filter { it.columnName == null && it.enabled }
    val shownIds = shown.map { it.id }.toSet()
    val fromForm = shown.mapNotNull { def ->
        values[def.id]?.let { MetricValue(metricId = def.id, value = it.toInt().toUInt()) }
    }
    val preserved = stored
        .filterKeys { it !in shownIds }
        .map { (id, value) -> MetricValue(metricId = id, value = value) }
    return fromForm + preserved
}
