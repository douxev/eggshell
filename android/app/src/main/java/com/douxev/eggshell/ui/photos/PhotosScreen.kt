package com.douxev.eggshell.ui.photos

import android.content.ContentValues
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
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
import com.douxev.eggshell.data.PhotosRepository
import uniffi.transition.PhotoRecord

@HiltViewModel
class PhotosViewModel @Inject constructor(
    private val repo: PhotosRepository,
) : ViewModel() {
    private val _items = MutableStateFlow<List<PhotoRecord>>(emptyList())
    val items: StateFlow<List<PhotoRecord>> = _items.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _items.value = runCatching { repo.list() }.getOrDefault(emptyList())
                .sortedByDescending { it.atMs }
        }
    }

    fun import(uri: android.net.Uri) {
        viewModelScope.launch {
            runCatching { repo.importFromUri(uri, null) }
            refresh()
        }
    }

    suspend fun decryptBytes(record: PhotoRecord): ByteArray? =
        runCatching { repo.decryptToBytes(record) }.getOrNull()

    suspend fun decryptToCache(record: PhotoRecord): java.io.File? =
        runCatching { repo.decryptToCache(record) }.getOrNull()

    fun delete(record: PhotoRecord) {
        viewModelScope.launch {
            runCatching { repo.delete(record) }
            refresh()
        }
    }
}

private enum class PhotoTab { Gallery, Compare }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotosScreen(
    onOpenSettings: () -> Unit = {},
    vm: PhotosViewModel = hiltViewModel(),
) {
    val items by vm.items.collectAsState()
    LaunchedEffect(Unit) { vm.refresh() }
    var tab by rememberSaveable { mutableStateOf(PhotoTab.Gallery) }
    var lightboxId by remember { mutableStateOf<Long?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val savedMsg = stringResource(R.string.photos_saved_to_gallery)

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { vm.import(it) } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    picker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Icon(
                    Icons.Filled.AddAPhoto,
                    contentDescription = stringResource(R.string.photos_add),
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            com.douxev.eggshell.ui.common.ScreenHeader(
                title = stringResource(R.string.photos_title),
                onOpenSettings = onOpenSettings,
                modifier = Modifier.padding(top = 2.dp),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = tab == PhotoTab.Gallery,
                    onClick = { tab = PhotoTab.Gallery },
                    label = { Text(stringResource(R.string.photos_tab_gallery)) },
                    shape = RoundedCornerShape(50),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                )
                FilterChip(
                    selected = tab == PhotoTab.Compare,
                    onClick = { tab = PhotoTab.Compare },
                    label = { Text(stringResource(R.string.photos_tab_compare)) },
                    shape = RoundedCornerShape(50),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                )
            }

            Box(modifier = Modifier
                .fillMaxSize()
                .padding(top = 16.dp)) {
                if (items.isEmpty()) {
                    EmptyState()
                } else when (tab) {
                    PhotoTab.Gallery -> GalleryGrid(items, vm, onOpen = { lightboxId = it.id })
                    PhotoTab.Compare -> CompareView(items, vm)
                }
            }
        }
    }

    val opened = items.firstOrNull { it.id == lightboxId }
    if (opened != null) {
        PhotoLightbox(
            record = opened,
            vm = vm,
            onClose = { lightboxId = null },
            onShare = {
                scope.launch {
                    val file = vm.decryptToCache(opened) ?: return@launch
                    val uri = FileProvider.getUriForFile(
                        ctx, "${ctx.packageName}.fileprovider", file,
                    )
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "image/jpeg"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    ctx.startActivity(Intent.createChooser(send, null))
                }
            },
            onSave = {
                scope.launch {
                    val bytes = vm.decryptBytes(opened) ?: return@launch
                    val saved = savePhotoToGallery(ctx, bytes, opened.atMs)
                    if (saved) snackbar.showSnackbar(savedMsg)
                }
            },
            onDelete = {
                vm.delete(opened)
                lightboxId = null
            },
        )
    }
}

private fun savePhotoToGallery(
    ctx: android.content.Context,
    bytes: ByteArray,
    atMs: Long,
): Boolean {
    val name = "transition-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(atMs))}.jpg"
    return runCatching {
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Transition")
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return@runCatching false
        resolver.openOutputStream(uri)?.use { it.write(bytes) }
        true
    }.getOrDefault(false)
}

@Composable
private fun EmptyState() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.PhotoLibrary,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Text(
                stringResource(R.string.photos_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GalleryGrid(
    items: List<PhotoRecord>,
    vm: PhotosViewModel,
    onOpen: (PhotoRecord) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 96.dp),
    ) {
        items(items, key = { it.id }) { record ->
            GalleryTile(record, vm, onClick = { onOpen(record) })
        }
    }
}

@Composable
private fun GalleryTile(record: PhotoRecord, vm: PhotosViewModel, onClick: () -> Unit) {
    val day = remember(record.atMs) {
        SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(record.atMs))
    }
    val month = remember(record.atMs) {
        SimpleDateFormat("MMM yy", Locale.getDefault()).format(Date(record.atMs)).uppercase()
    }
    Column(modifier = Modifier.clickable(onClick = onClick)) {
        PhotoThumb(
            record = record,
            vm = vm,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(18.dp)),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, top = 6.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(day, style = MaterialTheme.typography.titleSmall)
            Text(
                month,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun CompareView(items: List<PhotoRecord>, vm: PhotosViewModel) {
    // Pick oldest + newest as the default comparison.
    val first = items.lastOrNull()
    val last = items.firstOrNull()
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (first == null || last == null || first.id == last.id) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(R.string.photos_compare_not_enough),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(20.dp),
                )
            }
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ComparePane(record = first, vm = vm, label = stringResource(R.string.photos_compare_before),
                modifier = Modifier.weight(1f))
            ComparePane(record = last, vm = vm, label = stringResource(R.string.photos_compare_after),
                modifier = Modifier.weight(1f))
        }
        val months = monthsBetween(first.atMs, last.atMs)
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.photos_compare_span_fmt, months),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(R.string.photos_compare_span_sub),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                )
            }
        }
    }
}

