package com.douxev.eggshell.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.douxev.eggshell.R
import com.douxev.eggshell.data.MedicationRepository
import com.douxev.eggshell.data.PlannedDoses
import com.douxev.eggshell.punctuality.DosePoint
import com.douxev.eggshell.punctuality.exactLabel
import com.douxev.eggshell.punctuality.punctualityStats
import com.douxev.eggshell.ui.common.ScreenHeader
import com.douxev.eggshell.ui.components.ActionBand
import com.douxev.eggshell.ui.components.CardRule
import com.douxev.eggshell.ui.components.CardVariant
import com.douxev.eggshell.ui.components.EggCard
import com.douxev.eggshell.ui.components.EggFab
import com.douxev.eggshell.ui.components.EmptyState
import com.douxev.eggshell.ui.components.IconTile
import com.douxev.eggshell.ui.components.ListRow
import com.douxev.eggshell.ui.components.MicroLabel
import com.douxev.eggshell.ui.components.PunctualityChart
import com.douxev.eggshell.ui.components.SkeletonBlock
import com.douxev.eggshell.ui.medication.MedicationCatalog
import com.douxev.eggshell.ui.medication.deltaLabelText
import com.douxev.eggshell.ui.medication.formatDoseWithUnit
import com.douxev.eggshell.ui.medication.rememberChartLabels
import com.douxev.eggshell.ui.theme.EggDim
import com.douxev.eggshell.ui.theme.EggShapes
import uniffi.transition.Medication

/**
 * Médics — the treatment list (handoff §6.4).
 *
 * Every punctuality figure on this screen comes from [PlannedDoses.window]: the
 * list subtitles, the three headline numbers and the chart are three views of
 * the same pairing, so they can never contradict each other. When the window
 * holds no planned time at all the card shows the empty state rather than a
 * misleading `0 %` — the figures end up in a document handed to a doctor.
 */
