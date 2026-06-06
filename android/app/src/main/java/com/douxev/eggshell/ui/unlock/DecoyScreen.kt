package com.douxev.eggshell.ui.unlock

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState

/**
 * Decoy "Notes" app — shown when the user types the decoy PIN at unlock.
 *
 * A real, working notes app (Material 3 staggered cards, pastel tints, FAB,
 * drag-to-reorder, search, overflow menu). Notes are PERSISTED in a plain
 * SharedPreferences file so the decoy behaves like a genuine notes app across
 * sessions — edits, reorders and additions survive a cold start. The stored
 * content is decoy-only (the user's own cover notes); it never touches the real
 * encrypted vault. On first launch it seeds a few everyday notes so the app
 * looks lived-in rather than empty.
 *
 * Overrides MaterialTheme with a neutral teal palette so it doesn't inherit the
 * main app's lavender tokens (which would tip off an observant snooper).
 */
@Composable
fun DecoyScreen() {
    MaterialTheme(
        colorScheme = DecoyColors,
        typography = MaterialTheme.typography,
    ) {
        Surface(color = MaterialTheme.colorScheme.background) {
            DecoyNotesApp()
        }
    }
}

/**
 * Neutral teal palette used by the decoy notes app — and also by the PIN gate
 * when a decoy PIN is configured, so the transition from lock-screen to
 * fake-notes is visually seamless (no lavender flash).
 */
internal val DecoyColors = lightColorScheme(
    primary = Color(0xFF006A6A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB2ECEC),
    onPrimaryContainer = Color(0xFF002020),
    secondary = Color(0xFF4A6363),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCE8E8),
    onSecondaryContainer = Color(0xFF051F1F),
    background = Color(0xFFFAFDFC),
    onBackground = Color(0xFF191C1C),
    surface = Color(0xFFFAFDFC),
    onSurface = Color(0xFF191C1C),
    surfaceVariant = Color(0xFFDAE5E4),
    onSurfaceVariant = Color(0xFF3F4948),
    surfaceContainer = Color(0xFFEFF2F1),
    surfaceContainerHigh = Color(0xFFE9EDEC),
    outlineVariant = Color(0xFFBEC9C8),
)

private data class DecoyNote(
    val id: Long,
    val title: String,
    val body: String,
    val tintIndex: Int,
)

private val PastelTints = listOf(
    Color(0xFFFFF1B8),
    Color(0xFFC8E6C9),
    Color(0xFFFFCDD2),
    Color(0xFFB3E5FC),
    Color(0xFFE1BEE7),
    Color(0xFFFFE0B2),
)

private fun DecoyNote.tint(): Color = PastelTints[tintIndex.coerceIn(PastelTints.indices)]

// --- Persistence (plain prefs; decoy content only, never the real vault) -----

private object DecoyNotesStore {
    private const val PREFS = "decoy_notes"
    private const val KEY = "notes"

    /** Returns null if the user has never saved (→ caller seeds). */
    fun load(ctx: Context): List<DecoyNote>? {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return null
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                DecoyNote(
                    id = o.getLong("id"),
                    title = o.getString("title"),
                    body = o.getString("body"),
                    tintIndex = o.optInt("tint", 0),
                )
            }
        }.getOrNull()
    }

    fun save(ctx: Context, notes: List<DecoyNote>) {
        val arr = JSONArray()
        notes.forEach { n ->
            arr.put(
                JSONObject()
                    .put("id", n.id)
                    .put("title", n.title)
                    .put("body", n.body)
                    .put("tint", n.tintIndex)
            )
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, arr.toString()).apply()
    }

    /** Wipe the decoy notes (called from the app's full wipe). */
    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}

private fun seedNotes(): List<DecoyNote> = listOf(
    DecoyNote(1, "Courses",
        "Pain complet\nLait d'avoine\nŒufs\nCafé\nTomates cerises\nFromage râpé", 0),
    DecoyNote(2, "À faire ce week-end",
        "Appeler Marie\nRanger le placard de l'entrée\nFinir le livre commencé lundi\nLancer une lessive", 1),
    DecoyNote(3, "Idées vacances",
        "Lisbonne en septembre ?\nRegarder vols depuis Lyon\nDemander à Léa si dispo", 3),
    DecoyNote(4, "Recette tarte tatin",
        "Pâte brisée\n6 pommes (Reinette)\n100g sucre roux\n50g beurre\nUne pincée de cannelle", 5),
    DecoyNote(5, "Films à voir",
        "Past Lives\nThe Holdovers\nAnatomie d'une chute", 4),
)

