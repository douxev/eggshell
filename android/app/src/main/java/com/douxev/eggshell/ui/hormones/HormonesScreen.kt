package com.douxev.eggshell.ui.hormones

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.douxev.eggshell.R
import com.douxev.eggshell.data.HormoneUnitPrefs
import com.douxev.eggshell.data.HormonesRepository
import uniffi.transition.HormoneMeasurement

/** Measurement plus its display-time conversion to the user's preferred unit. */
data class DisplayMeasurement(
    val raw: HormoneMeasurement,
    val displayValue: Double,
    val displayUnit: String,
)

enum class HormonesTab { Hormones, Weight }

@HiltViewModel
class HormonesViewModel @Inject constructor(
    private val repo: HormonesRepository,
    private val units: HormoneUnitPrefs,
    navTabsPrefs: com.douxev.eggshell.data.FeaturesPrefs,
) : ViewModel() {
    val weightTrackingEnabled: StateFlow<Boolean> = navTabsPrefs.weightTracking
    private val _hormones = MutableStateFlow<List<String>>(emptyList())
    val hormones: StateFlow<List<String>> = _hormones.asStateFlow()
    private val _selected = MutableStateFlow<String?>(null)
    val selected: StateFlow<String?> = _selected.asStateFlow()
    private val _measurements = MutableStateFlow<List<DisplayMeasurement>>(emptyList())
    val measurements: StateFlow<List<DisplayMeasurement>> = _measurements.asStateFlow()
    private val _preferredUnit = MutableStateFlow<String?>(null)
    val preferredUnit: StateFlow<String?> = _preferredUnit.asStateFlow()

    private val _tab = MutableStateFlow(HormonesTab.Hormones)
    val tab: StateFlow<HormonesTab> = _tab.asStateFlow()

    init { refresh() }

    fun setTab(t: HormonesTab) {
        if (_tab.value == t) return
        _tab.value = t
        // Clear the selected hormone so refresh() picks the right default
        // for the new tab (first hormone vs. forced "weight").
        _selected.value = null
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            when (_tab.value) {
                HormonesTab.Hormones -> {
                    // Hide "weight" from the hormones list — it lives in its
                    // own tab even though it shares the underlying table.
                    _hormones.value = runCatching { repo.distinct() }.getOrDefault(emptyList())
                        .filter { it != HormoneCatalog.WEIGHT }
                    if (_selected.value == null) _selected.value = _hormones.value.firstOrNull()
                }
                HormonesTab.Weight -> {
                    _hormones.value = listOf(HormoneCatalog.WEIGHT)
                    _selected.value = HormoneCatalog.WEIGHT
                }
            }
            loadMeasurements()
        }
    }

    fun select(hormone: String) {
        _selected.value = hormone
        viewModelScope.launch { loadMeasurements() }
    }

    fun addWeight(value: Double, unit: String, atMs: Long = System.currentTimeMillis()) {
        viewModelScope.launch {
            runCatching {
                repo.add(
                    uniffi.transition.NewHormoneMeasurement(
                        atMs = atMs,
                        hormone = HormoneCatalog.WEIGHT,
                        value = value,
                        unit = unit,
                        labName = null,
                        notes = null,
                    )
                )
            }
            refresh()
        }
    }

    /** Replace an existing measurement (by id) with new values + date. The
     *  hormone kind stays the same as the original. */
    fun updateMeasurement(original: uniffi.transition.HormoneMeasurement, value: Double, unit: String, atMs: Long) {
        viewModelScope.launch {
            runCatching {
                repo.replace(
                    original.id,
                    uniffi.transition.NewHormoneMeasurement(
                        atMs = atMs,
                        hormone = original.hormone,
                        value = value,
                        unit = unit,
                        labName = original.labName,
                        notes = original.notes,
                    ),
                )
            }
            refresh()
        }
    }

    fun deleteMeasurement(id: Long) {
        viewModelScope.launch {
            runCatching { repo.delete(id) }
            refresh()
        }
    }

    private suspend fun loadMeasurements() {
        val h = _selected.value
        if (h == null) {
            _measurements.value = emptyList()
            _preferredUnit.value = null
            return
        }
        val target = if (h == HormoneCatalog.WEIGHT) {
            // Weight defaults to kg; kg ↔ lb conversion is a local helper
            // because the Rust core's convertHormoneValue doesn't know weight.
            units.getEffective(h) ?: "kg"
        } else {
            units.getEffective(h)
        }
        _preferredUnit.value = target
        val raw = runCatching { repo.listForHormone(h) }.getOrDefault(emptyList())
        _measurements.value = raw.map { m ->
            val converted = when {
                target == null || target == m.unit -> null
                h == HormoneCatalog.WEIGHT -> HormoneCatalog.convertWeight(m.value, m.unit, target)
                else -> repo.convert(m.value, m.unit, target, h)
            }
            DisplayMeasurement(
                raw = m,
                displayValue = converted ?: m.value,
                displayUnit = if (converted != null) target!! else m.unit,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HormonesScreen(
    onAdd: () -> Unit,
    onImport: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    vm: HormonesViewModel = hiltViewModel(),
) {
    val hormones by vm.hormones.collectAsState()
    val selected by vm.selected.collectAsState()
    val measurements by vm.measurements.collectAsState()
    val tab by vm.tab.collectAsState()
    val weightTrackingEnabled by vm.weightTrackingEnabled.collectAsState()
    LaunchedEffect(Unit) { vm.refresh() }
    // If the user turns off weight tracking while sitting on the Poids tab,
    // bounce them back to the Hormones tab so they aren't stuck on a hidden
    // section.
    LaunchedEffect(weightTrackingEnabled) {
        if (!weightTrackingEnabled && tab == HormonesTab.Weight) {
            vm.setTab(HormonesTab.Hormones)
        }
    }
    var addWeightOpen by androidx.compose.runtime.saveable.rememberSaveable {
        androidx.compose.runtime.mutableStateOf(false)
    }
    // Edit-on-tap: when the user taps a history row we surface this dialog
    // pre-filled with the row's value/unit/date. Save replaces the entry;
    // delete drops it. Identical UX for hormones and weight rows.
    var editing by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<DisplayMeasurement?>(null)
    }

    Scaffold(
        floatingActionButton = {
            // Hormones tab → existing dedicated add screen.
            // Weight tab → quick inline dialog (single value + unit, that's it).
            FloatingActionButton(
                onClick = {
                    when (tab) {
                        HormonesTab.Hormones -> onAdd()
                        HormonesTab.Weight -> { addWeightOpen = true }
                    }
                },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = when (tab) {
                        HormonesTab.Hormones -> stringResource(R.string.hormones_new)
                        HormonesTab.Weight -> stringResource(R.string.weight_add_fab)
                    },
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            com.douxev.eggshell.ui.common.ScreenHeader(
                title = stringResource(R.string.hormones_title),
                onOpenSettings = onOpenSettings,
            )

            // Tabs: Hormones / Poids. The Poids tab is only offered when
            // the user has opted-in to weight tracking (Réglages → Plus →
            // Suivi du poids).
            if (weightTrackingEnabled) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = tab == HormonesTab.Hormones,
                        onClick = { vm.setTab(HormonesTab.Hormones) },
                        label = { Text(stringResource(R.string.hormones_tab_hormones)) },
                        shape = RoundedCornerShape(50),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        ),
                    )
                    FilterChip(
                        selected = tab == HormonesTab.Weight,
                        onClick = { vm.setTab(HormonesTab.Weight) },
                        label = { Text(stringResource(R.string.hormones_tab_weight)) },
                        shape = RoundedCornerShape(50),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        ),
                    )
                }
            }

            // Hormone selector chips: only in the Hormones tab. The Poids
            // tab has a single implicit kind ("weight") so we don't need a
            // chip row above the chart.
            if (tab == HormonesTab.Hormones && hormones.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    hormones.forEach { h ->
                        FilterChip(
                            selected = h == selected,
                            onClick = { vm.select(h) },
                            label = { Text(HormoneCatalog.kindLabel(h)) },
                            shape = RoundedCornerShape(50),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                            ),
                        )
                    }
                }
            }

            // OCR import shortcut — only on the Hormones tab; nobody scans a
            // weight reading from a sheet of paper.
            if (tab == HormonesTab.Hormones) {
                androidx.compose.material3.OutlinedButton(
                    onClick = onImport,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    androidx.compose.material3.Icon(
                        Icons.Filled.PhotoLibrary,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Text(stringResource(R.string.import_lab_entry))
                }
            }

            if (measurements.isNotEmpty()) {
                val sorted = remember(measurements) { measurements.sortedBy { it.raw.atMs } }
                val latest = sorted.last()
                val prev = sorted.dropLast(1).lastOrNull()
                LatestCard(latest = latest, prev = prev, sortedAsc = sorted, weight = tab == HormonesTab.Weight)
                Text(
                    stringResource(R.string.hormones_history),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 6.dp),
                )
                HistoryCard(
                    items = measurements.sortedByDescending { it.raw.atMs },
                    onItemClick = { entry -> editing = entry },
                )
            } else {
                // Empty state copy depends on which tab the user is on.
                when (tab) {
                    HormonesTab.Weight -> EmptyWeightCard()
                    HormonesTab.Hormones ->
                        if (hormones.isEmpty()) EmptyHormonesCard() else EmptyHormonesCard()
                }
            }

            Spacer(Modifier.height(96.dp))
        }
    }

    if (addWeightOpen) {
        AddWeightDialog(
            onDismiss = { addWeightOpen = false },
            onSave = { value, unit, atMs ->
                vm.addWeight(value, unit, atMs)
                addWeightOpen = false
            },
        )
    }

    editing?.let { entry ->
        EditMeasurementDialog(
            entry = entry,
            isWeight = entry.raw.hormone == HormoneCatalog.WEIGHT,
            onDismiss = { editing = null },
            onSave = { value, unit, atMs ->
                vm.updateMeasurement(entry.raw, value, unit, atMs)
                editing = null
            },
            onDelete = {
                vm.deleteMeasurement(entry.raw.id)
                editing = null
            },
        )
    }
}

