package com.douxev.eggshell.ui.correlation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
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

@HiltViewModel
class CorrelationViewModel @Inject constructor(
    private val journal: JournalRepository,
    private val medications: MedicationRepository,
    private val bleeding: BleedingRepository,
    private val features: FeaturesPrefs,
) : ViewModel() {

    data class State(
        val days: Int = 90,
        val fromMs: Long = 0L,
        val toMs: Long = 0L,
        val moodPoints: List<Pair<Long, Int>> = emptyList(),
        val takenDoses: List<Long> = emptyList(),
        val skippedDoses: List<Long> = emptyList(),
        val treatmentChanges: List<Long> = emptyList(),
        val bleedingDays: List<Long> = emptyList(),
        val loading: Boolean = true,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init { load(90) }

    fun load(days: Int) {
        val now = System.currentTimeMillis()
        val from = now - days.toLong() * 86_400_000L
        _state.value = _state.value.copy(days = days, loading = true)
        viewModelScope.launch {
            val mood = runCatching { journal.list(0, 1000) }.getOrDefault(emptyList())
                .filter { it.atMs in from..now && it.mood != null }
                .map { it.atMs to it.mood!!.toInt() }
            val doses = runCatching { medications.listDoseEventsBetween(from, now) }.getOrDefault(emptyList())
            val taken = doses.filter { it.status == "taken" }.map { it.takenAtMs }
            val skipped = doses.filter { it.status != "taken" }.map { it.takenAtMs }
            val changes = runCatching { medications.listTreatmentChanges(from, now) }
                .getOrDefault(emptyList()).map { it.atMs }
            val bleeds = if (features.bleeding.value) {
                runCatching { bleeding.list(0, 1000) }.getOrDefault(emptyList())
                    .filter { it.atMs in from..now }.map { it.atMs }
            } else emptyList()
            _state.value = State(
                days = days,
                fromMs = from,
                toMs = now,
                moodPoints = mood.sortedBy { it.first },
                takenDoses = taken,
                skippedDoses = skipped,
                treatmentChanges = changes,
                bleedingDays = bleeds,
                loading = false,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CorrelationScreen(
    onBack: () -> Unit,
    vm: CorrelationViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.correlation_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.correlation_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(30, 90, 180).forEach { d ->
                    FilterChip(
                        selected = state.days == d,
                        onClick = { vm.load(d) },
                        label = { Text(stringResource(R.string.correlation_window_days, d)) },
                    )
                }
            }

            val moodColor = MaterialTheme.colorScheme.primary
            val takenColor = MaterialTheme.colorScheme.tertiary
            val skipColor = MaterialTheme.colorScheme.error
            val changeColor = MaterialTheme.colorScheme.secondary
            val bleedColor = MaterialTheme.colorScheme.error
            val gridColor = MaterialTheme.colorScheme.outlineVariant

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.moodPoints.size < 2 && state.takenDoses.isEmpty() && state.skippedDoses.isEmpty()) {
                    Text(
                        stringResource(R.string.correlation_not_enough_data),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                } else {
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp)
                            .padding(16.dp),
                    ) {
                        val w = size.width
                        val h = size.height
                        val moodTop = 6f
                        val moodBottom = h * 0.6f
                        val doseY = h * 0.76f
                        val skipY = h * 0.76f
                        val bleedY = h * 0.93f
                        val span = (state.toMs - state.fromMs).coerceAtLeast(1L).toFloat()
                        fun xFor(t: Long): Float =
                            (((t - state.fromMs).toFloat() / span) * w).coerceIn(0f, w)
                        fun moodYFor(v: Int): Float =
                            moodBottom - (v.coerceIn(0, 10) / 10f) * (moodBottom - moodTop)

                        // Horizontal guide lines at mood 0/5/10.
                        listOf(0, 5, 10).forEach { g ->
                            val y = moodYFor(g)
                            drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
                        }

                        // Treatment-change vertical markers (dashed, full height).
                        val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
                        state.treatmentChanges.forEach { t ->
                            val x = xFor(t)
                            drawLine(changeColor, Offset(x, 0f), Offset(x, h), strokeWidth = 2f, pathEffect = dash)
                        }

                        // Mood polyline + dots.
                        if (state.moodPoints.size >= 2) {
                            val path = Path()
                            state.moodPoints.forEachIndexed { i, (t, v) ->
                                val x = xFor(t)
                                val y = moodYFor(v)
                                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                            }
                            drawPath(path, moodColor, style = Stroke(width = 3f))
                        }
                        state.moodPoints.forEach { (t, v) ->
                            drawCircle(moodColor, radius = 4f, center = Offset(xFor(t), moodYFor(v)))
                        }

                        // Dose markers: taken dots, skipped crosses.
                        state.takenDoses.forEach { t ->
                            drawCircle(takenColor, radius = 5f, center = Offset(xFor(t), doseY))
                        }
                        state.skippedDoses.forEach { t ->
                            val x = xFor(t)
                            drawLine(skipColor, Offset(x - 5f, skipY - 5f), Offset(x + 5f, skipY + 5f), strokeWidth = 2.5f)
                            drawLine(skipColor, Offset(x - 5f, skipY + 5f), Offset(x + 5f, skipY - 5f), strokeWidth = 2.5f)
                        }

                        // Bleeding ticks at the very bottom.
                        state.bleedingDays.forEach { t ->
                            val x = xFor(t)
                            drawLine(bleedColor, Offset(x, bleedY - 5f), Offset(x, bleedY + 5f), strokeWidth = 3f)
                        }
                    }
                }
            }

            // Legend.
            LegendRow(MaterialTheme.colorScheme.primary, stringResource(R.string.correlation_legend_mood))
            LegendRow(MaterialTheme.colorScheme.tertiary, stringResource(R.string.correlation_legend_taken))
            LegendRow(MaterialTheme.colorScheme.error, stringResource(R.string.correlation_legend_skipped))
            LegendRow(MaterialTheme.colorScheme.secondary, stringResource(R.string.correlation_legend_change))
            if (state.bleedingDays.isNotEmpty()) {
                LegendRow(MaterialTheme.colorScheme.error, stringResource(R.string.correlation_legend_bleeding))
            }

            // Plain-language summary (descriptive only — no causal claim).
            val avgMood = state.moodPoints.map { it.second }.takeIf { it.isNotEmpty() }
                ?.average()?.let { String.format("%.1f", it) } ?: "—"
            Text(
                stringResource(
                    R.string.correlation_summary,
                    state.days,
                    state.takenDoses.size,
                    state.skippedDoses.size,
                    avgMood,
                    state.treatmentChanges.size,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(R.string.correlation_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LegendRow(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}
