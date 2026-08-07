package com.douxev.eggshell.ui.dreams

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.GraphicEq
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
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
import com.douxev.eggshell.R
import com.douxev.eggshell.data.DreamsRepository
import com.douxev.eggshell.ui.common.MonthGrid
import com.douxev.eggshell.ui.common.MonthGridDefaults
import com.douxev.eggshell.ui.common.ScreenHeader
import com.douxev.eggshell.ui.common.rememberLocale
import com.douxev.eggshell.ui.components.ActionBand
import com.douxev.eggshell.ui.components.CardVariant
import com.douxev.eggshell.ui.components.EggCard
import com.douxev.eggshell.ui.components.EggFab
import com.douxev.eggshell.ui.components.EmptyState
import com.douxev.eggshell.ui.components.MicroLabel
import com.douxev.eggshell.ui.components.SkeletonBlock
import com.douxev.eggshell.ui.components.StatusPill
import com.douxev.eggshell.ui.theme.EggDim
import com.douxev.eggshell.ui.theme.EggShapes
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.SimpleDateFormat
import java.util.Date
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uniffi.transition.Dream
import uniffi.transition.DreamTag

@HiltViewModel
class DreamsViewModel @Inject constructor(
    private val repo: DreamsRepository,
) : ViewModel() {

    /** A dream plus what the list needs to show without a second round-trip. */
    data class Row(
        val dream: Dream,
        val tags: List<DreamTag>,
        val audioCount: Int,
    )

    data class State(
        val rows: List<Row> = emptyList(),
        /** Local date of every night that has a dream, for the calendar. */
        val nights: Map<java.time.LocalDate, Row> = emptyMap(),
        val tags: List<DreamTag> = emptyList(),
        /** Null = every dream. */
        val filterTagId: Long? = null,
        val loading: Boolean = true,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            val filter = _state.value.filterTagId
            val dreams = runCatching { repo.list(filter) }.getOrDefault(emptyList())
            val tags = runCatching { repo.tags() }.getOrDefault(emptyList())
            // A filter whose tag has since been deleted would silently show an
            // empty journal; fall back to everything rather than to nothing.
            val stillExists = filter == null || tags.any { it.id == filter }
            val rows = dreams.map { d ->
                Row(
                    dream = d,
                    tags = runCatching { repo.tagsFor(d.id) }.getOrDefault(emptyList()),
                    audioCount = runCatching { repo.audioFor(d.id).size }.getOrDefault(0),
                )
            }
            val zone = java.time.ZoneId.systemDefault()
            _state.value = State(
                rows = rows,
                nights = rows.associateBy {
                    java.time.Instant.ofEpochMilli(it.dream.nightMs).atZone(zone).toLocalDate()
                },
                tags = tags,
                filterTagId = filter.takeIf { stillExists },
                loading = false,
            )
            if (!stillExists) refresh()
        }
    }

    fun filterBy(tagId: Long?) {
        _state.value = _state.value.copy(filterTagId = tagId)
        refresh()
    }
}

/**
 * « Carnet de rêves » — the list, filtered by recurring theme.
 *
 * Dreams are grouped by the night they belong to, never by when they were
 * written: an entry typed this morning about last week belongs last week. See
 * [DreamsRepository.nightOf].
 */
@Composable
fun DreamsScreen(
    onBack: () -> Unit,
    onOpenDream: (Long) -> Unit,
    onNewDream: () -> Unit,
    /** Tapping an empty night opens the editor already set to that night. */
    onNewNightDream: (Long) -> Unit,
    vm: DreamsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    val today = remember { java.time.LocalDate.now() }
    var visibleMonth by androidx.compose.runtime.saveable.rememberSaveable(
        stateSaver = YearMonthSaver,
    ) { androidx.compose.runtime.mutableStateOf(java.time.YearMonth.from(today)) }
    LaunchedEffect(Unit) { vm.refresh() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        bottomBar = {
            ActionBand {
                EggFab(
                    icon = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.dreams_add),
                    label = stringResource(R.string.dreams_add_label),
                    onClick = onNewDream,
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
                bottom = EggDim.BlockGap,
            ),
            verticalArrangement = Arrangement.spacedBy(EggDim.RowGap),
        ) {
            item { ScreenHeader(title = stringResource(R.string.dreams_title), onBack = onBack) }

            // The tag row is the whole point of the screen: a dream journal is
            // kept to notice what repeats, and this is where repetition shows.
            if (state.tags.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        FilterChip(
                            selected = state.filterTagId == null,
                            onClick = { vm.filterBy(null) },
                            label = { Text(stringResource(R.string.dreams_filter_all)) },
                            shape = EggShapes.Pill,
                            colors = selectedChipColors(),
                        )
                        state.tags.forEach { tag ->
                            FilterChip(
                                selected = state.filterTagId == tag.id,
                                onClick = {
                                    vm.filterBy(if (state.filterTagId == tag.id) null else tag.id)
                                },
                                // The count is what makes the row readable: it
                                // says which themes actually recur.
                                label = { Text("${tag.label} · ${tag.dreamCount}") },
                                shape = EggShapes.Pill,
                                colors = selectedChipColors(),
                            )
                        }
                    }
                }
            }

            // Calendar first, like the mood journal: a dream journal is read
            // for its shape over weeks — which nights are blank, where a run of
            // recall starts — and a list can only ever show that one row at a
            // time.
            item {
                MonthGrid(
                    yearMonth = visibleMonth,
                    onPrevMonth = { visibleMonth = visibleMonth.minusMonths(1) },
                    onNextMonth = { visibleMonth = visibleMonth.plusMonths(1) },
                ) { date ->
                    DreamDayCell(
                        date = date,
                        isToday = date == today,
                        row = state.nights[date],
                        onClick = {
                            val existing = state.nights[date]
                            if (existing != null) onOpenDream(existing.dream.id)
                            else onNewNightDream(DreamsRepository.nightOfDate(date))
                        },
                    )
                }
            }

            if (state.loading && state.rows.isEmpty()) {
                item { SkeletonBlock(height = 96.dp) }
                item { SkeletonBlock(height = 96.dp) }
            } else if (state.rows.isEmpty()) {
                item {
                    EmptyState(
                        message = stringResource(
                            if (state.filterTagId != null) R.string.dreams_empty_filtered
                            else R.string.dreams_empty,
                        ),
                        actionLabel = stringResource(R.string.dreams_empty_action),
                        onAction = onNewDream,
                    )
                }
            } else {
                items(state.rows, key = { it.dream.id }) { row ->
                    DreamCard(row = row, onClick = { onOpenDream(row.dream.id) })
                }
            }

            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun selectedChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
    selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
)

