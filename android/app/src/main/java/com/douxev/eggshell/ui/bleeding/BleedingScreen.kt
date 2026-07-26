package com.douxev.eggshell.ui.bleeding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Icon
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
import com.douxev.eggshell.ui.common.ScreenHeader
import com.douxev.eggshell.ui.components.ActionBand
import com.douxev.eggshell.ui.components.CardVariant
import com.douxev.eggshell.ui.components.EggCard
import com.douxev.eggshell.ui.components.EggFab
import com.douxev.eggshell.ui.components.EmptyState
import com.douxev.eggshell.ui.components.IconTile
import com.douxev.eggshell.ui.components.ListRow
import com.douxev.eggshell.ui.components.SkeletonBlock
import com.douxev.eggshell.ui.components.StatusPill
import com.douxev.eggshell.ui.theme.EggDim
import uniffi.transition.BleedingEntry

@HiltViewModel
class BleedingListViewModel @Inject constructor(
    private val repo: BleedingRepository,
) : ViewModel() {
    private val _items = MutableStateFlow<List<BleedingEntry>>(emptyList())
    val items: StateFlow<List<BleedingEntry>> = _items.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            runCatching { repo.list(0, 200) }.onSuccess { _items.value = it }
            _loading.value = false
        }
    }
}

/**
 * The Menstruations content, as list items.
 *
 * Two doors lead here — the launcher tile and the `Menstruations` segment of
 * Ressenti — and they must show the same thing, so the content lives in one
 * place and each door supplies its own header and action band.
 *
 * No cycle prediction, ever: this records what happened, it never guesses what
 * comes next.
 */
fun LazyListScope.bleedingSegment(
    items: List<BleedingEntry>,
    loading: Boolean,
    onAdd: () -> Unit,
    onEdit: (Long) -> Unit,
    onCustomize: () -> Unit,
) {
    item(key = "bleeding-customize") {
        ListRow(
            title = stringResource(R.string.metric_editor_open),
            subtitle = stringResource(R.string.bleeding_hint),
            leading = {
                IconTile(
                    container = MaterialTheme.colorScheme.tertiaryContainer,
                ) {
                    Icon(
                        Icons.Filled.Tune,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(22.dp),
                    )
                }
            },
            onClick = onCustomize,
        )
    }

    when {
        loading && items.isEmpty() -> item(key = "bleeding-skeleton") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(3) { SkeletonBlock(height = 76.dp) }
            }
        }
        items.isEmpty() -> item(key = "bleeding-empty") {
            EmptyState(
                message = stringResource(R.string.bleeding_empty),
                actionLabel = stringResource(R.string.feel_empty_bleeding_action),
                onAction = onAdd,
            )
        }
        else -> items(items, key = { "bleed-${it.id}" }) { entry ->
            BleedingCard(entry = entry, onClick = { onEdit(entry.id) })
        }
    }
}

@Composable
fun BleedingScreen(
    onAdd: () -> Unit,
    onEdit: (Long) -> Unit,
    onCustomize: () -> Unit,
    onBack: () -> Unit = {},
    vm: BleedingListViewModel = hiltViewModel(),
) {
    val items by vm.items.collectAsState()
    val loading by vm.loading.collectAsState()
    LaunchedEffect(Unit) { vm.refresh() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        bottomBar = {
            ActionBand {
                EggFab(
                    icon = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.feel_fab_bleeding),
                    onClick = onAdd,
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
                bottom = 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "bleeding-header") {
                ScreenHeader(
                    title = stringResource(R.string.bleeding_title),
                    onBack = onBack,
                )
            }
            bleedingSegment(
                items = items,
                loading = loading,
                onAdd = onAdd,
                onEdit = onEdit,
                onCustomize = onCustomize,
            )
        }
    }
}

@Composable
private fun BleedingCard(entry: BleedingEntry, onClick: () -> Unit) {
    val dateFmt = remember { SimpleDateFormat("EEEE d MMMM", Locale.getDefault()) }
    val kind = when (entry.isSpotting) {
        true -> stringResource(R.string.bleeding_kind_spotting)
        false -> stringResource(R.string.bleeding_kind_bleed)
        null -> stringResource(R.string.bleeding_kind_unspecified)
    }
    // A full bleed carries the error container, spotting and « non précisé »
    // stay neutral — and the word is always spelled out next to the colour.
    val pillContainer = if (entry.isSpotting == false) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val pillContent = if (entry.isSpotting == false) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    EggCard(
        variant = CardVariant.Low,
        padding = PaddingValues(16.dp),
        onClick = onClick,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(container = MaterialTheme.colorScheme.errorContainer) {
                Icon(
                    Icons.Filled.WaterDrop,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    dateFmt.format(Date(entry.atMs))
                        .replaceFirstChar { it.titlecase(Locale.getDefault()) },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                entry.freeText?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            StatusPill(label = kind, container = pillContainer, content = pillContent)
        }
    }
}
