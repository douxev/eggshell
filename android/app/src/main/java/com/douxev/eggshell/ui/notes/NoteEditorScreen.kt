package com.douxev.eggshell.ui.notes

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
import com.douxev.eggshell.data.NotesRepository
import com.douxev.eggshell.ui.common.ScreenHeader
import com.douxev.eggshell.ui.components.ActionBand
import com.douxev.eggshell.ui.components.EggCard
import com.douxev.eggshell.ui.theme.EggShapes
import uniffi.transition.NoteImage

@HiltViewModel
class NoteEditorViewModel @Inject constructor(
    private val repo: NotesRepository,
) : ViewModel() {

    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title.asStateFlow()

    private val _body = MutableStateFlow("")
    val body: StateFlow<String> = _body.asStateFlow()

    private val _images = MutableStateFlow<List<Pair<NoteImage, ByteArray>>>(emptyList())
    val images: StateFlow<List<Pair<NoteImage, ByteArray>>> = _images.asStateFlow()

    /** Null until the note exists in the vault — a brand-new note has no id yet. */
    private var noteId: Long? = null

    fun load(id: Long?) {
        noteId = id
        if (id == null) return
        viewModelScope.launch {
            repo.get(id)?.let {
                _title.value = it.title
                _body.value = it.body
            }
            reloadImages()
        }
    }

    fun onTitle(v: String) { _title.value = v }
    fun onBody(v: String) { _body.value = v }

    /**
     * Attaching an image needs a row to attach to, so a note that has never
     * been saved is created first. Without this, picking an image on a new
     * note would silently do nothing.
     */
    fun attach(uri: android.net.Uri) {
        viewModelScope.launch {
            val id = noteId ?: runCatching { repo.create(_title.value, _body.value).id }
                .getOrNull()?.also { noteId = it } ?: return@launch
            runCatching { repo.attachImage(id, uri) }
            reloadImages()
        }
    }

    fun detach(image: NoteImage) {
        viewModelScope.launch {
            runCatching { repo.detachImage(image) }
            reloadImages()
        }
    }

    /** Returns true when there is something worth keeping. */
    suspend fun save(): Boolean {
        val hasContent = _title.value.isNotBlank() || _body.value.isNotBlank() || _images.value.isNotEmpty()
        val id = noteId
        return when {
            id != null -> {
                runCatching { repo.update(id, _title.value, _body.value) }.isSuccess
            }
            // Never persist an untouched blank note: opening the editor and
            // backing out should leave no trace in the list.
            hasContent -> runCatching { repo.create(_title.value, _body.value) }.isSuccess
            else -> true
        }
    }

    fun delete(onDone: () -> Unit) {
        val id = noteId
        viewModelScope.launch {
            if (id != null) runCatching { repo.delete(id) }
            onDone()
        }
    }

    val canDelete: Boolean get() = noteId != null

    private suspend fun reloadImages() {
        val id = noteId ?: return
        val rows = runCatching { repo.images(id) }.getOrDefault(emptyList())
        _images.value = rows.mapNotNull { img ->
            runCatching { img to repo.decrypt(img) }.getOrNull()
        }
    }
}

@Composable
fun NoteEditorScreen(
    noteId: Long?,
    onBack: () -> Unit,
    vm: NoteEditorViewModel = hiltViewModel(),
) {
    val title by vm.title.collectAsState()
    val body by vm.body.collectAsState()
    val images by vm.images.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(noteId) { vm.load(noteId) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { vm.attach(it) } }

    val leave = {
        scope.launch {
            vm.save()
            onBack()
        }
        Unit
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.notes_delete_title)) },
            text = { Text(stringResource(R.string.notes_delete_body)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.delete(onBack) }) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        bottomBar = {
            ActionBand {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = {
                        picker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }) {
                        Icon(Icons.Filled.AddPhotoAlternate, contentDescription = null)
                        Text(
                            stringResource(R.string.notes_add_image),
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                    if (vm.canDelete) {
                        TextButton(onClick = { confirmDelete = true }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ScreenHeader(
                    title = stringResource(R.string.notes_editor_title),
                    onBack = { leave() },
                )
            }
            item {
                OutlinedTextField(
                    value = title,
                    onValueChange = vm::onTitle,
                    label = { Text(stringResource(R.string.notes_field_title)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = body,
                    onValueChange = vm::onBody,
                    label = { Text(stringResource(R.string.notes_field_body)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 200.dp),
                )
            }
            items(images, key = { it.first.id }) { (img, bytes) ->
                NoteImageCard(bytes = bytes, onRemove = { vm.detach(img) })
            }
        }
    }
}

@Composable
private fun NoteImageCard(bytes: ByteArray, onRemove: () -> Unit) {
    val bitmap = remember(bytes) {
        runCatching { android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
            .getOrNull()
    }
    EggCard {
        Box(modifier = Modifier.fillMaxWidth()) {
            bitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = stringResource(R.string.notes_image_cd),
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(EggShapes.Card),
                )
            } ?: Text(
                stringResource(R.string.notes_image_unreadable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            IconButton(
                onClick = onRemove,
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.notes_remove_image),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
