package com.douxev.eggshell.ui.hormones

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
import com.douxev.eggshell.ui.common.ScreenHeader
import com.douxev.eggshell.ui.common.ValueFormat
import com.douxev.eggshell.ui.components.ActionBand
import com.douxev.eggshell.ui.components.CardRule
import com.douxev.eggshell.ui.components.CardVariant
import com.douxev.eggshell.ui.components.EggCard
import com.douxev.eggshell.ui.components.EggFab
import com.douxev.eggshell.ui.components.EmptyState
import com.douxev.eggshell.ui.components.MicroLabel
import com.douxev.eggshell.ui.components.SectionTitle
import com.douxev.eggshell.ui.components.Segmented
import com.douxev.eggshell.ui.theme.EggColors
import com.douxev.eggshell.ui.theme.EggDim
import com.douxev.eggshell.ui.theme.EggShapes
import uniffi.transition.HormoneMeasurement

/** Measurement plus its display-time conversion to the user's preferred unit. */
data class DisplayMeasurement(
    val raw: HormoneMeasurement,
    val displayValue: Double,
    val displayUnit: String,
)

enum class HormonesTab { Hormones, Weight }

/** A dose taken inside the charted window — drawn as a dot on the curve so
 *  intakes and hormone levels correlate visually. Always `tertiary`: §5.1
 *  gives that role to "dose, medication" across every chart of the app. */
data class DoseMarker(val atMs: Long)

