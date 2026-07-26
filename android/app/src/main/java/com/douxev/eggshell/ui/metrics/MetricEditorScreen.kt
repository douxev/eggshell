package com.douxev.eggshell.ui.metrics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.douxev.eggshell.R
import com.douxev.eggshell.data.MetricsRepository
import com.douxev.eggshell.ui.common.ScreenHeader
import com.douxev.eggshell.ui.common.metricEmojis
import com.douxev.eggshell.ui.common.metricLabel
import com.douxev.eggshell.ui.components.ActionBand
import com.douxev.eggshell.ui.components.CardVariant
import com.douxev.eggshell.ui.components.EggCard
import com.douxev.eggshell.ui.components.EggFab
import com.douxev.eggshell.ui.components.IconTile
import com.douxev.eggshell.ui.components.TypeBadge
import com.douxev.eggshell.ui.theme.EggDim
import uniffi.transition.MetricDefinition
import uniffi.transition.MetricDefinitionUpdate
import uniffi.transition.NewMetricDefinition

@HiltViewModel
class MetricEditorViewModel @Inject constructor(
    private val metrics: MetricsRepository,
    state: SavedStateHandle,
) : ViewModel() {
    val domain: String = state.get<String>("domain") ?: MetricsRepository.DOMAIN_JOURNAL

    private val _defs = MutableStateFlow<List<MetricDefinition>>(emptyList())
    val defs: StateFlow<List<MetricDefinition>> = _defs.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            runCatching { metrics.definitions(domain, includeArchived = false) }
                .onSuccess { _defs.value = it }
        }
    }

    fun setEnabled(def: MetricDefinition, enabled: Boolean) = applyUpdate(def.copy(enabled = enabled))

    fun edit(def: MetricDefinition, label: String, left: String?, right: String?) =
        applyUpdate(def.copy(label = label, emojiLeft = left, emojiRight = right))

    /** Move a definition up (-1) or down (+1) by swapping sort_order with its
     *  neighbour, then persisting both. */
    fun move(index: Int, delta: Int) {
        val list = _defs.value
        val other = index + delta
        if (index !in list.indices || other !in list.indices) return
        val a = list[index]
        val b = list[other]
        viewModelScope.launch {
            runCatching {
                metrics.updateDefinition(a.id, a.copy(sortOrder = b.sortOrder).toUpdate())
                metrics.updateDefinition(b.id, b.copy(sortOrder = a.sortOrder).toUpdate())
            }
            refresh()
        }
    }

    fun add(label: String, left: String?, right: String?) {
        val nextOrder = (_defs.value.maxOfOrNull { it.sortOrder } ?: -1L) + 1L
        viewModelScope.launch {
            runCatching {
                metrics.addDefinition(
                    NewMetricDefinition(
                        domain = domain,
                        metricKey = "custom_${System.currentTimeMillis()}",
                        label = label,
                        emojiLeft = left,
                        emojiRight = right,
                        minValue = 0u,
                        maxValue = 10u,
                        sortOrder = nextOrder,
                        createdAtMs = System.currentTimeMillis(),
                    )
                )
            }
            refresh()
        }
    }

    fun delete(def: MetricDefinition) {
        viewModelScope.launch {
            runCatching { metrics.archiveDefinition(def.id) }
            refresh()
        }
    }

    private fun applyUpdate(def: MetricDefinition) {
        viewModelScope.launch {
            runCatching { metrics.updateDefinition(def.id, def.toUpdate()) }
            refresh()
        }
    }
}

private fun MetricDefinition.toUpdate() = MetricDefinitionUpdate(
    label = label,
    emojiLeft = emojiLeft,
    emojiRight = emojiRight,
    sortOrder = sortOrder,
    enabled = enabled,
)

/**
 * « Personnaliser les indicateurs » — the one place a slider is hidden,
 * reordered, renamed or created.
 *
 * Hiding never destroys anything: an entry keeps the value it was given, the
 * form simply stops asking for it.
 */
