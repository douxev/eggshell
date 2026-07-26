package com.douxev.eggshell.ui.photos

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.MediaStore
import android.view.WindowManager
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.douxev.eggshell.R
import com.douxev.eggshell.data.PhotosRepository
import com.douxev.eggshell.ui.common.PrivacyNote
import com.douxev.eggshell.ui.common.ScreenHeader
import com.douxev.eggshell.ui.components.ActionBand
import com.douxev.eggshell.ui.components.CardVariant
import com.douxev.eggshell.ui.components.EggCard
import com.douxev.eggshell.ui.components.EggFab
import com.douxev.eggshell.ui.components.EmptyState
import com.douxev.eggshell.ui.components.MicroLabel
import com.douxev.eggshell.ui.components.SectionTitle
import com.douxev.eggshell.ui.components.Segmented
import com.douxev.eggshell.ui.components.SkeletonBlock
import com.douxev.eggshell.ui.theme.EggDim
import com.douxev.eggshell.ui.theme.EggShapes
import uniffi.transition.PhotoRecord

@HiltViewModel
class PhotosViewModel @Inject constructor(
    private val repo: PhotosRepository,
) : ViewModel() {
    private val _items = MutableStateFlow<List<PhotoRecord>>(emptyList())
    val items: StateFlow<List<PhotoRecord>> = _items.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _items.value = runCatching { repo.list() }.getOrDefault(emptyList())
                .sortedByDescending { it.atMs }
            _loading.value = false
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

/**
 * Photos (§6.10). Two ways in: the whole library as a grid, or the two ends of
 * it side by side. Compare is where the point of the module lives — a single
 * shot says nothing, the pair does — so it gets the span card that spells the
 * distance out in months.
 *
 * `FLAG_SECURE` is forced for as long as this screen is on top, whatever the
 * global screenshot preference says: the privacy note promises exactly that,
 * and a promise made in a string has to be true in the window flags.
 */
@Composable
fun PhotosScreen(
    onBack: () -> Unit = {},
    vm: PhotosViewModel = hiltViewModel(),
) {
    val items by vm.items.collectAsState()
    val loading by vm.loading.collectAsState()
    LaunchedEffect(Unit) { vm.refresh() }

    var tab by rememberSaveable { mutableStateOf(PhotoTab.Gallery) }
    var lightboxId by rememberSaveable { mutableStateOf<Long?>(null) }
    var pendingDelete by remember { mutableStateOf<PhotoRecord?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val savedMsg = stringResource(R.string.photos_saved_to_gallery)
    val saveFailedMsg = stringResource(R.string.media_photos_save_failed)
    val deletedMsg = stringResource(R.string.media_photos_deleted)

    ForceSecureWindow()

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { vm.import(it) } }
    val pick = {
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    val tabs = listOf(
        stringResource(R.string.photos_tab_gallery),
        stringResource(R.string.photos_tab_compare),
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            ActionBand {
                EggFab(
                    icon = Icons.Filled.AddAPhoto,
                    contentDescription = stringResource(R.string.media_photos_add),
                    onClick = { pick() },
                )
            }
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(
                start = EggDim.ScreenMargin,
                end = EggDim.ScreenMargin,
                bottom = 12.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val full = GridItemSpan(2)

            item(key = "photos-header", span = { full }) {
                ScreenHeader(title = stringResource(R.string.module_photos), onBack = onBack)
            }
            item(key = "photos-tabs", span = { full }) {
                Segmented(
                    options = tabs,
                    selectedIndex = if (tab == PhotoTab.Gallery) 0 else 1,
                    onSelect = { tab = if (it == 0) PhotoTab.Gallery else PhotoTab.Compare },
                )
            }

            when {
                loading && items.isEmpty() -> {
                    items(4, key = { "photos-skeleton-$it" }) {
                        SkeletonBlock(
                            height = 200.dp,
                            shape = RoundedCornerShape(16.dp),
                        )
                    }
                }

                tab == PhotoTab.Gallery -> {
                    if (items.isEmpty()) {
                        item(key = "photos-empty", span = { full }) {
                            EmptyState(
                                message = stringResource(R.string.media_photos_empty),
                                actionLabel = stringResource(R.string.media_photos_add),
                                onAction = { pick() },
                            )
                        }
                    } else {
                        item(key = "photos-section", span = { full }) {
                            SectionTitle(text = stringResource(R.string.media_photos_section))
                        }
                        items(items, key = { "photo-${it.id}" }) { record ->
                            GalleryTile(
                                record = record,
                                vm = vm,
                                onClick = { lightboxId = record.id },
                            )
                        }
                    }
                }

                else -> {
                    val oldest = items.lastOrNull()
                    val newest = items.firstOrNull()
                    if (oldest == null || newest == null || oldest.id == newest.id) {
                        item(key = "photos-compare-empty", span = { full }) {
                            EmptyState(
                                message = stringResource(R.string.media_photos_compare_empty),
                                actionLabel = stringResource(R.string.media_photos_add),
                                onAction = { pick() },
                            )
                        }
                    } else {
                        item(key = "photos-compare", span = { full }) {
                            ComparePair(
                                oldest = oldest,
                                newest = newest,
                                vm = vm,
                                onOpen = { lightboxId = it.id },
                            )
                        }
                        item(key = "photos-span", span = { full }) {
                            SpanCard(oldest = oldest, newest = newest, total = items.size)
                        }
                    }
                }
            }

            item(key = "photos-privacy", span = { full }) {
                PrivacyNote(
                    text = stringResource(R.string.media_photos_privacy),
                    icon = Icons.Filled.VisibilityOff,
                )
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
                    val bytes = vm.decryptBytes(opened)
                    val saved = bytes != null && savePhotoToGallery(ctx, bytes, opened.atMs)
                    snackbar.showSnackbar(if (saved) savedMsg else saveFailedMsg)
                }
            },
            onDelete = { pendingDelete = opened },
        )
    }

    // §5.4: every destructive action goes through an AlertDialog. Deleting the
    // photo is the one thing on this screen the vault cannot undo.
    val doomed = pendingDelete
    if (doomed != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.media_photos_delete_title)) },
            text = { Text(stringResource(R.string.media_photos_delete_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.delete(doomed)
                        pendingDelete = null
                        lightboxId = null
                        scope.launch { snackbar.showSnackbar(deletedMsg) }
                    },
                ) {
                    Text(
                        stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/**
 * Holds `FLAG_SECURE` on the activity window while this screen is composed, and
 * only drops it on the way out if we were the ones who set it — the global
 * screenshot preference owns the flag the rest of the time.
 */
@Composable
private fun ForceSecureWindow() {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity) {
        val window = activity?.window
        val alreadySecure = window != null &&
            (window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            if (window != null && !alreadySecure) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun savePhotoToGallery(
    ctx: Context,
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
private fun GalleryTile(record: PhotoRecord, vm: PhotosViewModel, onClick: () -> Unit) {
    val day = remember(record.atMs) {
        SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(record.atMs))
    }
    val year = remember(record.atMs) {
        SimpleDateFormat("yyyy", Locale.getDefault()).format(Date(record.atMs))
    }
    val longDate = remember(record.atMs) {
        SimpleDateFormat("d MMMM yyyy", Locale.getDefault()).format(Date(record.atMs))
    }
    Column(
        modifier = Modifier.clickable(
            onClickLabel = stringResource(R.string.media_photos_open, longDate),
            onClick = onClick,
        ),
    ) {
        PhotoThumb(
            record = record,
            vm = vm,
            contentDescription = stringResource(R.string.media_photos_photo, longDate),
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(16.dp)),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, top = 6.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                day,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            MicroLabel(year)
        }
    }
}

/**
 * The two ends of the library, 3:4 and side by side. Pairing is automatic —
 * oldest on the left, newest on the right — and the newest caption carries
 * `primary` because that column is the one the user came to look at.
 */
@Composable
private fun ComparePair(
    oldest: PhotoRecord,
    newest: PhotoRecord,
    vm: PhotosViewModel,
    onOpen: (PhotoRecord) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ComparePane(
            record = oldest,
            vm = vm,
            caption = stringResource(R.string.media_photos_before, monthYear(oldest.atMs)),
            captionColor = MaterialTheme.colorScheme.onSurfaceVariant,
            onOpen = { onOpen(oldest) },
            modifier = Modifier.weight(1f),
        )
        ComparePane(
            record = newest,
            vm = vm,
            caption = stringResource(R.string.media_photos_now, monthYear(newest.atMs)),
            captionColor = MaterialTheme.colorScheme.primary,
            onOpen = { onOpen(newest) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ComparePane(
    record: PhotoRecord,
    vm: PhotosViewModel,
    caption: String,
    captionColor: Color,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val longDate = remember(record.atMs) {
        SimpleDateFormat("d MMMM yyyy", Locale.getDefault()).format(Date(record.atMs))
    }
    Column(
        modifier = modifier.clickable(
            onClickLabel = stringResource(R.string.media_photos_open, longDate),
            onClick = onOpen,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PhotoThumb(
            record = record,
            vm = vm,
            contentDescription = stringResource(R.string.media_photos_photo, longDate),
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(16.dp)),
        )
        MicroLabel(
            text = caption,
            color = captionColor,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SpanCard(oldest: PhotoRecord, newest: PhotoRecord, total: Int) {
    val months = remember(oldest.atMs, newest.atMs) { monthsBetween(oldest.atMs, newest.atMs) }
    val days = remember(oldest.atMs, newest.atMs) { daysBetween(oldest.atMs, newest.atMs) }
    // Under a month the month count would read « 0 mois », which says nothing:
    // the first weeks are exactly when people look most often, so count days.
    val headline = if (months >= 1) {
        pluralStringResource(R.plurals.media_photos_span_months, months, months)
    } else {
        pluralStringResource(R.plurals.media_photos_span_days, days, days)
    }
    EggCard(variant = CardVariant.Primary) {
        Text(
            headline,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            pluralStringResource(R.plurals.media_photos_span_sub, total, total),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun PhotoThumb(
    record: PhotoRecord,
    vm: PhotosViewModel,
    contentDescription: String,
    modifier: Modifier,
    maxPx: Int = 720,
) {
    var bitmap by remember(record.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(record.id) {
        val bytes = vm.decryptBytes(record) ?: return@LaunchedEffect
        // Full-size decode of a dozen 12 Mpx JPEGs would blow the heap while
        // scrolling; the grid never shows more than a few hundred pixels wide.
        bitmap = withContext(Dispatchers.Default) { decodeSampled(bytes, maxPx) }
    }
    Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private fun decodeSampled(bytes: ByteArray, maxPx: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    while (longest / (sample * 2) >= maxPx) sample *= 2
    return BitmapFactory.decodeByteArray(
        bytes, 0, bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample },
    )
}

private fun monthYear(atMs: Long): String =
    SimpleDateFormat("MMM yyyy", Locale.getDefault()).format(Date(atMs)).uppercase()

private fun monthsBetween(fromMs: Long, toMs: Long): Int {
    val from = Calendar.getInstance().apply { timeInMillis = fromMs }
    val to = Calendar.getInstance().apply { timeInMillis = toMs }
    var months = (to.get(Calendar.YEAR) - from.get(Calendar.YEAR)) * 12 +
        (to.get(Calendar.MONTH) - from.get(Calendar.MONTH))
    if (to.get(Calendar.DAY_OF_MONTH) < from.get(Calendar.DAY_OF_MONTH)) months--
    return months.coerceAtLeast(0)
}

private fun daysBetween(fromMs: Long, toMs: Long): Int =
    ((toMs - fromMs) / 86_400_000L).toInt().coerceAtLeast(0)

/**
 * Full-screen viewer: pinch-zoom, pan, double-tap, and the three actions that
 * existed before the refonte. Its own window needs `FLAG_SECURE` too — the
 * activity flag does not cover a dialog window.
 */
@Composable
private fun PhotoLightbox(
    record: PhotoRecord,
    vm: PhotosViewModel,
    onClose: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
) {
    var bitmap by remember(record.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(record.id) {
        val bytes = vm.decryptBytes(record) ?: return@LaunchedEffect
        bitmap = withContext(Dispatchers.Default) { decodeSampled(bytes, 2048) }
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

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(dialogWindow) {
            dialogWindow?.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE,
            )
            onDispose { }
        }
        // Column layout, not Box+align: this guarantees the bottom action row
        // sits at the natural bottom of a finite Column, with the image area
        // expanding to fill the middle.
        //
        // The backdrop is `scrim` — black in all 15 palettes — so the chrome on
        // top of it can't borrow `onSurface`: every control carries its own
        // tonal surface instead, which keeps it legible in light *and* dark.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.94f)),
        ) {
            // Top bar: close + date
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = topInset)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    onClick = onClose,
                    modifier = Modifier.size(EggDim.TouchTarget),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(
                                R.string.media_photos_viewer_close,
                            ),
                        )
                    }
                }
                Surface(
                    shape = EggShapes.Pill,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    Text(
                        dateLabel.replaceFirstChar {
                            if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
                        },
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
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
                        contentDescription = stringResource(
                            R.string.media_photos_photo, dateLabel,
                        ),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp)
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offsetX,
                                translationY = offsetY,
                            ),
                    )
                }
            }

            // Bottom action band: it reserves its strip on a real surface
            // rather than floating over the photo, and carries explicit bottom
            // padding equal to the system nav inset (or a 40 dp fallback) —
            // Compose dialogs have reported zero insets on several devices.
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = EggShapes.Sheet,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = bottomInset)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
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
                        tint = MaterialTheme.colorScheme.error,
                        onClick = onDelete,
                    )
                }
            }
        }
    }
}

private fun Modifier.pointerInputDoubleTap(onDoubleTap: () -> Unit): Modifier =
    this.pointerInput(Unit) {
        detectTapGestures(onDoubleTap = { onDoubleTap() })
    }

@Composable
private fun LightboxAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClickLabel = label, onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint)
        }
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
