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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.douxev.eggshell.R
import com.douxev.eggshell.data.BleedingRepository
import com.douxev.eggshell.data.FeaturesPrefs
import com.douxev.eggshell.data.JournalRepository
import com.douxev.eggshell.data.MedicationRepository
import com.douxev.eggshell.data.MetricsRepository
import com.douxev.eggshell.ui.bleeding.BleedingListViewModel
import com.douxev.eggshell.ui.bleeding.bleedingSegment
import com.douxev.eggshell.ui.common.ScreenHeader
import com.douxev.eggshell.ui.common.metricAccent
import com.douxev.eggshell.ui.correlation.CorrelationViewModel
import com.douxev.eggshell.ui.correlation.correlationSegment
import com.douxev.eggshell.ui.components.ActionBand
import com.douxev.eggshell.ui.components.CardVariant
import com.douxev.eggshell.ui.components.EggCard
import com.douxev.eggshell.ui.components.EggFab
import com.douxev.eggshell.ui.components.EmptyState
import com.douxev.eggshell.ui.components.IconTile
import com.douxev.eggshell.ui.components.ListRow
import com.douxev.eggshell.ui.components.MicroLabel
import com.douxev.eggshell.ui.components.SectionTitle
import com.douxev.eggshell.ui.components.Segmented
import com.douxev.eggshell.ui.components.SkeletonBlock
import com.douxev.eggshell.ui.theme.EggDim
import com.douxev.eggshell.ui.theme.EggShapes
import uniffi.transition.JournalEntry
import uniffi.transition.MetricDefinition

@HiltViewModel
class JournalListViewModel @Inject constructor(
    private val repo: JournalRepository,
    private val meds: MedicationRepository,
    private val bleedingRepo: BleedingRepository,
    private val metrics: MetricsRepository,
    private val features: FeaturesPrefs,
) : ViewModel() {
    /**
     * Menstruations is opt-in: bleeding is a strong signal about someone's
     * body, so the segment, its FAB and the calendar's band only exist once
     * the module has been enabled. Exposed as the live flag so flipping it in Réglages is
     * reflected the next time this screen is shown.
     */
    val bleedingEnabled: StateFlow<Boolean> = features.bleeding

    private val _items = MutableStateFlow<List<JournalEntry>>(emptyList())
    val items: StateFlow<List<JournalEntry>> = _items.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _selectedDate = MutableStateFlow<LocalDate?>(null)
    val selectedDate: StateFlow<LocalDate?> = _selectedDate.asStateFlow()

    /**
     * The four bars of a history card: the first four **enabled** indicators in
     * catalog order, each in its own §6.2 accent — so the little chart follows
     * whatever the user chose to track (D3).
     */
    private val _barDefs = MutableStateFlow<List<MetricDefinition>>(emptyList())
    val barDefs: StateFlow<List<MetricDefinition>> = _barDefs.asStateFlow()

    /** Custom-slider values per entry, only fetched when a bar needs one. */
    private val _barValues = MutableStateFlow<Map<Long, Map<Long, UInt>>>(emptyMap())
    val barValues: StateFlow<Map<Long, Map<Long, UInt>>> = _barValues.asStateFlow()

    /** Calendar overlays: bleeding days (continuous band) + medication days
     *  (per-med colored dots), so dose↔mood correlations show at a glance. */
    data class Overlays(
        val bleedingDays: Set<LocalDate> = emptySet(),
        val medsByDay: Map<LocalDate, List<Long>> = emptyMap(),
        val medColors: Map<Long, Long> = emptyMap(),
        val medNames: Map<Long, String> = emptyMap(),
    )

    private val _overlays = MutableStateFlow(Overlays())
    val overlays: StateFlow<Overlays> = _overlays.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            val entries = runCatching { repo.list(0, 200) }.getOrDefault(emptyList())
                .sortedByDescending { it.atMs }
            _items.value = entries
            _loading.value = false

            val defs = runCatching { metrics.definitions(MetricsRepository.DOMAIN_JOURNAL) }
                .getOrDefault(emptyList())
                .filter { it.enabled }
                .take(4)
            _barDefs.value = defs

            // Column-backed bars read straight off the entry; only a custom bar
            // costs one lookup per entry, and then only for what is on screen.
            if (defs.any { it.columnName == null }) {
                val ids = defs.filter { it.columnName == null }.map { it.id }.toSet()
                val loaded = LinkedHashMap<Long, Map<Long, UInt>>()
                entries.take(BAR_VALUE_LOOKAHEAD).forEach { entry ->
                    val values = runCatching {
                        metrics.values(MetricsRepository.DOMAIN_JOURNAL, entry.id)
                    }.getOrDefault(emptyList())
                    loaded[entry.id] = values
                        .filter { it.metricId in ids }
                        .associate { it.metricId to it.value }
                }
                _barValues.value = loaded
            } else {
                _barValues.value = emptyMap()
            }
        }
    }

    /** (Re)load the overlay data for the month the calendar is showing. */
    fun loadOverlays(month: YearMonth) {
        viewModelScope.launch {
            val zone = ZoneId.systemDefault()
            val from = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val to = month.atEndOfMonth().plusDays(1).atStartOfDay(zone)
                .toInstant().toEpochMilli() - 1
            val doses = runCatching { meds.listDoseEventsBetween(from, to) }
                .getOrDefault(emptyList())
                .filter { it.status == "taken" }
            val medList = runCatching { meds.list(includeArchived = true) }
                .getOrDefault(emptyList())
            // Bleeding entries are newest-first; one page comfortably covers
            // years of cycle history. Nothing is read at all while the module
            // is off, so the calendar can never draw an unasked-for band.
            val bleeding = if (!features.bleeding.value) emptyList() else {
                runCatching { bleedingRepo.list(0, 1000) }.getOrDefault(emptyList())
            }
            _overlays.value = Overlays(
                bleedingDays = bleeding
                    .map { Instant.ofEpochMilli(it.atMs).atZone(zone).toLocalDate() }
                    .toSet(),
                medsByDay = doses
                    .groupBy(
                        { Instant.ofEpochMilli(it.takenAtMs).atZone(zone).toLocalDate() },
                        { it.medicationId },
                    )
                    .mapValues { (_, ids) -> ids.distinct() },
                medColors = medList.mapNotNull { m -> m.color?.let { m.id to it } }.toMap(),
                medNames = medList.associate { it.id to it.name },
            )
        }
    }

    fun selectDate(date: LocalDate?) {
        _selectedDate.value = date
    }

    private companion object {
        /** Enough history for the list the user will actually scroll through. */
        const val BAR_VALUE_LOOKAHEAD = 60
    }
}

