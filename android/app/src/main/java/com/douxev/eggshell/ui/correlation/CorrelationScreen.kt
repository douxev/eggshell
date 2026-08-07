package com.douxev.eggshell.ui.correlation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
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
import com.douxev.eggshell.data.BleedingRepository
import com.douxev.eggshell.data.FeaturesPrefs
import com.douxev.eggshell.data.JournalRepository
import com.douxev.eggshell.data.MedicationRepository
import com.douxev.eggshell.data.PlannedDoses
import com.douxev.eggshell.punctuality.DoseTiming
import com.douxev.eggshell.punctuality.timingOf
import com.douxev.eggshell.ui.common.ScreenHeader
import com.douxev.eggshell.ui.common.rememberLocale
import com.douxev.eggshell.ui.components.CardVariant
import com.douxev.eggshell.ui.components.EggCard
import com.douxev.eggshell.ui.components.EmptyState
import com.douxev.eggshell.ui.components.Pill
import com.douxev.eggshell.ui.components.SkeletonBlock
import com.douxev.eggshell.ui.theme.EggColors
import com.douxev.eggshell.ui.theme.EggDim

@HiltViewModel
class CorrelationViewModel @Inject constructor(
    private val journal: JournalRepository,
    private val medications: MedicationRepository,
    private val plannedDoses: PlannedDoses,
    private val bleeding: BleedingRepository,
    private val dreams: com.douxev.eggshell.data.DreamsRepository,
    private val features: FeaturesPrefs,
) : ViewModel() {

    data class State(
        val days: Int = 90,
        val fromMs: Long = 0L,
        val toMs: Long = 0L,
        val moodPoints: List<Pair<Long, Int>> = emptyList(),
        /** Logged on time, or logged without a planned time to compare against. */
        val onTimeDoses: List<Long> = emptyList(),
        val lateDoses: List<Long> = emptyList(),
        val missedDoses: List<Long> = emptyList(),
        val treatmentChanges: List<Long> = emptyList(),
        val bleedingDays: List<Long> = emptyList(),
        /**
         * Nights a dream was recorded, placed by `night_ms` and never by when
         * the entry was typed — a dream written this morning about last week
         * belongs last week, and a lane drawn from the writing time would put
         * every late-recalled dream next to the wrong doses.
         */
        val dreamNights: List<Long> = emptyList(),
        val lucidNights: List<Long> = emptyList(),
        val loading: Boolean = true,
    ) {
        val isEmpty: Boolean
            get() = moodPoints.size < 2 && onTimeDoses.isEmpty() &&
                lateDoses.isEmpty() && missedDoses.isEmpty()
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Read again every time the segment is shown.
     *
     * The ViewModel is scoped to a back-stack entry that survives while another
     * screen sits on top, so a one-shot latch would freeze the chart on the
     * data of the first visit — a dose or a feeling logged since would simply
     * never appear. The reload keeps the previous points on screen while it
     * runs, so there is no flash of skeleton.
     */
    fun reload() = load(_state.value.days)

    fun load(days: Int) {
        val now = System.currentTimeMillis()
        val from = now - days.toLong() * 86_400_000L
        _state.value = _state.value.copy(days = days, loading = true)
        viewModelScope.launch {
            val mood = runCatching { journal.list(0, 1000) }.getOrDefault(emptyList())
                .filter { it.atMs in from..now && it.mood != null }
                .map { it.atMs to it.mood!!.toInt() }
            val doses = runCatching { medications.listDoseEventsBetween(from, now) }
                .getOrDefault(emptyList())
            // Same punctuality definition as everywhere else: ±15 min is on
            // time. An intake with no planned time is logged, not late — it is
            // never turned into a fabricated delay (D2).
            val logged = doses.filter { it.status == "taken" }
            // Partitioned on the event itself, never on its instant: two
            // treatments taken at the same minute share a timestamp, and a set
            // or a map keyed on it would drop the punctual twin of a late one.
            val (lateEvents, onTimeEvents) = logged.partition { e ->
                val planned = e.scheduledAtMs
                planned != null &&
                    timingOf(((e.takenAtMs - planned) / 60_000L).toInt()) == DoseTiming.Late
            }
            val late = lateEvents.map { it.takenAtMs }
            val onTime = onTimeEvents.map { it.takenAtMs }
            // A forgotten dose leaves no event behind, so reading the stored
            // ones could only ever see declared skips — and the lane stayed
            // empty while Médics counted oublis. The occurrence grid is the
            // single source of truth for what was expected (D2).
            val missed = runCatching { plannedDoses.window(from, now) }
                .getOrNull()
                ?.occurrences
                .orEmpty()
                .filter { it.event == null }
                .map { it.plannedAtMs }
            val changes = runCatching { medications.listTreatmentChanges(from, now) }
                .getOrDefault(emptyList()).map { it.atMs }
            val dreamEntries = if (features.dreams.value) {
                runCatching { dreams.listBetween(from, now) }.getOrDefault(emptyList())
            } else emptyList()
            val bleeds = if (features.bleeding.value) {
                runCatching { bleeding.list(0, 1000) }.getOrDefault(emptyList())
                    .filter { it.atMs in from..now }.map { it.atMs }
            } else emptyList()
            _state.value = State(
                days = days,
                fromMs = from,
                toMs = now,
                moodPoints = mood.sortedBy { it.first },
                onTimeDoses = onTime,
                lateDoses = late,
                missedDoses = missed,
                treatmentChanges = changes,
                bleedingDays = bleeds,
                dreamNights = dreamEntries.map { it.nightMs },
                // Lucid nights ride their own lane rather than a flag on the
                // first: they are rare, and a marker that only sometimes means
                // something is one the eye learns to ignore.
                lucidNights = dreamEntries.filter { it.lucid }.map { it.nightMs },
                loading = false,
            )
        }
    }
}

/**
 * Corrélations, as list items — the third segment of Ressenti, and the whole
 * body of the standalone screen.
 *
 * Descriptive only: everything is drawn on one shared time axis so a shape can
 * be *seen*, and the disclaimer under it says in plain words that a coincidence
 * in time is not a cause.
 */
fun LazyListScope.correlationSegment(
    state: CorrelationViewModel.State,
    onWindow: (Int) -> Unit,
    onAddEntry: (() -> Unit)? = null,
) {
    item(key = "corr-window") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(30, 90, 180).forEach { d ->
                Pill(
                    label = stringResource(R.string.correlation_window_days, d),
                    selected = state.days == d,
                    onClick = { onWindow(d) },
                )
            }
        }
    }

    when {
        state.loading && state.isEmpty -> item(key = "corr-skeleton") {
            SkeletonBlock(height = 240.dp)
        }
        state.isEmpty -> item(key = "corr-empty") {
            EmptyState(
                message = stringResource(R.string.correlation_not_enough_data),
                actionLabel = onAddEntry?.let { stringResource(R.string.feel_corr_empty_action) },
                onAction = onAddEntry,
            )
        }
        else -> {
            item(key = "corr-chart") { CorrelationChart(state) }
            item(key = "corr-summary") {
                val avgMood = state.moodPoints.map { it.second }.takeIf { it.isNotEmpty() }
                    ?.average()?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: "—"
                Text(
                    stringResource(
                        R.string.correlation_summary,
                        state.days,
                        state.onTimeDoses.size + state.lateDoses.size,
                        state.missedDoses.size,
                        avgMood,
                        state.treatmentChanges.size,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }

    item(key = "corr-disclaimer") {
        Text(
            stringResource(R.string.correlation_disclaimer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun CorrelationScreen(
    onBack: () -> Unit,
    vm: CorrelationViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    LaunchedEffect(Unit) { vm.reload() }

    Scaffold(containerColor = MaterialTheme.colorScheme.surface) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(
                start = EggDim.ScreenMargin,
                end = EggDim.ScreenMargin,
                bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "corr-header") {
                ScreenHeader(
                    title = stringResource(R.string.correlation_title),
                    onBack = onBack,
                )
            }
            item(key = "corr-hint") {
                Text(
                    stringResource(R.string.correlation_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            correlationSegment(state = state, onWindow = vm::load)
        }
    }
}

/** Gutter reserved for the axis labels — §5.1: the legend is the axis. */
private val AxisGutter = 96.dp
private const val MoodChartHeight = 132
private const val LaneHeight = 18

@Composable
private fun CorrelationChart(state: CorrelationViewModel.State) {
    val scheme = MaterialTheme.colorScheme
    val grid = EggColors.chartGrid
    val span = (state.toMs - state.fromMs).coerceAtLeast(1L).toFloat()
    val fractionFor: (Long) -> Float = { t ->
        ((t - state.fromMs).toFloat() / span).coerceIn(0f, 1f)
    }

    EggCard(variant = CardVariant.Low, padding = PaddingValues(18.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Mood — the main curve: gradient area, polyline, fat terminal dot.
            Row(verticalAlignment = Alignment.CenterVertically) {
                AxisLabel(stringResource(R.string.feel_corr_axis_mood), scheme.primary)
                Canvas(
                    modifier = Modifier
                        .weight(1f)
                        .height(MoodChartHeight.dp),
                ) {
                    val w = size.width
                    val h = size.height
                    fun yFor(v: Int): Float = h - (v.coerceIn(0, 10) / 10f) * h
                    listOf(0, 5, 10).forEach { g ->
                        val y = yFor(g).coerceIn(0.5f, h - 0.5f)
                        drawLine(grid, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
                    }
                    // Treatment changes: dashed verticals through the curve.
                    val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
                    state.treatmentChanges.forEach { t ->
                        val x = fractionFor(t) * w
                        drawLine(
                            scheme.secondary,
                            Offset(x, 0f),
                            Offset(x, h),
                            strokeWidth = 2f,
                            pathEffect = dash,
                        )
                    }
                    val pts = state.moodPoints.map { (t, v) ->
                        Offset(fractionFor(t) * w, yFor(v))
                    }
                    if (pts.size >= 2) {
                        val area = Path().apply {
                            moveTo(pts.first().x, h)
                            pts.forEach { lineTo(it.x, it.y) }
                            lineTo(pts.last().x, h)
                            close()
                        }
                        drawPath(
                            area,
                            Brush.verticalGradient(
                                listOf(
                                    scheme.primary.copy(alpha = 0.28f),
                                    scheme.primary.copy(alpha = 0f),
                                ),
                            ),
                        )
                        val line = Path().apply {
                            moveTo(pts.first().x, pts.first().y)
                            pts.drop(1).forEach { lineTo(it.x, it.y) }
                        }
                        drawPath(line, scheme.primary, style = Stroke(width = 3f))
                    }
                    pts.forEach { drawCircle(scheme.primary, radius = 3.5f, center = it) }
                    pts.lastOrNull()?.let { drawCircle(scheme.primary, radius = 5.5f, center = it) }
                }
            }

            DoseLane(
                label = stringResource(R.string.feel_corr_axis_doses),
                color = scheme.tertiary,
                times = state.onTimeDoses,
                fractionFor = fractionFor,
            )
            if (state.lateDoses.isNotEmpty()) {
                DoseLane(
                    label = stringResource(R.string.feel_corr_axis_late),
                    color = scheme.secondary,
                    times = state.lateDoses,
                    fractionFor = fractionFor,
                )
            }
            if (state.missedDoses.isNotEmpty()) {
                DoseLane(
                    label = stringResource(
                        R.string.feel_corr_axis_missed_fmt,
                        state.missedDoses.size,
                    ),
                    color = scheme.error,
                    times = state.missedDoses,
                    fractionFor = fractionFor,
                    cross = true,
                )
            }
            if (state.bleedingDays.isNotEmpty()) {
                DoseLane(
                    label = stringResource(R.string.feel_corr_axis_bleeding),
                    color = scheme.error,
                    times = state.bleedingDays,
                    fractionFor = fractionFor,
                    tick = true,
                )
            }
            if (state.dreamNights.isNotEmpty()) {
                DoseLane(
                    label = stringResource(
                        R.string.feel_corr_axis_dreams_fmt,
                        state.dreamNights.size,
                    ),
                    color = scheme.secondary,
                    times = state.dreamNights,
                    fractionFor = fractionFor,
                    tick = true,
                )
            }
            if (state.lucidNights.isNotEmpty()) {
                DoseLane(
                    label = stringResource(R.string.feel_corr_axis_lucid),
                    color = scheme.tertiary,
                    times = state.lucidNights,
                    fractionFor = fractionFor,
                    tick = true,
                )
            }
            if (state.treatmentChanges.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AxisLabel(
                        stringResource(
                            R.string.feel_corr_axis_changes_fmt,
                            state.treatmentChanges.size,
                        ),
                        scheme.secondary,
                    )
                }
            }

            // X axis: three time gradations, proportional to time, never an index.
            val fmtLocale = rememberLocale()
            val fmt = remember(fmtLocale) { SimpleDateFormat("d MMM", fmtLocale) }
            Row(modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.width(AxisGutter))
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    listOf(
                        state.fromMs,
                        state.fromMs + (state.toMs - state.fromMs) / 2,
                        state.toMs,
                    ).forEach { t ->
                        Text(
                            fmt.format(Date(t)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AxisLabel(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .width(AxisGutter)
            .padding(end = 8.dp),
    )
}

@Composable
private fun DoseLane(
    label: String,
    color: Color,
    times: List<Long>,
    fractionFor: (Long) -> Float,
    cross: Boolean = false,
    tick: Boolean = false,
) {
    val grid = EggColors.chartGrid
    Row(verticalAlignment = Alignment.CenterVertically) {
        AxisLabel(label, color)
        Canvas(
            modifier = Modifier
                .weight(1f)
                .height(LaneHeight.dp),
        ) {
            val w = size.width
            val mid = size.height / 2f
            drawLine(grid, Offset(0f, mid), Offset(w, mid), strokeWidth = 1f)
            times.forEach { t ->
                val x = fractionFor(t) * w
                when {
                    cross -> {
                        drawLine(color, Offset(x - 4f, mid - 4f), Offset(x + 4f, mid + 4f), strokeWidth = 2.5f)
                        drawLine(color, Offset(x - 4f, mid + 4f), Offset(x + 4f, mid - 4f), strokeWidth = 2.5f)
                    }
                    tick -> drawLine(color, Offset(x, mid - 6f), Offset(x, mid + 6f), strokeWidth = 3f)
                    else -> drawCircle(color, radius = 4f, center = Offset(x, mid))
                }
            }
        }
    }
}