@Composable
private fun DreamCard(row: DreamsViewModel.Row, onClick: () -> Unit) {
    val locale = rememberLocale()
    val nightFmt = remember(locale) { SimpleDateFormat("EEEE d MMMM", locale) }
    val dream = row.dream

    EggCard(
        variant = CardVariant.Low,
        padding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                // The night, not the writing date — stated first because it is
                // the thing the entry is about.
                MicroLabel(
                    stringResource(
                        R.string.dreams_night_fmt,
                        nightFmt.format(Date(dream.nightMs))
                            .replaceFirstChar { it.titlecase(locale) },
                    ),
                )
                Text(
                    dream.title.ifBlank { stringResource(R.string.dreams_untitled) },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (dream.lucid) {
                StatusPill(
                    label = stringResource(R.string.dreams_lucid),
                    container = MaterialTheme.colorScheme.tertiaryContainer,
                    content = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }

        if (dream.body.isNotBlank()) {
            Text(
                dream.body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        if (row.tags.isNotEmpty() || row.audioCount > 0) {
            Row(
                modifier = Modifier.padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (row.audioCount > 0) {
                    Icon(
                        Icons.Filled.GraphicEq,
                        contentDescription = stringResource(
                            R.string.dreams_audio_count_cd, row.audioCount,
                        ),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                row.tags.take(MAX_TAG_CHIPS).forEach { tag ->
                    StatusPill(
                        label = tag.label,
                        container = MaterialTheme.colorScheme.surfaceContainerHighest,
                        content = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (row.tags.size > MAX_TAG_CHIPS) {
                    MicroLabel("+${row.tags.size - MAX_TAG_CHIPS}")
                }
            }
        }
    }
}

/** Past this the chips wrap and the card stops reading as one line. */
private const val MAX_TAG_CHIPS = 3

/**
 * One night in the dream calendar.
 *
 * A night either has a dream or it does not — there is no continuous value to
 * shade, the way mood shades the journal's cells. So presence is a filled disc,
 * lucidity is a ring around it, and a voice note adds a dot: three states the
 * eye separates at a glance, none of them told by colour alone (§10).
 *
 * Tapping an empty night opens the editor already set to it, which is the whole
 * reason a dream journal wants a calendar — you remember a dream two days late
 * and need to file it against the right night without a date picker.
 */
@Composable
private fun DreamDayCell(
    date: java.time.LocalDate,
    isToday: Boolean,
    row: DreamsViewModel.Row?,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val cdToday = stringResource(R.string.feel_cd_today)
    val cdDream = stringResource(R.string.dreams_cd_has_dream)
    val cdLucid = stringResource(R.string.dreams_lucid)
    val cd = listOfNotNull(
        date.dayOfMonth.toString(),
        cdToday.takeIf { isToday },
        cdDream.takeIf { row != null },
        cdLucid.takeIf { row?.dream?.lucid == true },
    ).joinToString(", ")

    Box(
        modifier = Modifier
            .height(MonthGridDefaults.CellHeight)
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics { contentDescription = cd },
        contentAlignment = Alignment.Center,
    ) {
        if (row != null) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .background(
                        if (row.dream.lucid) scheme.tertiaryContainer else scheme.secondaryContainer,
                        androidx.compose.foundation.shape.CircleShape,
                    ),
            )
            if (row.dream.lucid) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .border(
                            1.5.dp,
                            scheme.tertiary,
                            androidx.compose.foundation.shape.CircleShape,
                        ),
                )
            }
        }
        if (isToday && row == null) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .border(1.dp, scheme.primary, androidx.compose.foundation.shape.CircleShape),
            )
        }
        Text(
            date.dayOfMonth.toString(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (row != null) FontWeight.SemiBold else FontWeight.Normal,
            color = when {
                row?.dream?.lucid == true -> scheme.onTertiaryContainer
                row != null -> scheme.onSecondaryContainer
                else -> scheme.onSurfaceVariant
            },
        )
        if ((row?.audioCount ?: 0) > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 1.dp)
                    .size(4.dp)
                    .background(scheme.tertiary, androidx.compose.foundation.shape.CircleShape),
            )
        }
    }
}

/** YearMonth is not Parcelable; store it as "yyyy-MM" across process death. */
private val YearMonthSaver = androidx.compose.runtime.saveable.Saver<java.time.YearMonth, String>(
    save = { it.toString() },
    restore = { java.time.YearMonth.parse(it) },
)