@HiltViewModel
class HormonesViewModel @Inject constructor(
    private val repo: HormonesRepository,
    private val units: HormoneUnitPrefs,
    private val meds: com.douxev.eggshell.data.MedicationRepository,
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
    private val _doseMarkers = MutableStateFlow<List<DoseMarker>>(emptyList())
    val doseMarkers: StateFlow<List<DoseMarker>> = _doseMarkers.asStateFlow()

    /** Instants at which a treatment changed, drawn as dashed verticals so a
     *  jump in the curve can be read against the change that caused it. */
    private val _treatmentChanges = MutableStateFlow<List<Long>>(emptyList())
    val treatmentChanges: StateFlow<List<Long>> = _treatmentChanges.asStateFlow()

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
            _doseMarkers.value = emptyList()
            _treatmentChanges.value = emptyList()
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
        loadOverlays(h, raw)
    }

    /** Doses taken inside the charted window (one marker per med per day —
     *  daily treatments would otherwise stack dozens of dots on one spot) plus
     *  the treatment changes of the same window. */
    private suspend fun loadOverlays(hormone: String, raw: List<HormoneMeasurement>) {
        if (hormone == HormoneCatalog.WEIGHT || raw.size < 2) {
            _doseMarkers.value = emptyList()
            _treatmentChanges.value = emptyList()
            return
        }
        val from = raw.minOf { it.atMs }
        val to = raw.maxOf { it.atMs }
        // Bucket by *local* calendar day — UTC buckets would split a 23:00 +
        // 01:30 same-night pair into two markers in UTC+2.
        val zone = java.time.ZoneId.systemDefault()
        _doseMarkers.value = runCatching { meds.listDoseEventsBetween(from, to) }
            .getOrDefault(emptyList())
            .filter { it.status == "taken" }
            .distinctBy {
                it.medicationId to
                    java.time.Instant.ofEpochMilli(it.takenAtMs).atZone(zone).toLocalDate()
            }
            .map { DoseMarker(it.takenAtMs) }
        _treatmentChanges.value = runCatching { meds.listTreatmentChanges(from, to) }
            .getOrDefault(emptyList())
            .map { it.atMs }
            .distinct()
            .sorted()
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HormonesScreen(
    onAdd: () -> Unit,
    onImport: () -> Unit = {},
    onBack: () -> Unit = {},
    /** Which segment to open on — the launcher has a tile per family. */
    initialTab: String = com.douxev.eggshell.ui.home.MeasuresTab.HORMONES,
    vm: HormonesViewModel = hiltViewModel(),
) {
    val hormones by vm.hormones.collectAsState()
    val selected by vm.selected.collectAsState()
    val measurements by vm.measurements.collectAsState()
    val doseMarkers by vm.doseMarkers.collectAsState()
    val treatmentChanges by vm.treatmentChanges.collectAsState()
    val tab by vm.tab.collectAsState()
    val weightTrackingEnabled by vm.weightTrackingEnabled.collectAsState()
    LaunchedEffect(Unit) { vm.refresh() }
    LaunchedEffect(initialTab) {
        if (initialTab == com.douxev.eggshell.ui.home.MeasuresTab.WEIGHT) {
            vm.setTab(HormonesTab.Weight)
        }
    }
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
    val isWeightTab = tab == HormonesTab.Weight
    val startAdd = { if (isWeightTab) addWeightOpen = true else onAdd() }

    Scaffold(
        bottomBar = {
            ActionBand {
                EggFab(
                    icon = Icons.Filled.Add,
                    contentDescription = stringResource(
                        if (isWeightTab) R.string.measures_add_weight
                        else R.string.measures_add_measurement,
                    ),
                    label = stringResource(R.string.measures_add),
                    onClick = startAdd,
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = EggDim.ScreenMargin),
            verticalArrangement = Arrangement.spacedBy(EggDim.BlockGap),
        ) {
            ScreenHeader(
                title = stringResource(R.string.measures_title),
                onBack = onBack,
                actions = {
                    IconButton(
                        onClick = onImport,
                        modifier = Modifier.size(EggDim.TouchTarget),
                    ) {
                        Icon(
                            Icons.Filled.FileUpload,
                            contentDescription = stringResource(R.string.measures_import_action),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )

            // Hormones / Poids. Weight tracking is opt-in, and a one-option
            // segmented control would be furniture — so the track only shows
            // up once there are actually two segments.
            if (weightTrackingEnabled) {
                Segmented(
                    options = listOf(
                        stringResource(R.string.hormones_tab_hormones),
                        stringResource(R.string.hormones_tab_weight),
                    ),
                    selectedIndex = if (isWeightTab) 1 else 0,
                    onSelect = { vm.setTab(if (it == 1) HormonesTab.Weight else HormonesTab.Hormones) },
                )
            }

            // Analyte chips: only in the Hormones tab. Poids has one implicit
            // kind, so a chip row above its chart would say nothing. Eleven
            // analytes exist, hence the horizontal scroll.
            if (!isWeightTab && hormones.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    hormones.forEach { h ->
                        FilterChip(
                            selected = h == selected,
                            onClick = { vm.select(h) },
                            label = { Text(HormoneCatalog.kindLabel(h)) },
                            shape = MeasureChipShape,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            ),
                        )
                    }
                }
            }

            if (measurements.isNotEmpty()) {
                val sorted = remember(measurements) { measurements.sortedBy { it.raw.atMs } }
                CurveCard(
                    sortedAsc = sorted,
                    kindLabel = HormoneCatalog.kindLabel(selected ?: HormoneCatalog.WEIGHT),
                    doseMarkers = if (isWeightTab) emptyList() else doseMarkers,
                    treatmentChanges = if (isWeightTab) emptyList() else treatmentChanges,
                    weight = isWeightTab,
                )
                SectionTitle(stringResource(R.string.measures_readings))
                ReadingsCard(
                    items = remember(measurements) { measurements.sortedByDescending { it.raw.atMs } },
                    onItemClick = { entry -> editing = entry },
                )
            } else if (isWeightTab) {
                EmptyState(
                    message = stringResource(R.string.measures_empty_weight),
                    actionLabel = stringResource(R.string.measures_empty_weight_action),
                    onAction = { addWeightOpen = true },
                )
            } else {
                EmptyState(
                    message = stringResource(R.string.measures_empty_hormones),
                    actionLabel = stringResource(R.string.measures_empty_hormones_action),
                    onAction = onImport,
                )
            }

            Spacer(Modifier.height(12.dp))
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

// ---------------------------------------------------------------------------
// The curve card
// ---------------------------------------------------------------------------

@Composable
private fun CurveCard(
    sortedAsc: List<DisplayMeasurement>,
    kindLabel: String,
    doseMarkers: List<DoseMarker>,
    treatmentChanges: List<Long>,
    weight: Boolean,
) {
    val latest = sortedAsc.last()
    val prev = sortedAsc.dropLast(1).lastOrNull()
    val headerFmt = remember { SimpleDateFormat("d MMMM", Locale.getDefault()) }
    val axisFmt = remember { SimpleDateFormat("MMM yy", Locale.getDefault()) }

    EggCard(variant = CardVariant.Low, padding = PaddingValues(18.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                MicroLabel(
                    stringResource(
                        if (weight) R.string.measures_last_weight_fmt
                        else R.string.measures_last_value_fmt,
                        headerFmt.format(Date(latest.raw.atMs)).uppercase(Locale.getDefault()),
                    ),
                )
                Row(
                    modifier = Modifier.padding(top = 2.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        ValueFormat.significant(latest.displayValue),
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        latest.displayUnit,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
            }
            if (prev != null) DeltaPill(latest.displayValue - prev.displayValue)
        }

        if (sortedAsc.size >= 2) {
            val first = sortedAsc.first().raw.atMs
            val last = latest.raw.atMs
            val chartLabel = stringResource(
                R.string.measures_chart_a11y_fmt,
                kindLabel,
                axisFmt.format(Date(first)),
                axisFmt.format(Date(last)),
                "${ValueFormat.significant(latest.displayValue)} ${latest.displayUnit}",
            )
            MeasureChart(
                points = remember(sortedAsc) {
                    sortedAsc.map { MeasurePoint(atMs = it.raw.atMs, value = it.displayValue) }
                },
                unit = latest.displayUnit,
                doseMarkers = doseMarkers,
                treatmentChanges = treatmentChanges,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                contentDescription = chartLabel,
            )
        } else {
            Text(
                stringResource(R.string.measures_chart_one_point),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

/**
 * The delta pill. A rise is the one direction the handoff colours in
 * `successContainer`; a fall stays neutral, because whether a level going down
 * is good news depends entirely on the analyte and eggshell doesn't judge.
 * The arrow glyph is written as text — `trending_up` is missing from the icon
 * set the design system ships (§3.6).
 */
@Composable
private fun DeltaPill(delta: Double) {
    val rising = delta > 0.0
    val flat = delta == 0.0
    // An unchanged reading is "→ 0", not "→ 0.0000". Padding a difference of
    // exactly nothing out to five figures quotes a precision that has no
    // measurement behind it at all.
    val magnitude = if (flat) "0" else ValueFormat.significant(kotlin.math.abs(delta))
    val label = stringResource(
        when {
            flat -> R.string.measures_delta_flat_fmt
            rising -> R.string.measures_delta_up_fmt
            else -> R.string.measures_delta_down_fmt
        },
        magnitude,
    )
    val a11y = when {
        flat -> stringResource(R.string.measures_delta_flat_a11y)
        rising -> stringResource(R.string.measures_delta_up_a11y_fmt, magnitude)
        else -> stringResource(R.string.measures_delta_down_a11y_fmt, magnitude)
    }
    val container = if (rising) {
        EggColors.successContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val content = if (rising) {
        EggColors.onSuccessContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .background(container, EggShapes.Pill)
            .padding(horizontal = 10.dp, vertical = 5.dp)
            .semantics { contentDescription = a11y },
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = content)
    }
}

// ---------------------------------------------------------------------------
// The readings list
// ---------------------------------------------------------------------------

@Composable
private fun ReadingsCard(
    items: List<DisplayMeasurement>,
    onItemClick: (DisplayMeasurement) -> Unit,
) {
    val dateFmt = remember { SimpleDateFormat("d MMMM yyyy", Locale.getDefault()) }
    val editLabel = stringResource(R.string.measures_reading_edit)
    val manual = stringResource(R.string.measures_reading_manual)

    EggCard(
        variant = CardVariant.Low,
        padding = PaddingValues(horizontal = 18.dp, vertical = 6.dp),
    ) {
        items.forEachIndexed { i, m ->
            val date = dateFmt.format(Date(m.raw.atMs))
            val value = ValueFormat.significant(m.displayValue)
            // The subtitle carries the provenance, and the original unit
            // whenever the display unit differs — so the user can always audit
            // the conversion against what the sheet said.
            val origin = m.raw.labName?.takeIf { it.isNotBlank() } ?: manual
            val original = if (m.displayUnit != m.raw.unit) {
                stringResource(
                    R.string.measures_reading_original_fmt,
                    ValueFormat.significant(m.raw.value),
                    m.raw.unit,
                )
            } else {
                null
            }
            val subtitle = if (original != null) {
                stringResource(R.string.measures_reading_sub_fmt, origin, original)
            } else {
                origin
            }
            val rowLabel = stringResource(
                R.string.measures_reading_a11y_fmt,
                date,
                value,
                m.displayUnit,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = EggDim.TouchTarget)
                    .clickable(onClickLabel = editLabel) { onItemClick(m) }
                    .semantics(mergeDescendants = true) { contentDescription = rowLabel }
                    .padding(vertical = 13.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        date,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    value,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    m.displayUnit,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (i < items.lastIndex) CardRule()
        }
    }
}

// ---------------------------------------------------------------------------
// Add / edit dialogs — behaviour unchanged, restyled only (D6)
// ---------------------------------------------------------------------------

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
        initialValueText = ValueFormat.plain(entry.raw.value),
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
                    shape = EggShapes.Field,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    unitOptions.forEach { u ->
                        FilterChip(
                            selected = u == unit,
                            onClick = { unit = u },
                            label = { Text(u) },
                            shape = MeasureChipShape,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            ),
                        )
                    }
                }
                androidx.compose.material3.OutlinedButton(
                    onClick = { datePickerOpen = true },
                    shape = EggShapes.Pill,
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

