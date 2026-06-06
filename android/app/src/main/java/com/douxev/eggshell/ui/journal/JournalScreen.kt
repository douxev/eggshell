package com.douxev.eggshell.ui.journal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.douxev.eggshell.R
import com.douxev.eggshell.data.JournalRepository
import uniffi.transition.JournalEntry

@HiltViewModel
class JournalListViewModel @Inject constructor(
    private val repo: JournalRepository,
) : ViewModel() {
    private val _items = MutableStateFlow<List<JournalEntry>>(emptyList())
    val items: StateFlow<List<JournalEntry>> = _items.asStateFlow()

    private val _selectedDate = MutableStateFlow<LocalDate?>(null)
    val selectedDate: StateFlow<LocalDate?> = _selectedDate.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _items.value = runCatching { repo.list(0, 200) }.getOrDefault(emptyList())
                .sortedByDescending { it.atMs }
        }
    }

    fun selectDate(date: LocalDate?) {
        _selectedDate.value = date
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun JournalListScreen(
    onAdd: () -> Unit,
    onEdit: (Long) -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenCorrelation: () -> Unit = {},
    vm: JournalListViewModel = hiltViewModel(),
) {
    val items by vm.items.collectAsState()
    val selectedDate by vm.selectedDate.collectAsState()
    LaunchedEffect(Unit) { vm.refresh() }

    val zone = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now(zone) }
    var visibleMonth by remember { mutableStateOf(YearMonth.from(today)) }
    val byDate: Map<LocalDate, List<JournalEntry>> = remember(items) {
        items.groupBy {
            java.time.Instant.ofEpochMilli(it.atMs).atZone(zone).toLocalDate()
        }
    }
    val visibleEntries = remember(items, selectedDate, byDate) {
        if (selectedDate == null) items
        else byDate[selectedDate].orEmpty().sortedByDescending { it.atMs }
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAdd,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = stringResource(R.string.journal_new),
                )
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                com.douxev.eggshell.ui.common.ScreenHeader(
                    title = stringResource(R.string.journal_title),
                    onOpenSettings = onOpenSettings,
                )
            }

            item {
                androidx.compose.material3.OutlinedButton(
                    onClick = onOpenCorrelation,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        Icons.Filled.Insights,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 6.dp),
                    )
                    Text(stringResource(R.string.correlation_open))
                }
            }

            item {
                MonthCalendar(
                    yearMonth = visibleMonth,
                    today = today,
                    selected = selectedDate,
                    byDate = byDate,
                    onPrevMonth = { visibleMonth = visibleMonth.minusMonths(1) },
                    onNextMonth = { visibleMonth = visibleMonth.plusMonths(1) },
                    onSelect = { date -> vm.selectDate(if (date == selectedDate) null else date) },
                )
            }

            item {
                Text(
                    if (selectedDate == null) stringResource(R.string.journal_history)
                    else SimpleDateFormat("EEEE d MMMM", Locale.getDefault())
                        .format(Date.from(selectedDate!!.atStartOfDay(zone).toInstant()))
                        .replaceFirstChar {
                            if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
                        },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                )
            }

            if (visibleEntries.isEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (selectedDate == null) stringResource(R.string.journal_empty)
                            else stringResource(R.string.journal_empty_for_date),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                }
            } else {
                items(visibleEntries, key = { it.id }) { e ->
                    EntryCard(e, onClick = { onEdit(e.id) })
                }
            }
        }
    }
}

/**
 * Compact monthly calendar grid: weekday header + 6×7 day cells. Tapping a
 * day selects it; tapping the same day again unselects. Prev/next arrows in
 * the title row let the user paginate through months. Days with entries
 * show a small mood-tinted dot below the number.
 */