private enum class Sort { Natural, Title, Length }

@Composable
private fun DecoyNotesApp() {
    val ctx = LocalContext.current
    val notes = remember {
        val loaded = DecoyNotesStore.load(ctx)
        mutableStateListOf(*(loaded ?: seedNotes()).toTypedArray())
    }
    // Persist whenever the list (content or order) changes.
    LaunchedEffect(notes) {
        snapshotFlow { notes.toList() }.collect { DecoyNotesStore.save(ctx, it) }
    }
    // Next id strictly above any existing one, so additions never collide.
    val nextId = remember { mutableStateOf((notes.maxOfOrNull { it.id } ?: 0L) + 1L) }
    fun freshId(): Long { val v = nextId.value; nextId.value = v + 1; return v }

    var editingIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }

    when {
        editingIndex != null -> EditNote(
            note = notes[editingIndex!!],
            onSave = { updated ->
                notes[editingIndex!!] = updated
                editingIndex = null
            },
            onDelete = {
                notes.removeAt(editingIndex!!)
                editingIndex = null
            },
            onBack = { editingIndex = null },
        )
        creating -> EditNote(
            note = DecoyNote(freshId(), "", "", PastelTints.indices.random()),
            onSave = { updated ->
                notes.add(0, updated)
                creating = false
            },
            onDelete = { creating = false },
            onBack = { creating = false },
        )
        else -> NoteList(
            notes = notes,
            onOpen = { idx -> editingIndex = idx },
            onAdd = { creating = true },
            onSwap = { from, to ->
                androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
                    val item = notes.removeAt(from)
                    notes.add(to, item)
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteList(
    notes: List<DecoyNote>,
    onOpen: (Int) -> Unit,
    onAdd: () -> Unit,
    onSwap: (from: Int, to: Int) -> Unit,
) {
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(Sort.Natural) }
    var menuOpen by remember { mutableStateOf(false) }
    var aboutOpen by rememberSaveable { mutableStateOf(false) }

    val visible: List<Pair<Int, DecoyNote>> by remember(notes, query, sort) {
        derivedStateOf {
            val indexed = notes.mapIndexedNotNull { idx, note ->
                if (query.isBlank() ||
                    note.title.contains(query, ignoreCase = true) ||
                    note.body.contains(query, ignoreCase = true)
                ) idx to note else null
            }
            when (sort) {
                Sort.Natural -> indexed
                Sort.Title -> indexed.sortedBy { it.second.title.lowercase() }
                Sort.Length -> indexed.sortedByDescending { it.second.body.length }
            }
        }
    }

    val canReorder = query.isBlank() && sort == Sort.Natural
    val gridState = rememberLazyGridState()
    val reorderState = rememberReorderableLazyGridState(gridState) { from, to ->
        val fromIdx = notes.indexOfFirst { it.id == from.key as Long }
        val toIdx = notes.indexOfFirst { it.id == to.key as Long }
        if (fromIdx >= 0 && toIdx >= 0) onSwap(fromIdx, toIdx)
    }

    BackHandler(enabled = searchOpen) {
        searchOpen = false
        query = ""
    }

    Scaffold(
        topBar = {
            if (searchOpen) {
                SearchBar(
                    query = query,
                    onQueryChange = { query = it },
                    onClose = { searchOpen = false; query = "" },
                )
            } else {
                TopAppBar(
                    title = { Text("Notes", style = MaterialTheme.typography.titleLarge) },
                    actions = {
                        IconButton(onClick = { searchOpen = true }) {
                            Icon(Icons.Filled.Search, contentDescription = "Rechercher")
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "Menu")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Trier · ordre manuel") },
                                    onClick = { sort = Sort.Natural; menuOpen = false },
                                    trailingIcon = if (sort == Sort.Natural)
                                        { { Icon(Icons.Filled.MoreVert, contentDescription = null) } }
                                    else null,
                                )
                                DropdownMenuItem(
                                    text = { Text("Trier · titre A-Z") },
                                    onClick = { sort = Sort.Title; menuOpen = false },
                                )
                                DropdownMenuItem(
                                    text = { Text("Trier · plus longues") },
                                    onClick = { sort = Sort.Length; menuOpen = false },
                                )
                                DropdownMenuItem(
                                    text = { Text("À propos") },
                                    onClick = { aboutOpen = true; menuOpen = false },
                                )
                            }
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) {
                Icon(Icons.Filled.Add, contentDescription = "Nouvelle note")
            }
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            state = gridState,
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 90.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
        ) {
            itemsIndexed(visible, key = { _, pair -> pair.second.id }) { _, (origIdx, note) ->
                ReorderableItem(reorderState, key = note.id) { dragging ->
                    val handle = if (canReorder) {
                        Modifier.longPressDraggableHandle()
                    } else Modifier
                    NoteCard(
                        note = note,
                        elevated = dragging,
                        onClick = { onOpen(origIdx) },
                        modifier = handle,
                    )
                }
            }
        }

        if (visible.isEmpty() && query.isNotBlank()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                Text("Aucun résultat", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (aboutOpen) {
        AlertDialog(
            onDismissRequest = { aboutOpen = false },
            confirmButton = { TextButton(onClick = { aboutOpen = false }) { Text("OK") } },
            title = { Text("Notes") },
            text = {
                Column {
                    Text("Version 1.4.2", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Application de prise de notes simple et rapide. " +
                            "Glisse une note avec un appui long pour la réordonner.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    TopAppBar(
        title = {
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                textStyle = TextStyle(fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
                decorationBox = { inner ->
                    if (query.isEmpty()) Text(
                        "Rechercher",
                        style = TextStyle(fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant),
                    )
                    inner()
                },
            )
        },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Fermer la recherche")
            }
        },
        actions = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = "Effacer")
                }
            }
        },
    )
}

@Composable
private fun NoteCard(
    note: DecoyNote,
    elevated: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = note.tint(),
        contentColor = Color(0xFF1A1A1A),
        tonalElevation = if (elevated) 8.dp else 0.dp,
        shadowElevation = if (elevated) 8.dp else 0.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (note.title.isNotBlank()) {
                Text(
                    note.title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.W700),
                    maxLines = 2,
                )
            }
            Text(note.body, style = MaterialTheme.typography.bodySmall, maxLines = 8)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditNote(
    note: DecoyNote,
    onSave: (DecoyNote) -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
) {
    var title by rememberSaveable { mutableStateOf(note.title) }
    var body by rememberSaveable { mutableStateOf(note.body) }
    var menuOpen by remember { mutableStateOf(false) }

    BackHandler { onSave(note.copy(title = title, body = body)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = {
                    IconButton(onClick = { onSave(note.copy(title = title, body = body)) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = null)
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Supprimer") },
                                onClick = { menuOpen = false; onDelete() },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(note.tint())
                .padding(padding)
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BasicTextField(
                value = title,
                onValueChange = { title = it },
                textStyle = TextStyle(
                    fontSize = 22.sp,
                    fontWeight = FontWeight.W700,
                    color = Color(0xFF1A1A1A),
                ),
                cursorBrush = SolidColor(Color(0xFF1A1A1A)),
                decorationBox = { inner ->
                    if (title.isEmpty()) Text(
                        "Titre",
                        style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.W700),
                        color = Color(0xFF1A1A1A).copy(alpha = 0.4f),
                    )
                    inner()
                },
                modifier = Modifier.fillMaxWidth(),
            )
            BasicTextField(
                value = body,
                onValueChange = { body = it },
                textStyle = TextStyle(fontSize = 16.sp, color = Color(0xFF1A1A1A)),
                cursorBrush = SolidColor(Color(0xFF1A1A1A)),
                decorationBox = { inner ->
                    if (body.isEmpty()) Text(
                        "Note",
                        style = TextStyle(fontSize = 16.sp),
                        color = Color(0xFF1A1A1A).copy(alpha = 0.4f),
                    )
                    inner()
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