/**
 * Ressenti (§6.7) — one screen, three segments.
 *
 * `Journal` holds the month calendar and the history it filters, `Menstruations`
 * and `Corrélations` show exactly what their own screens show: the launcher tile
 * and the segment must never disagree about what a module contains.
 *
 * The three `…Bleeding` callbacks default to [onOpenBleeding] so a host that
 * only wires the tile still lands the user on working screens.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun JournalListScreen(
    onAdd: () -> Unit,
    onEdit: (Long) -> Unit,
    onBack: () -> Unit = {},
    // Kept for the host's call site: correlations are a segment now, not a push.
    onOpenCorrelation: () -> Unit = {},
    onOpenBleeding: () -> Unit = {},
    onOpenSummary: () -> Unit = {},
    onAddBleeding: () -> Unit = onOpenBleeding,
    onEditBleeding: (Long) -> Unit = { onOpenBleeding() },
    onCustomizeBleeding: () -> Unit = onOpenBleeding,
    vm: JournalListViewModel = hiltViewModel(),
    bleedingVm: BleedingListViewModel = hiltViewModel(),
    correlationVm: CorrelationViewModel = hiltViewModel(),
) {
    val items by vm.items.collectAsState()
    val loading by vm.loading.collectAsState()
    val selectedDate by vm.selectedDate.collectAsState()
    val overlays by vm.overlays.collectAsState()
    val barDefs by vm.barDefs.collectAsState()
    val barValues by vm.barValues.collectAsState()
    val bleedingItems by bleedingVm.items.collectAsState()
    val bleedingLoading by bleedingVm.loading.collectAsState()
    val correlationState by correlationVm.state.collectAsState()

    val bleedingEnabled by vm.bleedingEnabled.collectAsState()

    // What is remembered is the segment's *identity*, never its position: the
    // list shrinks when the module is switched off, and a stored index would
    // then designate the wrong tab — or one past the end.
    val segments = remember(bleedingEnabled) {
        if (bleedingEnabled) {
            listOf(SEGMENT_JOURNAL, SEGMENT_BLEEDING, SEGMENT_CORRELATIONS)
        } else {
            listOf(SEGMENT_JOURNAL, SEGMENT_CORRELATIONS)
        }
    }
    var requestedSegment by rememberSaveable { mutableIntStateOf(SEGMENT_JOURNAL) }
    val segment = if (requestedSegment in segments) requestedSegment else SEGMENT_JOURNAL
    val segmentIndex = segments.indexOf(segment).coerceAtLeast(0)

    LaunchedEffect(bleedingEnabled) {
        vm.refresh()
        if (bleedingEnabled) bleedingVm.refresh()
    }
    LaunchedEffect(segment) { if (segment == SEGMENT_CORRELATIONS) correlationVm.reload() }

    val zone = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now(zone) }
    var visibleMonth by remember { mutableStateOf(YearMonth.from(today)) }
    // Keyed on the flag too: turning Menstruations off while the calendar is
    // on screen must drop the band, not leave it drawn until the next month
    // change.
    LaunchedEffect(visibleMonth, bleedingEnabled) { vm.loadOverlays(visibleMonth) }

    val byDate: Map<LocalDate, List<JournalEntry>> = remember(items) {
        items.groupBy { Instant.ofEpochMilli(it.atMs).atZone(zone).toLocalDate() }
    }
    val moodByDay: Map<LocalDate, Double> = remember(byDate) {
        byDate.mapNotNull { (day, entries) ->
            entries.mapNotNull { it.mood?.toInt() }.takeIf { it.isNotEmpty() }
                ?.average()?.let { day to it }
        }.toMap()
    }
    val visibleEntries = remember(items, selectedDate, byDate) {
        if (selectedDate == null) items
        else byDate[selectedDate].orEmpty().sortedByDescending { it.atMs }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        // The band is reserved, not floating — the last history card stays
        // readable under it.
        bottomBar = {
            when (segment) {
                SEGMENT_JOURNAL -> ActionBand {
                    EggFab(
                        icon = Icons.Filled.Add,
                        contentDescription = stringResource(R.string.feel_fab_journal),
                        onClick = onAdd,
                    )
                }
                SEGMENT_BLEEDING -> ActionBand {
                    EggFab(
                        icon = Icons.Filled.Add,
                        contentDescription = stringResource(R.string.feel_fab_bleeding),
                        onClick = onAddBleeding,
                    )
                }
                else -> Unit
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
                bottom = if (segment == SEGMENT_CORRELATIONS) 24.dp else 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "feel-header") {
                ScreenHeader(title = stringResource(R.string.feel_title), onBack = onBack)
            }
            item(key = "feel-segments") {
                Segmented(
                    options = segments.map { id ->
                        stringResource(
                            when (id) {
                                SEGMENT_BLEEDING -> R.string.feel_seg_bleeding
                                SEGMENT_CORRELATIONS -> R.string.feel_seg_correlations
                                else -> R.string.feel_seg_journal
                            },
                        )
                    },
                    selectedIndex = segmentIndex,
                    onSelect = { requestedSegment = segments[it] },
                )
            }

            when (segment) {
                SEGMENT_JOURNAL -> {
                    item(key = "feel-summary") {
                        ListRow(
                            title = stringResource(R.string.summary_title),
                            subtitle = stringResource(R.string.feel_summary_subtitle),
                            leading = {
                                IconTile(
                                    container = MaterialTheme.colorScheme.primaryContainer,
                                ) {
                                    Icon(
                                        Icons.Filled.Timeline,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(22.dp),
                                    )
                                }
                            },
                            onClick = onOpenSummary,
                        )
                    }
                    item(key = "feel-calendar") {
                        MonthCalendarCard(
                            yearMonth = visibleMonth,
                            today = today,
                            selected = selectedDate,
                            moodByDay = moodByDay,
                            overlays = overlays,
                            onPrevMonth = { visibleMonth = visibleMonth.minusMonths(1) },
                            onNextMonth = { visibleMonth = visibleMonth.plusMonths(1) },
                            onSelect = { date ->
                                vm.selectDate(if (date == selectedDate) null else date)
                            },
                        )
                    }
                    item(key = "feel-history-title") {
                        val fmt = remember { SimpleDateFormat("EEEE d MMMM", Locale.getDefault()) }
                        SectionTitle(
                            text = selectedDate?.let { day ->
                                fmt.format(Date.from(day.atStartOfDay(zone).toInstant()))
                                    .replaceFirstChar { it.titlecase(Locale.getDefault()) }
                            } ?: stringResource(R.string.journal_history),
                            action = stringResource(R.string.correlation_title),
                            onAction = { requestedSegment = SEGMENT_CORRELATIONS },
                        )
                    }
                    when {
                        loading && items.isEmpty() -> item(key = "feel-skeleton") {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                repeat(3) { SkeletonBlock(height = 92.dp) }
                            }
                        }
                        visibleEntries.isEmpty() -> item(key = "feel-empty") {
                            EmptyState(
                                message = if (selectedDate == null) {
                                    stringResource(R.string.feel_empty_journal)
                                } else {
                                    stringResource(R.string.journal_empty_for_date)
                                },
                                actionLabel = stringResource(R.string.feel_fab_journal),
                                onAction = onAdd,
                            )
                        }
                        else -> items(visibleEntries, key = { "journal-${it.id}" }) { entry ->
                            EntryCard(
                                entry = entry,
                                barDefs = barDefs,
                                customValues = barValues[entry.id].orEmpty(),
                                today = today,
                                zone = zone,
                                onClick = { onEdit(entry.id) },
                            )
                        }
                    }
                }

                SEGMENT_BLEEDING -> bleedingSegment(
                    items = bleedingItems,
                    loading = bleedingLoading,
                    onAdd = onAddBleeding,
                    onEdit = onEditBleeding,
                    onCustomize = onCustomizeBleeding,
                )

                else -> correlationSegment(
                    state = correlationState,
                    onWindow = correlationVm::load,
                    onAddEntry = onAdd,
                )
            }
        }
    }
}

private const val SEGMENT_JOURNAL = 0
private const val SEGMENT_BLEEDING = 1
private const val SEGMENT_CORRELATIONS = 2

// ---------------------------------------------------------------- calendar --

/** Cell height from the prototype. The cell is wider than it is tall. */
private val CellHeight = 34.dp
private val DiscSize = 28.dp

