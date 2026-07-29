package com.douxev.eggshell.ui.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import com.douxev.eggshell.R
import com.douxev.eggshell.data.NotesRepository
import com.douxev.eggshell.ui.common.ScreenHeader
import com.douxev.eggshell.ui.components.ActionBand
import com.douxev.eggshell.ui.components.CardVariant
import com.douxev.eggshell.ui.components.EggCard
import com.douxev.eggshell.ui.components.EggFab
import com.douxev.eggshell.ui.components.EmptyState
import uniffi.transition.Note

@HiltViewModel
class NotesViewModel @Inject constructor(
    private val repo: NotesRepository,
    private val exporter: com.douxev.eggshell.data.NoteExporter,
) : ViewModel() {

    /**
     * Ids picked for a bulk action. Empty means ordinary browsing — the list
     * only turns into a selection surface once something is actually selected,
     * so a stray tap can never silently arm a delete.
     */
    private val _selected = MutableStateFlow<Set<Long>>(emptySet())
    val selected: StateFlow<Set<Long>> = _selected.asStateFlow()

    fun toggleSelection(id: Long) {
        _selected.value = _selected.value.let { if (id in it) it - id else it + id }
    }

    fun clearSelection() { _selected.value = emptySet() }

    fun deleteSelected() {
        val ids = _selected.value
        _selected.value = emptySet()
        viewModelScope.launch {
            ids.forEach { runCatching { repo.delete(it) } }
            refresh()
        }
    }

    /** Builds the zip and hands back the file for a share sheet. */
    fun exportSelected(onReady: (java.io.File) -> Unit) {
        val ids = _selected.value.toList().ifEmpty { return }
        viewModelScope.launch {
            runCatching { exporter.exportToCache(ids) }.getOrNull()?.let(onReady)
            _selected.value = emptySet()
        }
    }
    private val _notes = MutableStateFlow<List<Note>>(emptyList())
    val notes: StateFlow<List<Note>> = _notes.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _notes.value = runCatching { repo.list() }.getOrDefault(emptyList())
            _loading.value = false
        }
    }

    /**
     * Move locally first, then persist.
     *
     * A drag produces a stream of one-position moves; waiting for the database
     * between each would make the list stutter under the finger. The in-memory
     * order is the source of truth during the gesture and the write follows.
     */
    fun move(from: Int, to: Int) {
        val current = _notes.value.toMutableList()
        if (from !in current.indices || to !in current.indices) return
        current.add(to, current.removeAt(from))
        _notes.value = current
        viewModelScope.launch {
            runCatching { repo.reorder(current.map { it.id }) }
        }
    }
}

/**
 * Notes — a flat, hand-ordered list.
 *
 * The interaction is the decoy notes app's (long-press, drag, drop) because
 * that is what was asked for; the surface is Eggshell's, so it uses the same
 * ScreenHeader-inside-the-list skeleton as Journal and Photos rather than the
 * decoy's deliberately plain grid.
 */
@Composable
fun NotesScreen(
    onBack: () -> Unit,
    onOpenNote: (Long) -> Unit,
    onNewNote: () -> Unit,
    vm: NotesViewModel = hiltViewModel(),
) {
    val notes by vm.notes.collectAsState()
    val loading by vm.loading.collectAsState()
    val selected by vm.selected.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { vm.refresh() }

    // Back leaves selection mode before it leaves the screen — the same rule
    // every list with a selection mode follows.
    androidx.activity.compose.BackHandler(enabled = selected.isNotEmpty()) { vm.clearSelection() }

    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        // Offset by one: the header occupies index 0 of the same list.
        vm.move(from.index - 1, to.index - 1)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        bottomBar = {
            ActionBand {
                if (selected.isEmpty()) {
                    EggFab(
                        icon = Icons.Filled.Add,
                        contentDescription = stringResource(R.string.notes_add),
                        onClick = onNewNote,
                    )
                } else {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.TextButton(onClick = {
                            vm.exportSelected { file -> shareNotesZip(ctx, file) }
                        }) { Text(stringResource(R.string.notes_export)) }
                        androidx.compose.material3.TextButton(onClick = vm::deleteSelected) {
                            Text(
                                stringResource(R.string.action_delete),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "header") {
                ScreenHeader(title = stringResource(R.string.notes_title), onBack = onBack)
            }

            if (!loading && notes.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        message = stringResource(R.string.notes_empty),
                        actionLabel = stringResource(R.string.notes_add),
                        onAction = onNewNote,
                    )
                }
            }

            items(notes, key = { it.id }) { note ->
                ReorderableItem(reorderState, key = note.id) { dragging ->
                    NoteRow(
                        note = note,
                        dragging = dragging,
                        selected = note.id in selected,
                        onClick = {
                            if (selected.isEmpty()) onOpenNote(note.id)
                            else vm.toggleSelection(note.id)
                        },
                        onLongClick = { vm.toggleSelection(note.id) },
                        dragHandle = Modifier.draggableHandle(),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteRow(
    note: Note,
    dragging: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    dragHandle: Modifier,
) {
    EggCard(
        variant = if (selected) CardVariant.Primary else CardVariant.Low,
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    note.title.ifBlank { stringResource(R.string.notes_untitled) },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (note.body.isNotBlank()) {
                    Text(
                        note.body.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            Box(modifier = dragHandle.padding(start = 8.dp)) {
                Icon(
                    Icons.Filled.DragIndicator,
                    contentDescription = stringResource(R.string.notes_reorder_cd),
                    tint = if (dragging) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}


/**
 * Hand the zip to the system share sheet through our own FileProvider — the
 * only way another app is allowed to read a file out of our cache.
 */
private fun shareNotesZip(context: android.content.Context, file: java.io.File) {
    runCatching {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file,
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(android.content.Intent.createChooser(intent, null))
    }
}
