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
import com.douxev.eggshell.ui.components.EggCard
import com.douxev.eggshell.ui.components.EggFab
import com.douxev.eggshell.ui.components.EmptyState
import uniffi.transition.Note

@HiltViewModel
class NotesViewModel @Inject constructor(
    private val repo: NotesRepository,
) : ViewModel() {
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
    LaunchedEffect(Unit) { vm.refresh() }

    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        // Offset by one: the header occupies index 0 of the same list.
        vm.move(from.index - 1, to.index - 1)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        bottomBar = {
            ActionBand {
                EggFab(
                    icon = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.notes_add),
                    onClick = onNewNote,
                )
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
                        onClick = { onOpenNote(note.id) },
                        dragHandle = Modifier.draggableHandle(),
                    )
                }
            }
        }
    }
}

@Composable
private fun NoteRow(
    note: Note,
    dragging: Boolean,
    onClick: () -> Unit,
    dragHandle: Modifier,
) {
    EggCard(onClick = onClick) {
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
