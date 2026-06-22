package com.douxev.eggshell.ui.summary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
import com.douxev.eggshell.data.SummaryPeriod
import com.douxev.eggshell.data.SummaryPrefs
import com.douxev.eggshell.data.SummaryRepository

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummaryScreen(
    onBack: () -> Unit,
    vm: SummaryViewModel = hiltViewModel(),
) {
    val period by vm.period.collectAsState()
    val summary by vm.summary.collectAsState()
    val loading by vm.loading.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.summary_title)) },
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = period == SummaryPeriod.WEEK,
                    onClick = { vm.setPeriod(SummaryPeriod.WEEK) },
                    label = { Text(stringResource(R.string.summary_period_week)) },
                )
                FilterChip(
                    selected = period == SummaryPeriod.MONTH,
                    onClick = { vm.setPeriod(SummaryPeriod.MONTH) },
                    label = { Text(stringResource(R.string.summary_period_month)) },
                )
            }

            val s = summary
            when {
                loading && s == null -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) { CircularProgressIndicator() }
                }
                s == null || !s.hasData -> {
                    InfoCard(stringResource(R.string.summary_no_data))
                }
                else -> {
                    HeadlineCard(headlineFor(s))

                    if (s.hasJournal) {
                        SectionCard(title = stringResource(R.string.summary_mood_title)) {
                            ComparisonLine(
                                current = fmt1(s.moodCurrent),
                                previous = fmt1(s.moodPrevious),
                                suffix = stringResource(R.string.summary_out_of_ten),
                            )
                        }
                    }

                    if (s.hasMedications && (s.expectedCurrent > 0 || s.takenCurrent > 0 || s.expectedPrevious > 0)) {
                        SectionCard(title = stringResource(R.string.summary_doses_title)) {
                            Text(
                                stringResource(
                                    R.string.summary_doses_logged_fmt,
                                    s.takenCurrent,
                                    s.expectedCurrent,
                                ),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                stringResource(
                                    R.string.summary_missed_vs_fmt,
                                    s.missedCurrent,
                                    s.missedPrevious,
                                ),
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

                    if (s.customMetrics.isNotEmpty()) {
                        SectionCard(title = stringResource(R.string.summary_symptoms_title)) {
                            s.customMetrics.forEach { m ->
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        m.label,
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        stringResource(
                                            R.string.summary_vs_prev_fmt,
                                            fmt1(m.current),
                                            fmt1(m.previous),
                                        ),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        }
                    }

                    if (s.hasJournal) {
                        SectionCard(title = stringResource(R.string.summary_journal_title)) {
                            Text(
                                stringResource(
                                    R.string.summary_journal_count_fmt,
                                    s.journalCountCurrent,
                                    s.journalCountPrevious,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }

                    Text(
                        stringResource(R.string.summary_disclaimer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
private fun HeadlineCard(text: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(20.dp),
        )
    }
}

@Composable
private fun InfoCard(text: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(20.dp),
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            content()
        }
    }
}

@Composable
private fun ComparisonLine(current: String, previous: String, suffix: String) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            "$current$suffix",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            "  " + stringResource(R.string.summary_vs_prev_short_fmt, previous),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
    }
}

private fun fmt1(v: Double?): String = v?.let { String.format("%.1f", it) } ?: "—"