@Composable
fun MetricEditorScreen(
    onBack: () -> Unit,
    vm: MetricEditorViewModel = hiltViewModel(),
) {
    val defs by vm.defs.collectAsState()
    var editTarget by remember { mutableStateOf<MetricDefinition?>(null) }
    var adding by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<MetricDefinition?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        bottomBar = {
            ActionBand {
                EggFab(
                    icon = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.metric_editor_add),
                    onClick = { adding = true },
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
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "metric-header") {
                ScreenHeader(
                    title = stringResource(R.string.metric_editor_title),
                    onBack = onBack,
                )
            }
            item(key = "metric-hint") {
                Text(
                    stringResource(
                        if (vm.domain == MetricsRepository.DOMAIN_BLEEDING) {
                            R.string.feel_metric_domain_bleeding
                        } else {
                            R.string.feel_metric_domain_journal
                        },
                    ) + " " + stringResource(R.string.metric_editor_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            itemsIndexed(defs, key = { _, def -> "metric-${def.id}" }) { index, def ->
                MetricRow(
                    def = def,
                    isFirst = index == 0,
                    isLast = index == defs.lastIndex,
                    onToggle = { vm.setEnabled(def, it) },
                    onMoveUp = { vm.move(index, -1) },
                    onMoveDown = { vm.move(index, +1) },
                    onEdit = { editTarget = def }.takeIf { !def.builtin },
                    onDelete = { confirmDelete = def }.takeIf { !def.builtin },
                )
            }
        }
    }

    if (adding) {
        MetricDialog(
            initial = null,
            onDismiss = { adding = false },
            onSave = { label, l, r -> vm.add(label, l, r); adding = false },
        )
    }
    editTarget?.let { target ->
        MetricDialog(
            initial = target,
            onDismiss = { editTarget = null },
            onSave = { label, l, r -> vm.edit(target, label, l, r); editTarget = null },
        )
    }
    confirmDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.metric_editor_delete_title)) },
            text = { Text(stringResource(R.string.metric_editor_delete_body, target.label)) },
            confirmButton = {
                TextButton(onClick = { vm.delete(target); confirmDelete = null }) {
                    Text(
                        stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun MetricRow(
    def: MetricDefinition,
    isFirst: Boolean,
    isLast: Boolean,
    onToggle: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    val (left, right) = metricEmojis(def)
    EggCard(variant = CardVariant.Low, padding = PaddingValues(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            IconTile(size = 40.dp) {
                Text(
                    listOfNotNull(left, right).joinToString(""),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        metricLabel(def),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    // The word says it too, never the switch position alone.
                    if (!def.enabled) TypeBadge(stringResource(R.string.feel_metric_hidden))
                }
            }
            IconButton(
                onClick = onMoveUp,
                enabled = !isFirst,
                modifier = Modifier.size(EggDim.TouchTarget),
            ) {
                Icon(
                    Icons.Filled.ArrowUpward,
                    contentDescription = stringResource(R.string.metric_editor_move_up),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = onMoveDown,
                enabled = !isLast,
                modifier = Modifier.size(EggDim.TouchTarget),
            ) {
                Icon(
                    Icons.Filled.ArrowDownward,
                    contentDescription = stringResource(R.string.metric_editor_move_down),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (onEdit != null) {
                IconButton(onClick = onEdit, modifier = Modifier.size(EggDim.TouchTarget)) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = stringResource(R.string.action_edit),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete, modifier = Modifier.size(EggDim.TouchTarget)) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.action_delete),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Switch(checked = def.enabled, onCheckedChange = onToggle)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MetricDialog(
    initial: MetricDefinition?,
    onDismiss: () -> Unit,
    onSave: (label: String, left: String?, right: String?) -> Unit,
) {
    var label by rememberSaveable { mutableStateOf(initial?.label.orEmpty()) }
    var left by rememberSaveable { mutableStateOf(initial?.emojiLeft.orEmpty()) }
    var right by rememberSaveable { mutableStateOf(initial?.emojiRight.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (initial == null) R.string.metric_editor_add_title
                    else R.string.metric_editor_edit_title
                )
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it.take(40) },
                    label = { Text(stringResource(R.string.metric_editor_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = left,
                        onValueChange = { left = it.take(4) },
                        label = { Text(stringResource(R.string.metric_editor_emoji_left)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = right,
                        onValueChange = { right = it.take(4) },
                        label = { Text(stringResource(R.string.metric_editor_emoji_right)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(label.trim(), left.ifBlank { null }, right.ifBlank { null }) },
                enabled = label.isNotBlank(),
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
