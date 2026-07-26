package com.douxev.eggshell.ui.summary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.douxev.eggshell.R
import com.douxev.eggshell.data.SummaryPeriod
import com.douxev.eggshell.data.SummaryPrefs
import com.douxev.eggshell.data.SummaryRepository
import com.douxev.eggshell.ui.common.ScreenHeader
import com.douxev.eggshell.ui.components.CardVariant
import com.douxev.eggshell.ui.components.EggCard
import com.douxev.eggshell.ui.components.EmptyState
import com.douxev.eggshell.ui.components.Pill
import com.douxev.eggshell.ui.components.SectionTitle
import com.douxev.eggshell.ui.components.SkeletonBlock
import com.douxev.eggshell.ui.theme.EggDim

@HiltViewModel
class SummaryViewModel @Inject constructor(
    private val repo: SummaryRepository,
    private val prefs: SummaryPrefs,
) : ViewModel() {

    val period: StateFlow<SummaryPeriod> = prefs.period

    private val _summary = MutableStateFlow<SummaryRepository.PeriodSummary?>(null)
    val summary: StateFlow<SummaryRepository.PeriodSummary?> = _summary.asStateFlow()
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    init { reload(prefs.period.value) }

    fun setPeriod(p: SummaryPeriod) {
        if (p == period.value) return
        prefs.setPeriod(p)
        reload(p)
    }

    private fun reload(p: SummaryPeriod) {
        _loading.value = true
        viewModelScope.launch {
            _summary.value = runCatching { repo.compute(p) }.getOrNull()
            _loading.value = false
        }
    }
}

/**
 * « Ton résumé » — the encouraging, honest read of the last week or month.
 *
 * It only ever states what is true: the headline picks the kindest framing the
 * data actually supports, and the footer says out loud that a comparison is not
 * a cause.
 */
@Composable
fun SummaryScreen(
    onBack: () -> Unit,
    vm: SummaryViewModel = hiltViewModel(),
) {
    val period by vm.period.collectAsState()
    val summary by vm.summary.collectAsState()
    val loading by vm.loading.collectAsState()

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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "summary-header") {
                ScreenHeader(title = stringResource(R.string.summary_title), onBack = onBack)
            }
            item(key = "summary-period") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill(
                        label = stringResource(R.string.summary_period_week),
                        selected = period == SummaryPeriod.WEEK,
                        onClick = { vm.setPeriod(SummaryPeriod.WEEK) },
                    )
                    Pill(
                        label = stringResource(R.string.summary_period_month),
                        selected = period == SummaryPeriod.MONTH,
                        onClick = { vm.setPeriod(SummaryPeriod.MONTH) },
                    )
                }
            }

            val s = summary
            when {
                loading && s == null -> item(key = "summary-skeleton") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SkeletonBlock(height = 84.dp)
                        SkeletonBlock(height = 104.dp)
                        SkeletonBlock(height = 104.dp)
                    }
                }
                s == null || !s.hasData -> item(key = "summary-empty") {
                    EmptyState(message = stringResource(R.string.summary_no_data))
                }
                else -> summaryBody(s)
            }

            item(key = "summary-disclaimer") {
                Text(
                    stringResource(R.string.summary_disclaimer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun LazyListScope.summaryBody(s: SummaryRepository.PeriodSummary) {
    item(key = "summary-headline") {
        EggCard(variant = CardVariant.Primary) {
            Text(
                headlineFor(s),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }

    if (s.hasJournal) {
        item(key = "summary-mood") {
            SummarySection(title = stringResource(R.string.summary_mood_title)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        fmt1(s.moodCurrent) + stringResource(R.string.summary_out_of_ten),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "  " + stringResource(
                            R.string.summary_vs_prev_short_fmt,
                            fmt1(s.moodPrevious),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
            }
        }
    }

    if (s.hasMedications && (s.expectedCurrent > 0 || s.takenCurrent > 0 || s.expectedPrevious > 0)) {
        item(key = "summary-doses") {
            SummarySection(title = stringResource(R.string.summary_doses_title)) {
                Text(
                    stringResource(
                        R.string.summary_doses_logged_fmt,
                        s.takenCurrent,
                        s.expectedCurrent,
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stringResource(R.string.summary_missed_vs_fmt, s.missedCurrent, s.missedPrevious),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.summary_estimate_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (s.customMetrics.isNotEmpty()) {
        item(key = "summary-metrics") {
            SummarySection(title = stringResource(R.string.summary_symptoms_title)) {
                s.customMetrics.forEach { m ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            m.label,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            stringResource(
                                R.string.summary_vs_prev_fmt,
                                fmt1(m.current),
                                fmt1(m.previous),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    if (s.hasJournal) {
        item(key = "summary-journal") {
            SummarySection(title = stringResource(R.string.summary_journal_title)) {
                Text(
                    stringResource(
                        R.string.summary_journal_count_fmt,
                        s.journalCountCurrent,
                        s.journalCountPrevious,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** Picks the most encouraging *true* framing for the headline. */
@Composable
private fun headlineFor(s: SummaryRepository.PeriodSummary): String {
    val mood = s.moodCurrent
    val prevMood = s.moodPrevious
    return when {
        mood != null && prevMood != null && mood >= prevMood + 0.3 ->
            stringResource(R.string.summary_headline_mood_up)
        s.hasMedications && s.missedCurrent < s.missedPrevious ->
            stringResource(R.string.summary_headline_fewer_missed)
        s.journalCountCurrent > 0 ->
            stringResource(R.string.summary_headline_kept_logging)
        else ->
            stringResource(R.string.summary_headline_steady)
    }
}

@Composable
private fun SummarySection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionTitle(title)
        EggCard(variant = CardVariant.Low, padding = PaddingValues(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
        }
    }
}

private fun fmt1(v: Double?): String =
    v?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: "—"