/**
 * The month calendar of §6.7.
 *
 * Always **six rows / 42 cells**: a 31-day month starting on a Saturday needs
 * them, and a grid that changes height between months makes the page jump.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MonthCalendarCard(
    yearMonth: YearMonth,
    today: LocalDate,
    selected: LocalDate?,
    moodByDay: Map<LocalDate, Double>,
    overlays: JournalListViewModel.Overlays,
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onSelect: (LocalDate) -> Unit,
) {
    val locale = Locale.getDefault()
    val zone = remember { ZoneId.systemDefault() }
    val monthLabel = remember(yearMonth, locale) {
        SimpleDateFormat("LLLL yyyy", locale)
            .format(Date.from(yearMonth.atDay(1).atStartOfDay(zone).toInstant()))
            .replaceFirstChar { it.titlecase(locale) }
    }
    val firstDayOfWeek = remember(locale) { WeekFields.of(locale).firstDayOfWeek }
    val weekdayLabels = remember(firstDayOfWeek, locale) {
        (0..6).map { i ->
            DayOfWeek.of(((firstDayOfWeek.value - 1 + i) % 7) + 1)
                .getDisplayName(TextStyle.NARROW, locale)
                .uppercase(locale)
        }
    }
    val firstOfMonth = yearMonth.atDay(1)
    val leadingBlanks = ((firstOfMonth.dayOfWeek.value - firstDayOfWeek.value) + 7) % 7
    val daysInMonth = yearMonth.lengthOfMonth()

    val monthDays = remember(yearMonth) { (1..daysInMonth).map { yearMonth.atDay(it) } }
    val monthHasMood = monthDays.any { it in moodByDay }
    val monthHasBleeding = monthDays.any { it in overlays.bleedingDays }
    val monthMedIds = monthDays.flatMap { overlays.medsByDay[it].orEmpty() }.distinct()

    EggCard(variant = CardVariant.Low, padding = PaddingValues(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPrevMonth, modifier = Modifier.size(EggDim.TouchTarget)) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = stringResource(R.string.journal_prev_month),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                monthLabel,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onNextMonth, modifier = Modifier.size(EggDim.TouchTarget)) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = stringResource(R.string.journal_next_month),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
            weekdayLabels.forEach { label ->
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.70f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // Six rows, always. Columns touch each other so a run of bleeding days
        // welds into one continuous bar instead of a dotted line of pills.
        for (row in 0 until 6) {
            Row(modifier = Modifier.fillMaxWidth()) {
                for (col in 0..6) {
                    val dayNumber = row * 7 + col - leadingBlanks + 1
                    if (dayNumber !in 1..daysInMonth) {
                        Box(modifier = Modifier.weight(1f).height(CellHeight))
                        continue
                    }
                    val date = yearMonth.atDay(dayNumber)
                    val bleeding = date in overlays.bleedingDays
                    DayCell(
                        modifier = Modifier.weight(1f),
                        date = date,
                        isToday = date == today,
                        isSelected = date == selected,
                        mood = moodByDay[date],
                        bleeding = bleeding,
                        bleedStartsRun = bleeding && date.minusDays(1) !in overlays.bleedingDays,
                        bleedEndsRun = bleeding && date.plusDays(1) !in overlays.bleedingDays,
                        medIds = overlays.medsByDay[date].orEmpty(),
                        medColors = overlays.medColors,
                        onClick = { onSelect(date) },
                    )
                }
            }
        }

        if (monthHasMood || monthHasBleeding || monthMedIds.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) {
                if (monthHasMood) {
                    LegendItem(
                        color = MaterialTheme.colorScheme.primary,
                        label = stringResource(R.string.feel_calendar_legend_mood),
                    )
                }
                monthMedIds.forEach { medId ->
                    LegendItem(
                        color = overlays.medColors[medId]?.let { Color(it.toInt()) }
                            ?: MaterialTheme.colorScheme.tertiary,
                        label = overlays.medNames[medId].orEmpty(),
                    )
                }
                if (monthHasBleeding) {
                    LegendItem(
                        color = MaterialTheme.colorScheme.errorContainer,
                        label = stringResource(R.string.journal_legend_bleeding),
                        band = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String, band: Boolean = false) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(width = if (band) 16.dp else 9.dp, height = if (band) 8.dp else 9.dp)
                .clip(EggShapes.Pill)
                .background(color),
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One day.
 *
 * Layers, bottom to top: the period band, the mood disc (whose opacity follows
 * the day's average mood), the selection ring, the number, the medication dots.
 * Today wins over the tint with a solid disc; its dots are redrawn in
 * `onPrimary` so nothing is lost on the most-looked-at cell of the month.
 */