@Composable
private fun MonthCalendar(
    yearMonth: YearMonth,
    today: LocalDate,
    selected: LocalDate?,
    byDate: Map<LocalDate, List<JournalEntry>>,
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onSelect: (LocalDate) -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }
    val monthLabel = remember(yearMonth) {
        SimpleDateFormat("LLLL yyyy", Locale.getDefault())
            .format(Date.from(yearMonth.atDay(1).atStartOfDay(zone).toInstant()))
            .replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
            // Title row: ◀ Month YYYY ▶
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                IconButton(onClick = onPrevMonth) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = stringResource(R.string.journal_prev_month),
                    )
                }
                Text(
                    monthLabel,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onNextMonth) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = stringResource(R.string.journal_next_month),
                    )
                }
            }

            // Weekday header — locale-aware first day of week.
            val firstDayOfWeek = remember {
                java.time.temporal.WeekFields.of(Locale.getDefault()).firstDayOfWeek
            }
            val weekdayLabels = remember(firstDayOfWeek) {
                (0..6).map { i ->
                    val dow = DayOfWeek.of(((firstDayOfWeek.value - 1 + i) % 7) + 1)
                    dow.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault())
                        .trimEnd('.')
                        .replaceFirstChar { it.titlecase(Locale.getDefault()) }
                }
            }
            Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp)) {
                weekdayLabels.forEach { label ->
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        fontWeight = FontWeight.SemiBold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }

            // Compute the leading empty slots so the first day of the month
            // lands under its weekday column.
            val firstOfMonth = yearMonth.atDay(1)
            val leadingBlanks = run {
                val dayOfWeek = firstOfMonth.dayOfWeek.value // 1=Mon..7=Sun
                val firstDow = firstDayOfWeek.value
                ((dayOfWeek - firstDow) + 7) % 7
            }
            val daysInMonth = yearMonth.lengthOfMonth()
            val totalCells = leadingBlanks + daysInMonth
            val rows = (totalCells + 6) / 7

            for (r in 0 until rows) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    for (c in 0..6) {
                        val cellIdx = r * 7 + c
                        val dayNum = cellIdx - leadingBlanks + 1
                        if (dayNum in 1..daysInMonth) {
                            val date = yearMonth.atDay(dayNum)
                            val entries = byDate[date].orEmpty()
                            val avgMood = entries.mapNotNull { it.mood?.toInt() }
                                .takeIf { it.isNotEmpty() }?.average()
                            DayGridCell(
                                date = date,
                                isToday = date == today,
                                isSelected = selected == date,
                                hasEntries = entries.isNotEmpty(),
                                avgMood = avgMood,
                                modifier = Modifier.weight(1f),
                                onClick = { onSelect(date) },
                            )
                        } else {
                            Box(modifier = Modifier.weight(1f).aspectRatio(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayGridCell(
    date: LocalDate,
    isToday: Boolean,
    isSelected: Boolean,
    hasEntries: Boolean,
    avgMood: Double?,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val container = when {
        isSelected -> MaterialTheme.colorScheme.primary
        isToday -> MaterialTheme.colorScheme.primaryContainer
        else -> Color.Transparent
    }
    val onContainer = when {
        isSelected -> MaterialTheme.colorScheme.onPrimary
        isToday -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .padding(2.dp)
            .clip(RoundedCornerShape(50))
            .background(container)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                date.dayOfMonth.toString(),
                style = MaterialTheme.typography.bodyMedium,
                color = onContainer,
                fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Normal,
            )
            val dotColor = when {
                !hasEntries -> Color.Transparent
                avgMood == null -> if (isSelected) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.primary
                else -> {
                    val intensity = (avgMood / 10.0).coerceIn(0.3, 1.0).toFloat()
                    if (isSelected) MaterialTheme.colorScheme.onPrimary.copy(alpha = intensity)
                    else MaterialTheme.colorScheme.primary.copy(alpha = intensity)
                }
            }
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(RoundedCornerShape(50))
                    .background(dotColor),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EntryCard(e: JournalEntry, onClick: () -> Unit) {
    val dateFmt = remember { SimpleDateFormat("EEE d MMM", Locale.getDefault()) }
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
        ) {
            MiniBars(e)
            Column(
                modifier = Modifier
                    .padding(start = 14.dp)
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row {
                    Text(
                        dateFmt.format(Date(e.atMs)),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        timeFmt.format(Date(e.atMs)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val tags = e.sideEffects.orEmpty()
                    .split(',').map { it.trim() }.filter { it.isNotEmpty() }
                if (tags.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        tags.forEach { tag ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(MaterialTheme.colorScheme.secondaryContainer)
                                    .padding(horizontal = 9.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    tag,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }
                e.freeText?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun MiniBars(e: JournalEntry) {
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val secondary = MaterialTheme.colorScheme.secondary
    val bars: List<Pair<Int, Color>> = buildList {
        e.mood?.let { add(it.toInt() to primary) }
        e.euphoria?.let { add(it.toInt() to tertiary) }
        e.libido?.let { add(it.toInt() to secondary) }
        e.energy?.let { add(it.toInt() to primary) }
    }
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.height(40.dp),
    ) {
        bars.forEach { (value, color) ->
            Box(
                modifier = Modifier
                    .width(7.dp)
                    .height(40.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.BottomCenter,
            ) {
                val pct = (value.coerceIn(0, 10) / 10f).coerceAtLeast(0.05f)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height((40 * pct).dp)
                        .clip(RoundedCornerShape(50))
                        .background(color),
                )
            }
        }
    }
}

