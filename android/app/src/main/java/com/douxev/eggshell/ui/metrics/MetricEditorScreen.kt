package com.douxev.eggshell.ui.metrics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import com.douxev.eggshell.ui.common.metricLabel
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetricEditorScreen(
    onBack: () -> Unit,
    vm: MetricEditorViewModel = hiltViewModel(),
) {
    val defs by vm.defs.collectAsState()
    // null = closed; a definition = edit it; the sentinel below = add new.
    var editTarget by remember { mutableStateOf<MetricDefinition?>(null) }
    var adding by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<MetricDefinition?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.metric_editor_title)) },
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
        floatingActionButton = {
            FloatingActionButton(onClick = { adding = true }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.metric_editor_add))
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.metric_editor_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            defs.forEachIndexed { index, def ->
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
            Box(modifier = Modifier.height(80.dp))
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
                    Text(stringResource(R.string.action_delete))
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        ) {
            Text(metricLabel(def), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            IconButton(onClick = onMoveUp, enabled = !isFirst) {
                Icon(Icons.Filled.ArrowUpward, contentDescription = stringResource(R.string.metric_editor_move_up))
            }
            IconButton(onClick = onMoveDown, enabled = !isLast) {
                Icon(Icons.Filled.ArrowDownward, contentDescription = stringResource(R.string.metric_editor_move_down))
            }
            if (onEdit != null) {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.action_edit))
                }
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.action_delete),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