@Composable
private fun DayCell(
    modifier: Modifier,
    date: LocalDate,
    isToday: Boolean,
    isSelected: Boolean,
    mood: Double?,
    bleeding: Boolean,
    bleedStartsRun: Boolean,
    bleedEndsRun: Boolean,
    medIds: List<Long>,
    medColors: Map<Long, Long>,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val dotFallback = if (isToday) scheme.onPrimary else scheme.tertiary
    val dots = medIds.take(3).map { id ->
        if (isToday) scheme.onPrimary else medColors[id]?.let { Color(it.toInt()) } ?: dotFallback
    }

    // TalkBack reads the day as a sentence: no state here is carried by colour
    // alone (§10).
    val cdToday = stringResource(R.string.feel_cd_today)
    val cdSelected = stringResource(R.string.feel_cd_selected)
    val cdMood = stringResource(
        R.string.feel_cd_mood_fmt,
        String.format(Locale.getDefault(), "%.1f", mood ?: 0.0),
    )
    val cdDoses = stringResource(R.string.feel_cd_doses_fmt, medIds.size)
    val cdBleeding = stringResource(R.string.feel_cd_bleeding)
    val cd = listOfNotNull(
        date.dayOfMonth.toString(),
        cdToday.takeIf { isToday },
        cdSelected.takeIf { isSelected },
        cdMood.takeIf { mood != null },
        cdDoses.takeIf { medIds.isNotEmpty() },
        cdBleeding.takeIf { bleeding },
    ).joinToString(", ")

    Box(
        modifier = modifier
            .height(CellHeight)
            .clickable(onClick = onClick)
            .semantics { contentDescription = cd },
        contentAlignment = Alignment.Center,
    ) {
        if (bleeding) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(DiscSize)
                    .padding(
                        start = if (bleedStartsRun) 3.dp else 0.dp,
                        end = if (bleedEndsRun) 3.dp else 0.dp,
                    )
                    .clip(
                        RoundedCornerShape(
                            topStart = if (bleedStartsRun) 100.dp else 0.dp,
                            bottomStart = if (bleedStartsRun) 100.dp else 0.dp,
                            topEnd = if (bleedEndsRun) 100.dp else 0.dp,
                            bottomEnd = if (bleedEndsRun) 100.dp else 0.dp,
                        ),
                    )
                    .background(scheme.errorContainer),
            )
        }

        // Mood tint tops out at .42 on purpose: the calendar reads as a month,
        // not as a heat map.
        val discAlpha = when {
            isToday -> 1f
            mood != null -> ((mood / 10.0).coerceIn(0.0, 1.0) * 0.42).toFloat()
            else -> 0f
        }
        if (discAlpha > 0f) {
            Box(
                modifier = Modifier
                    .size(DiscSize)
                    .clip(CircleShape)
                    .background(scheme.primary.copy(alpha = discAlpha)),
            )
        }
        if (isSelected) {
            Box(
                modifier = Modifier
                    .size(DiscSize)
                    .border(2.dp, scheme.primary, CircleShape),
            )
        }

        Text(
            date.dayOfMonth.toString(),
            style = MaterialTheme.typography.bodySmall,
            color = if (isToday) scheme.onPrimary else scheme.onSurface,
            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
        )

        if (dots.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 2.dp),
            ) {
                dots.forEach { color ->
                    Box(
                        modifier = Modifier
                            .size(4.dp)
                            .clip(CircleShape)
                            .background(color.copy(alpha = 0.85f)),
                    )
                }
            }
        }
    }
}