@Composable
private fun EmptyHormonesCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            stringResource(R.string.hormones_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(24.dp),
        )
    }
}

@Composable
private fun EmptyWeightCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            stringResource(R.string.weight_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(24.dp),
        )
    }
}

/**
 * Quick weight-entry dialog: one decimal field + kg/lb chip group + save.
 * Date defaults to "now" — if the user wants a back-dated pesée they can
 * still use the full add screen via the Hormones tab.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AddWeightDialog(
    onDismiss: () -> Unit,
    onSave: (value: Double, unit: String, atMs: Long) -> Unit,
) {
    MeasurementDialog(
        titleRes = R.string.weight_add_title,
        initialValueText = "",
        initialUnit = "kg",
        unitOptions = HormoneCatalog.WEIGHT_UNITS,
        initialAtMs = System.currentTimeMillis(),
        valueLabelRes = R.string.weight_value_label,
        confirmRes = R.string.weight_save,
        onDismiss = onDismiss,
        onSave = onSave,
        onDelete = null,
    )
}

/**
 * Unified edit/delete dialog. Same shape as the add-weight dialog plus a
 * delete button when an existing entry is supplied.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun EditMeasurementDialog(
    entry: DisplayMeasurement,
    isWeight: Boolean,
    onDismiss: () -> Unit,
    onSave: (value: Double, unit: String, atMs: Long) -> Unit,
    onDelete: () -> Unit,
) {
    // We let the user edit in whatever unit they originally saved — that
    // keeps the original record meaningful (re-entering a converted value
    // back would lose precision). Unit chips therefore offer the full set
    // for the kind: weight → kg/lb, hormone → the curated UNITS list.
    val units = if (isWeight) HormoneCatalog.WEIGHT_UNITS else HormoneCatalog.UNITS
    MeasurementDialog(
        titleRes = if (isWeight) R.string.weight_edit_title else R.string.hormones_edit_title,
        initialValueText = formatDouble(entry.raw.value),
        initialUnit = entry.raw.unit,
        unitOptions = units,
        initialAtMs = entry.raw.atMs,
        valueLabelRes = if (isWeight) R.string.weight_value_label else R.string.hormones_field_value,
        confirmRes = R.string.action_save,
        onDismiss = onDismiss,
        onSave = onSave,
        onDelete = onDelete,
    )
}

/**
 * Generic value+unit+date dialog shared by add-weight and edit flows. Uses
 * Material 3's [androidx.compose.material3.DatePickerDialog] for the date
 * — same picker the rest of Compose-using apps surface, so the UX feels
 * native.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun MeasurementDialog(
    titleRes: Int,
    initialValueText: String,
    initialUnit: String,
    unitOptions: List<String>,
    initialAtMs: Long,
    valueLabelRes: Int,
    confirmRes: Int,
    onDismiss: () -> Unit,
    onSave: (value: Double, unit: String, atMs: Long) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var raw by androidx.compose.runtime.saveable.rememberSaveable(initialValueText) {
        androidx.compose.runtime.mutableStateOf(initialValueText)
    }
    var unit by androidx.compose.runtime.saveable.rememberSaveable(initialUnit) {
        androidx.compose.runtime.mutableStateOf(initialUnit)
    }
    var atMs by androidx.compose.runtime.saveable.rememberSaveable(initialAtMs) {
        androidx.compose.runtime.mutableLongStateOf(initialAtMs)
    }
    var datePickerOpen by androidx.compose.runtime.saveable.rememberSaveable {
        androidx.compose.runtime.mutableStateOf(false)
    }
    var deleteConfirmOpen by androidx.compose.runtime.saveable.rememberSaveable {
        androidx.compose.runtime.mutableStateOf(false)
    }
    val parsed = raw.replace(',', '.').toDoubleOrNull()
    val canSave = parsed != null && parsed > 0.0

    val dateFmt = remember { SimpleDateFormat("d MMM yyyy", Locale.getDefault()) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                androidx.compose.material3.OutlinedTextField(
                    value = raw,
                    onValueChange = { raw = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(6) },
                    label = { Text(stringResource(valueLabelRes)) },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    unitOptions.forEach { u ->
                        FilterChip(
                            selected = u == unit,
                            onClick = { unit = u },
                            label = { Text(u) },
                            shape = RoundedCornerShape(50),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                            ),
                        )
                    }
                }
                androidx.compose.material3.OutlinedButton(
                    onClick = { datePickerOpen = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(
                            R.string.hormones_date_pick_fmt,
                            dateFmt.format(Date(atMs)),
                        ),
                    )
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                enabled = canSave,
                onClick = {
                    val v = parsed ?: return@TextButton
                    onSave(v, unit, atMs)
                },
            ) { Text(stringResource(confirmRes)) }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    androidx.compose.material3.TextButton(
                        onClick = { deleteConfirmOpen = true },
                        colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) { Text(stringResource(R.string.action_delete)) }
                }
                androidx.compose.material3.TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        },
    )

    if (datePickerOpen) {
        val state = androidx.compose.material3.rememberDatePickerState(initialSelectedDateMillis = atMs)
        androidx.compose.material3.DatePickerDialog(
            onDismissRequest = { datePickerOpen = false },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    state.selectedDateMillis?.let { atMs = it }
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

    if (deleteConfirmOpen && onDelete != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deleteConfirmOpen = false },
            title = { Text(stringResource(R.string.action_delete)) },
            text = { Text(stringResource(R.string.hormones_delete_confirm)) },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = { deleteConfirmOpen = false; onDelete() },
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { deleteConfirmOpen = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun LatestCard(
    latest: DisplayMeasurement,
    prev: DisplayMeasurement?,
    sortedAsc: List<DisplayMeasurement>,
    weight: Boolean = false,
) {
    val color = MaterialTheme.colorScheme.primary
    val dateFmt = remember { SimpleDateFormat("d MMM", Locale.getDefault()) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(
                    if (weight) R.string.weight_last_measure_fmt
                    else R.string.hormones_last_measure_fmt,
                    dateFmt.format(Date(latest.raw.atMs)),
                ).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    formatDouble(latest.displayValue),
                    color = color,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    latest.displayUnit,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (prev != null) {
                    Spacer(Modifier.width(12.dp))
                    val delta = latest.displayValue - prev.displayValue
                    val sign = if (delta >= 0) "+" else ""
                    Text(
                        "$sign${formatDouble(delta)} ${stringResource(R.string.hormones_vs_previous)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (sortedAsc.size >= 2) {
                AreaChart(
                    values = sortedAsc.map { it.displayValue },
                    color = color,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun HistoryCard(
    items: List<DisplayMeasurement>,
    onItemClick: (DisplayMeasurement) -> Unit = {},
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            val dateFmt = remember { SimpleDateFormat("d MMM yy", Locale.getDefault()) }
            items.forEachIndexed { i, m ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onItemClick(m) }
                        .padding(vertical = 13.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.primary),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            dateFmt.format(Date(m.raw.atMs)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (m.displayUnit != m.raw.unit) {
                            // Show the original unit too so the user can audit
                            // the conversion at a glance.
                            Text(
                                "${formatDouble(m.raw.value)} ${m.raw.unit}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            )
                        }
                    }
                    Text(
                        formatDouble(m.displayValue),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        " ${m.displayUnit}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (i < items.lastIndex) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    )
                }
            }
        }
    }
}

@Composable
private fun AreaChart(values: List<Double>, color: Color, modifier: Modifier) {
    val min = values.min()
    val max = values.max()
    val range = (max - min).takeIf { it > 0.0 } ?: 1.0
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val pad = 14f
        val padBottom = 18f
        val linePath = Path()
        val areaPath = Path()
        values.forEachIndexed { i, v ->
            val x = pad + (w - 2 * pad) * (i.toFloat() / (values.size - 1).toFloat())
            val y = pad + (h - pad - padBottom) * (1f - ((v - min) / range).toFloat())
            if (i == 0) {
                linePath.moveTo(x, y)
                areaPath.moveTo(x, h - padBottom)
                areaPath.lineTo(x, y)
            } else {
                linePath.lineTo(x, y)
                areaPath.lineTo(x, y)
            }
        }
        val lastX = pad + (w - 2 * pad)
        areaPath.lineTo(lastX, h - padBottom)
        areaPath.close()

        drawPath(
            path = areaPath,
            brush = Brush.verticalGradient(
                colors = listOf(color.copy(alpha = 0.28f), color.copy(alpha = 0f)),
                startY = 0f,
                endY = h - padBottom,
            ),
        )
        drawPath(linePath, color, style = Stroke(width = 5f, cap = StrokeCap.Round))
        val v = values.last()
        val x = pad + (w - 2 * pad)
        val y = pad + (h - pad - padBottom) * (1f - ((v - min) / range).toFloat())
        drawCircle(color, radius = 8f, center = Offset(x, y))
    }
}

private fun formatDouble(v: Double): String {
    val s = v.toString()
    return if (s.endsWith(".0")) s.dropLast(2) else s
}