@HiltViewModel
class MedicationListViewModel @Inject constructor(
    private val repo: MedicationRepository,
    private val plannedDoses: PlannedDoses,
) : ViewModel() {

    enum class Filter { Active, All, Archived }

    /** One treatment, with its own regularity over the window. */
    data class Row(
        val med: Medication,
        /** Null when this treatment planned nothing over the window (D2). */
        val adherencePercent: Int?,
        val meanDelayMin: Int?,
    )

    /** Which sentence the card ends on. */
    enum class Insight { Good, Late, Missed, Both }

    /** The action the sentence proposes. It is always about a real schedule. */
    sealed interface Advice {
        data object None : Advice
        /** Move the reminder that is chronically answered late. */
        data class Shift(
            val medicationId: Long,
            val medicationName: String,
            val hour: Int,
            val minute: Int,
        ) : Advice
        /** Make the reminder that keeps being missed harder to walk past. */
        data class Prioritize(val medicationId: Long, val medicationName: String) : Advice
    }

    data class Regularity(
        val adherencePercent: Int,
        val meanDelayMin: Int,
        val missedCount: Int,
        val lateCount: Int,
        val onTimeCount: Int,
        val plannedCount: Int,
        val points: List<DosePoint>,
        val insight: Insight,
        val advice: Advice,
    )

    data class State(
        val loading: Boolean = true,
        val filter: Filter = Filter.Active,
        val rows: List<Row> = emptyList(),
        val hasAnyMedication: Boolean = false,
        /** Null = nothing to compare over the window; show the empty state. */
        val regularity: Regularity? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init { refresh() }

    fun setFilter(filter: Filter) {
        if (filter == _state.value.filter) return
        _state.value = _state.value.copy(filter = filter)
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val filter = _state.value.filter
            val all = runCatching { repo.list(includeArchived = true) }.getOrDefault(emptyList())
            val visible = when (filter) {
                Filter.Active -> all.filterNot { it.archived }
                Filter.All -> all
                Filter.Archived -> all.filter { it.archived }
            }

            val now = System.currentTimeMillis()
            val from = now - WINDOW_MS
            val window = runCatching { plannedDoses.window(fromMs = from, toMs = now) }.getOrNull()
            val occurrences = window?.occurrences.orEmpty()

            val rows = visible.map { med ->
                val own = occurrences.filter { it.medicationId == med.id }
                // Same function as the card's headline figures, so a row can
                // never round differently from the card above it.
                val stats = punctualityStats(
                    plannedCount = own.size,
                    points = own.map {
                        DosePoint(
                            atMs = it.event?.takenAtMs ?: it.plannedAtMs,
                            deltaMin = it.deltaMin,
                        )
                    },
                )
                Row(
                    med = med,
                    adherencePercent = if (own.isEmpty()) null else stats.adherencePercent,
                    meanDelayMin = if (own.none { it.deltaMin != null }) null else stats.meanDelayMin,
                )
            }

            _state.value = State(
                loading = false,
                filter = filter,
                rows = rows,
                hasAnyMedication = all.isNotEmpty(),
                regularity = window?.takeIf { it.occurrences.isNotEmpty() }?.let { w ->
                    regularityOf(w, all)
                },
            )
        }
    }

    private fun regularityOf(
        window: PlannedDoses.Window,
        meds: List<Medication>,
    ): Regularity {
        val stats = window.stats
        val tolerance = MedicationCatalog.ON_TIME_TOLERANCE_MIN
        val late = window.occurrences.filter { (it.deltaMin ?: 0) > tolerance }
        val missed = window.occurrences.filter { it.event == null }
        val planned = window.occurrences.size

        // Two thresholds rather than "any late dose at all": a single late
        // evening in a month is not a pattern, and calling it one would train
        // the user to ignore the sentence.
        val lateHeavy = late.size * 5 >= planned
        val missedHeavy = missed.size * 10 >= planned && missed.isNotEmpty()
        val insight = when {
            lateHeavy && missedHeavy -> Insight.Both
            lateHeavy -> Insight.Late
            missedHeavy -> Insight.Missed
            else -> Insight.Good
        }

        val byName = meds.associateBy({ it.id }, { it.name })
        val advice = when (insight) {
            Insight.Good -> Advice.None
            Insight.Late, Insight.Both -> shiftAdvice(late, byName)
            Insight.Missed -> missed.groupingBy { it.medicationId }.eachCount()
                .maxByOrNull { it.value }
                ?.key
                ?.let { id -> byName[id]?.let { Advice.Prioritize(id, it) } }
                ?: Advice.None
        }

        return Regularity(
            adherencePercent = stats.adherencePercent,
            meanDelayMin = stats.meanDelayMin,
            missedCount = missed.size,
            lateCount = late.size,
            onTimeCount = planned - missed.size - late.size,
            plannedCount = planned,
            points = window.points,
            insight = insight,
            advice = advice,
        )
    }

    /**
     * "Move the reminder to …": take the treatment that runs late most often,
     * the time of day it runs late at, and slide that time by the median of its
     * own delays. The median, not the mean — one forgotten evening at 3 a.m.
     * must not drag the proposal into the night.
     */
    private fun shiftAdvice(
        late: List<PlannedDoses.Occurrence>,
        byName: Map<Long, String>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Advice {
        val worst = late.groupingBy { it.medicationId }.eachCount().maxByOrNull { it.value }?.key
            ?: return Advice.None
        val name = byName[worst] ?: return Advice.None
        val own = late.filter { it.medicationId == worst }
        val slot = own
            .groupingBy { Instant.ofEpochMilli(it.plannedAtMs).atZone(zone).toLocalTime().withSecond(0).withNano(0) }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
            ?: return Advice.None
        val deltas = own.mapNotNull { it.deltaMin }.sorted()
        if (deltas.isEmpty()) return Advice.None
        val median = deltas[deltas.size / 2]
        // Round to five minutes: a reminder at 21:37 reads like a machine wrote it.
        val shifted = slot.plusMinutes((median / 5L) * 5L)
        return Advice.Shift(worst, name, shifted.hour, shifted.minute)
    }

    private companion object {
        /** The card's period, and the only window the list ever reads. */
        const val WINDOW_MS = 30L * 24L * 60L * 60L * 1000L
    }
}

@Composable
fun MedicationListScreen(
    onAddMedication: () -> Unit,
    onOpenMedication: (Long) -> Unit,
    onBack: () -> Unit = {},
    vm: MedicationListViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { vm.refresh() }

    val needle = query.trim()
    val rows = if (needle.isEmpty()) state.rows else {
        state.rows.filter { it.med.name.contains(needle, ignoreCase = true) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        bottomBar = {
            ActionBand {
                EggFab(
                    icon = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.meds_add_treatment),
                    onClick = onAddMedication,
                )
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(
                start = EggDim.ScreenMargin,
                end = EggDim.ScreenMargin,
                top = 4.dp,
                bottom = EggDim.BlockGap,
            ),
            verticalArrangement = Arrangement.spacedBy(EggDim.BlockGap),
        ) {
            item {
                ScreenHeader(
                    title = stringResource(R.string.med_list_title),
                    onBack = onBack,
                    actions = {
                        IconButton(
                            onClick = {
                                searching = !searching
                                if (!searching) query = ""
                            },
                            modifier = Modifier.size(EggDim.TouchTarget),
                        ) {
                            Icon(
                                if (searching) Icons.Filled.Close else Icons.Filled.Search,
                                contentDescription = stringResource(
                                    if (searching) R.string.meds_search_close else R.string.meds_search
                                ),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                )
            }

            if (searching) {
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text(stringResource(R.string.meds_search_hint)) },
                        singleLine = true,
                        shape = EggShapes.Field,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            item {
                FilterRow(selected = state.filter, onSelect = vm::setFilter)
            }

            when {
                state.loading -> items(SKELETON_ROWS) { SkeletonBlock(height = 76.dp) }

                rows.isEmpty() -> item {
                    when {
                        needle.isNotEmpty() -> EmptyState(
                            message = stringResource(R.string.meds_empty_search_fmt, needle),
                        )
                        state.filter == MedicationListViewModel.Filter.Archived -> EmptyState(
                            message = stringResource(R.string.meds_empty_archived),
                        )
                        else -> EmptyState(
                            message = stringResource(R.string.med_list_empty),
                            actionLabel = stringResource(R.string.meds_add_treatment),
                            onAction = onAddMedication,
                        )
                    }
                }

                else -> items(rows, key = { it.med.id }) { row ->
                    MedicationRow(row = row, onClick = { onOpenMedication(row.med.id) })
                }
            }

            if (state.hasAnyMedication && !state.loading) {
                item { RegularityHeader() }
                item {
                    val regularity = state.regularity
                    if (regularity == null) {
                        // D2: no planned time over the period means nothing to
                        // compare — never a fabricated 0 %.
                        EmptyState(message = stringResource(R.string.meds_regularity_empty))
                    } else {
                        RegularityCard(regularity = regularity, onOpenMedication = onOpenMedication)
                    }
                }
            }
        }
    }
}

/**
 * « Régularité » with its period on the right. The period is a statement, not
 * an action — the card only ever covers 30 days — so it is not a tap target.
 */
@Composable
private fun RegularityHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.meds_regularity),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        MicroLabel(stringResource(R.string.meds_regularity_period))
    }
}

/** Actifs / Tous / Archivés — filter chips, radius 10 (D4). */
@Composable
private fun FilterRow(
    selected: MedicationListViewModel.Filter,
    onSelect: (MedicationListViewModel.Filter) -> Unit,
) {
    val options = listOf(
        MedicationListViewModel.Filter.Active to R.string.meds_filter_active,
        MedicationListViewModel.Filter.All to R.string.meds_filter_all,
        MedicationListViewModel.Filter.Archived to R.string.meds_filter_archived,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (filter, labelRes) ->
            FilterChip(
                selected = filter == selected,
                onClick = { onSelect(filter) },
                label = { Text(stringResource(labelRes)) },
                shape = FilterChipShape,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        }
    }
}

@Composable
private fun MedicationRow(row: MedicationListViewModel.Row, onClick: () -> Unit) {
    val med = row.med
    // A per-treatment colour is a user choice, so it wins over the default
    // tile; the glyph then carries the same hue so the pair stays readable.
    val accent: Color? = med.color?.let { Color(it.toInt()) }
    val tile = accent?.copy(alpha = 0.18f) ?: MaterialTheme.colorScheme.primaryContainer
    val glyph = accent ?: MaterialTheme.colorScheme.onPrimaryContainer

    val parts = buildList {
        formatDoseWithUnit(med.defaultDose, med.defaultDoseUnit)?.let(::add)
        add(stringResource(MedicationCatalog.routeLabelRes(med.route)))
        row.adherencePercent?.let { add(stringResource(R.string.meds_adherence_fmt, it)) }
        row.meanDelayMin?.let { mean ->
            val label = deltaLabelText(exactLabel(mean, MedicationCatalog.ON_TIME_TOLERANCE_MIN))
            // Within tolerance the segment just reads « à l'heure »: "+0 min en
            // moyenne" would be noise dressed up as a measurement.
            add(
                if (mean <= MedicationCatalog.ON_TIME_TOLERANCE_MIN) label
                else stringResource(R.string.meds_mean_delay_fmt, label)
            )
        }
        if (med.archived) add(stringResource(R.string.meds_archived_badge))
    }

    ListRow(
        title = med.name,
        subtitle = parts.joinToString(MedicationCatalog.SEP),
        badge = stringResource(MedicationCatalog.kindLabelRes(med.kind)),
        leading = {
            IconTile(size = 48.dp, shape = EggShapes.SmallTile, container = tile) {
                Icon(
                    MedicationCatalog.routeIcon(med.route),
                    contentDescription = null,
                    tint = glyph,
                )
            }
        },
        onClick = onClick,
    )
}

/** « Régularité · 30 jours » — three figures, the chart, and a sentence. */
@Composable
private fun RegularityCard(
    regularity: MedicationListViewModel.Regularity,
    onOpenMedication: (Long) -> Unit,
) {
    val meanLabel = deltaLabelText(
        exactLabel(regularity.meanDelayMin, MedicationCatalog.ON_TIME_TOLERANCE_MIN)
    )
    EggCard(variant = CardVariant.Low, padding = PaddingValues(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatCell(
                value = stringResource(R.string.meds_adherence_fmt, regularity.adherencePercent),
                label = stringResource(R.string.meds_stat_logged),
                modifier = Modifier.weight(1f),
            )
            StatDivider()
            StatCell(
                value = meanLabel,
                label = stringResource(R.string.meds_stat_mean_delay),
                modifier = Modifier.weight(1f),
            )
            StatDivider()
            StatCell(
                value = regularity.missedCount.toString(),
                label = stringResource(R.string.meds_stat_missed),
                valueColor = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f),
            )
        }

        MicroLabel(
            stringResource(R.string.meds_chart_caption),
            modifier = Modifier.padding(top = 18.dp),
        )
        val chartCd = stringResource(R.string.meds_chart_cd)
        val labels = rememberChartLabels()
        PunctualityChart(
            points = regularity.points,
            labelFor = labels.delta,
            missedLabel = labels.missed,
            onTimeToleranceMin = MedicationCatalog.ON_TIME_TOLERANCE_MIN,
            modifier = Modifier
                .padding(top = 4.dp)
                .height(96.dp)
                .semantics { contentDescription = chartCd },
        )

        CardRule(modifier = Modifier.padding(top = 14.dp))
        Text(
            insightSentence(regularity),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
        AdviceLine(advice = regularity.advice, onOpenMedication = onOpenMedication)
    }
}

/**
 * The chart's text alternative (§10): what the figures mean, in one sentence,
 * computed from the window — never the prototype's demonstration copy.
 */
@Composable
private fun insightSentence(r: MedicationListViewModel.Regularity): String = when (r.insight) {
    MedicationListViewModel.Insight.Good ->
        stringResource(R.string.meds_insight_good_fmt, r.onTimeCount, r.plannedCount)
    MedicationListViewModel.Insight.Late ->
        stringResource(R.string.meds_insight_late_fmt, r.lateCount, r.plannedCount)
    MedicationListViewModel.Insight.Missed ->
        stringResource(R.string.meds_insight_missed_fmt, r.missedCount, r.plannedCount)
    MedicationListViewModel.Insight.Both ->
        stringResource(R.string.meds_insight_both_fmt, r.missedCount, r.lateCount, r.plannedCount)
}

@Composable
private fun AdviceLine(
    advice: MedicationListViewModel.Advice,
    onOpenMedication: (Long) -> Unit,
) {
    when (advice) {
        MedicationListViewModel.Advice.None -> Text(
            stringResource(R.string.meds_action_none),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        is MedicationListViewModel.Advice.Shift -> {
            val at = remember(advice) {
                DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
                    .withLocale(Locale.getDefault())
                    .format(LocalTime.of(advice.hour, advice.minute))
            }
            TextButton(onClick = { onOpenMedication(advice.medicationId) }) {
                Text(stringResource(R.string.meds_action_shift_fmt, advice.medicationName, at))
            }
        }
        is MedicationListViewModel.Advice.Prioritize -> TextButton(
            onClick = { onOpenMedication(advice.medicationId) },
        ) {
            Text(stringResource(R.string.meds_action_priority_fmt, advice.medicationName))
        }
    }
}

@Composable
private fun StatCell(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(modifier = modifier) {
        Text(
            value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = valueColor,
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatDivider() {
    Box(
        modifier = Modifier
            .padding(horizontal = 7.dp)
            .width(1.dp)
            .height(34.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

/** D4: a filter chip is a different species from a period pill — radius 10. */
private val FilterChipShape = RoundedCornerShape(10.dp)
private const val SKELETON_ROWS = 3