// ----------------------------------------------------------------- history --

/** One history card: the bars on the left, the words on the right (§6.7). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EntryCard(
    entry: JournalEntry,
    barDefs: List<MetricDefinition>,
    customValues: Map<Long, UInt>,
    today: LocalDate,
    zone: ZoneId,
    onClick: () -> Unit,
) {
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val dayFmt = remember { SimpleDateFormat("d MMMM", Locale.getDefault()) }
    val day = remember(entry.atMs) { Instant.ofEpochMilli(entry.atMs).atZone(zone).toLocalDate() }
    val time = timeFmt.format(Date(entry.atMs))
    val dateLabel = when (day) {
        today -> stringResource(R.string.feel_entry_today_fmt, time)
        today.minusDays(1) -> stringResource(R.string.feel_entry_yesterday_fmt, time)
        else -> stringResource(
            R.string.feel_entry_date_fmt,
            dayFmt.format(Date(entry.atMs)).uppercase(Locale.getDefault()),
            time,
        )
    }

    val bars = barDefs.mapIndexedNotNull { index, def ->
        val raw: UInt? = if (def.columnName != null) {
            journalColumn(entry, def.columnName!!)
        } else {
            customValues[def.id]
        }
        raw?.let { Triple(it.toInt(), def.maxValue.toInt().coerceAtLeast(1), metricAccent(def, index)) }
    }

    val tags = remember(entry.sideEffects) { splitEffects(entry.sideEffects.orEmpty()) }

    EggCard(variant = CardVariant.Low, padding = PaddingValues(16.dp), onClick = onClick) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
            if (bars.isNotEmpty()) MoodBars(bars)
            Column(modifier = Modifier.weight(1f)) {
                MicroLabel(dateLabel)
                entry.freeText?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
                if (tags.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 9.dp),
                    ) {
                        tags.forEach { tag ->
                            Box(
                                modifier = Modifier
                                    .clip(EggShapes.Pill)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                    .padding(horizontal = 9.dp, vertical = 3.dp),
                            ) {
                                Text(
                                    tag,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Up to four tracks of 8 dp; a zero still shows a stub so the axis is legible. */
@Composable
private fun MoodBars(bars: List<Triple<Int, Int, Color>>) {
    val height = 44.dp
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier.height(height),
    ) {
        bars.forEach { (value, max, color) ->
            Box(
                modifier = Modifier
                    .width(8.dp)
                    .height(height)
                    .clip(EggShapes.Pill)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.BottomCenter,
            ) {
                val fraction = (value.coerceIn(0, max).toFloat() / max).coerceAtLeast(8f / 44f)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(height * fraction)
                        .clip(EggShapes.Pill)
                        .background(color),
                )
            }
        }
    }
}

/** Built-in journal gauges keep their value in the entry's own columns. */
private fun journalColumn(entry: JournalEntry, column: String): UInt? = when (column) {
    "mood" -> entry.mood
    "dysphoria" -> entry.dysphoria
    "euphoria" -> entry.euphoria
    "libido" -> entry.libido
    "energy" -> entry.energy
    else -> null
}