@Composable
private fun ComparePane(record: PhotoRecord, vm: PhotosViewModel, label: String, modifier: Modifier) {
    val day = remember(record.atMs) {
        SimpleDateFormat("d MMM yy", Locale.getDefault()).format(Date(record.atMs))
    }
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        PhotoThumb(
            record = record,
            vm = vm,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(18.dp)),
        )
        Text(day, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        Box(
            modifier = Modifier
                .padding(top = 4.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal = 10.dp, vertical = 2.dp),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun PhotoThumb(record: PhotoRecord, vm: PhotosViewModel, modifier: Modifier) {
    var bitmap by remember(record.id) { mutableStateOf<android.graphics.Bitmap?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(record.id) {
        scope.launch {
            val bytes = vm.decryptBytes(record) ?: return@launch
            bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }
    val bg = MaterialTheme.colorScheme.surfaceContainerHigh
    Box(modifier = modifier.background(bg)) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = stringResource(R.string.photos_locked),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private fun monthsBetween(fromMs: Long, toMs: Long): Int {
    val days = ((toMs - fromMs) / 86_400_000L).toInt()
    return (days / 30).coerceAtLeast(0)
}

@Composable
private fun PhotoLightbox(
    record: PhotoRecord,
    vm: PhotosViewModel,
    onClose: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
) {
    var bitmap by remember(record.id) { mutableStateOf<android.graphics.Bitmap?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(record.id) {
        scope.launch {
            val bytes = vm.decryptBytes(record) ?: return@launch
            bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }
    val dateLabel = remember(record.atMs) {
        SimpleDateFormat("EEEE d MMMM yyyy", Locale.getDefault()).format(Date(record.atMs))
    }

    // Pinch-zoom + pan + double-tap-to-toggle state.
    var scale by remember(record.id) { mutableStateOf(1f) }
    var offsetX by remember(record.id) { mutableStateOf(0f) }
    var offsetY by remember(record.id) { mutableStateOf(0f) }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val newScale = (scale * zoomChange).coerceIn(1f, 5f)
        offsetX = (offsetX + panChange.x) * (if (newScale > 1f) 1f else 0f)
        offsetY = (offsetY + panChange.y) * (if (newScale > 1f) 1f else 0f)
        scale = newScale
        val maxOffset = 600f * (newScale - 1f).coerceAtLeast(0f)
        offsetX = offsetX.coerceIn(-maxOffset, maxOffset)
        offsetY = offsetY.coerceIn(-maxOffset, maxOffset)
    }

    // Resolve the actual top/bottom system insets via the local density. We
    // deliberately don't rely on `safeDrawingPadding` alone because Compose
    // Dialogs with `decorFitsSystemWindows = false` have reported zero insets
    // on several real devices, leaving the bottom action row tucked behind
    // the gesture handle. We take the max of the reported inset and a
    // hardcoded fallback so the row is always visible.
    val systemBarsPad = WindowInsets.systemBars.asPaddingValues()
    val topInset = systemBarsPad.calculateTopPadding().coerceAtLeast(24.dp)
    val bottomInset = systemBarsPad.calculateBottomPadding().coerceAtLeast(40.dp)

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        // Column layout, not Box+align: this guarantees the bottom action row
        // sits at the natural bottom of a finite Column, with the image area
        // expanding to fill the middle.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xF2000000)),
        ) {
            // Top bar: close + date
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = topInset)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color(0x66FFFFFF)),
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.action_back),
                        tint = Color.White,
                    )
                }
                Text(
                    dateLabel.replaceFirstChar {
                        if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
                    },
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 12.dp).weight(1f),
                )
            }

            // Image: takes all remaining vertical space between the bars.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .transformable(state = transformState)
                    .pointerInputDoubleTap {
                        if (scale > 1f) {
                            scale = 1f; offsetX = 0f; offsetY = 0f
                        } else {
                            scale = 2.5f
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                bitmap?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = stringResource(R.string.photos_locked),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp)
                            .androidx_graphicsLayer(scale, offsetX, offsetY),
                    )
                }
            }

            // Bottom action bar: explicit bottom padding equal to system nav
            // inset (or 40dp fallback). The Column layout above guarantees
            // this row is at the bottom of the parent, so the only thing
            // that can hide it is the gesture handle / nav bar.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = bottomInset)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                LightboxAction(
                    icon = Icons.Filled.Share,
                    label = stringResource(R.string.photos_share),
                    onClick = onShare,
                )
                LightboxAction(
                    icon = Icons.Filled.Download,
                    label = stringResource(R.string.photos_save),
                    onClick = onSave,
                )
                LightboxAction(
                    icon = Icons.Filled.Delete,
                    label = stringResource(R.string.action_delete),
                    onClick = onDelete,
                )
            }
        }
    }
}

private fun Modifier.androidx_graphicsLayer(
    scale: Float,
    offsetX: Float,
    offsetY: Float,
): Modifier = this.graphicsLayer(
    scaleX = scale,
    scaleY = scale,
    translationX = offsetX,
    translationY = offsetY,
)

private fun Modifier.pointerInputDoubleTap(onDoubleTap: () -> Unit): Modifier =
    this.pointerInput(Unit) {
        detectTapGestures(onDoubleTap = { onDoubleTap() })
    }

@Composable
private fun LightboxAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Color(0x33FFFFFF)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = Color.White)
        }
        Text(
            label,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
