package com.douxev.eggshell.ui.voice

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.douxev.eggshell.R
import com.douxev.eggshell.data.VoiceRepository
import com.douxev.eggshell.ui.common.ScreenHeader
import com.douxev.eggshell.ui.components.CardRule
import com.douxev.eggshell.ui.components.CardVariant
import com.douxev.eggshell.ui.components.Decorative
import com.douxev.eggshell.ui.components.EggCard
import com.douxev.eggshell.ui.components.EmptyState
import com.douxev.eggshell.ui.components.ErrorCard
import com.douxev.eggshell.ui.components.MicroLabel
import com.douxev.eggshell.ui.components.SectionTitle
import com.douxev.eggshell.ui.components.SkeletonBlock
import com.douxev.eggshell.ui.components.StatusPill
import com.douxev.eggshell.ui.theme.EggColors
import com.douxev.eggshell.ui.theme.EggDim
import com.douxev.eggshell.ui.theme.EggShapes
import uniffi.transition.VoiceClip

@HiltViewModel
class VoiceViewModel @Inject constructor(
    private val repo: VoiceRepository,
) : ViewModel() {

    /** Recorder lifecycle. `Processing` covers MediaCodec decode + YIN pitch
     *  detection + AES-GCM encryption, which takes a few seconds for a 10s
     *  clip — long enough that the user otherwise thinks the stop button
     *  didn't register and double-taps. */
    enum class Phase { Idle, Recording, Processing }

    data class State(
        val clips: List<VoiceClip> = emptyList(),
        val phase: Phase = Phase.Idle,
        val recordingMs: Long = 0L,
        val playingId: String? = null,
        val loading: Boolean = true,
        val failed: Boolean = false,
    ) {
        val recording: Boolean get() = phase == Phase.Recording
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch { refresh() }
    }

    suspend fun refresh() {
        val clips = runCatching { repo.list() }.getOrDefault(emptyList())
        _state.value = _state.value.copy(clips = clips, loading = false)
    }

    fun startRecording() {
        if (_state.value.phase != Phase.Idle) return
        runCatching { repo.startRecording() }
            .onSuccess {
                _state.value = _state.value.copy(
                    phase = Phase.Recording, recordingMs = 0L, failed = false,
                )
            }
            .onFailure { _state.value = _state.value.copy(failed = true) }
    }

    fun tickRecording(ms: Long) {
        if (_state.value.phase == Phase.Recording) {
            _state.value = _state.value.copy(recordingMs = ms)
        }
    }

    fun stopRecording() {
        // Bail if the user double-taps while we're already wrapping up.
        if (_state.value.phase != Phase.Recording) return
        // Flip the UI to "Processing" immediately so the button stops taking
        // taps. The actual encrypt + pitch-detect work then runs on the IO
        // dispatcher and can take a few seconds.
        _state.value = _state.value.copy(phase = Phase.Processing, recordingMs = 0L)
        viewModelScope.launch {
            val ok = runCatching { repo.stopRecording() }.isSuccess
            _state.value = _state.value.copy(phase = Phase.Idle, failed = !ok)
            refresh()
        }
    }

    fun cancelRecording() {
        viewModelScope.launch {
            repo.cancelRecording()
            _state.value = _state.value.copy(phase = Phase.Idle, recordingMs = 0L)
        }
    }

    fun dismissFailure() {
        _state.value = _state.value.copy(failed = false)
    }

    fun togglePlay(entry: VoiceClip) {
        if (_state.value.playingId == entry.id) {
            repo.stopPlayback()
            _state.value = _state.value.copy(playingId = null)
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(playingId = entry.id)
            val ok = runCatching {
                repo.play(entry) {
                    _state.value = _state.value.copy(playingId = null)
                }
            }.getOrDefault(false)
            if (!ok) _state.value = _state.value.copy(playingId = null)
        }
    }

    fun delete(entry: VoiceClip) {
        viewModelScope.launch {
            if (_state.value.playingId == entry.id) {
                repo.stopPlayback()
                _state.value = _state.value.copy(playingId = null)
            }
            runCatching { repo.delete(entry) }
            refresh()
        }
    }

    suspend fun decryptToCache(entry: VoiceClip): java.io.File? =
        runCatching { repo.decryptToCache(entry) }.getOrNull()

    override fun onCleared() {
        repo.stopPlayback()
        // Fire-and-forget: we're being torn down anyway, but a stale
        // MediaRecorder would leak the mic across activities.
        viewModelScope.launch { runCatching { repo.cancelRecording() } }
        super.onCleared()
    }
}

/**
 * Voix (§6.11). One curve, one big button, one list. No action band: the thing
 * you came to do is the 96 dp button in the middle of the screen, not a FAB in
 * the corner.
 */
@Composable
fun VoiceScreen(
    onBack: () -> Unit,
    vm: VoiceViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var micDenied by rememberSaveable { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<VoiceClip?>(null) }
    val deletedMsg = stringResource(R.string.media_voice_deleted)

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        micDenied = !granted
        if (granted) vm.startRecording()
    }
    val onRecordTap = {
        when (state.phase) {
            VoiceViewModel.Phase.Recording -> vm.stopRecording()
            VoiceViewModel.Phase.Idle -> {
                micDenied = false
                permLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
            VoiceViewModel.Phase.Processing -> Unit
        }
    }

    // Recording timer. The 100 ms cadence is what makes the seconds tick
    // visibly rather than jumping — it's the only feedback that the mic is
    // actually live.
    LaunchedEffect(state.recording) {
        if (state.recording) {
            val start = System.currentTimeMillis()
            while (state.recording) {
                vm.tickRecording(System.currentTimeMillis() - start)
                delay(100)
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(
                start = EggDim.ScreenMargin,
                end = EggDim.ScreenMargin,
                bottom = 40.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "voice-header") {
                ScreenHeader(title = stringResource(R.string.module_voice), onBack = onBack)
            }

            item(key = "voice-trend") { PitchTrendCard(clips = state.clips) }

            // Visible reminder that this is a local estimate sensitive to
            // capture conditions — we don't want anyone reading a noisy 5 Hz
            // wobble as a real change in their voice.
            if (state.clips.any { it.pitchHz != null }) {
                item(key = "voice-disclaimer") {
                    Text(
                        stringResource(R.string.voice_pitch_disclaimer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }

            item(key = "voice-recorder") {
                RecorderCard(
                    phase = state.phase,
                    recordingMs = state.recordingMs,
                    onToggle = onRecordTap,
                )
            }

            if (micDenied) {
                item(key = "voice-mic-denied") {
                    ErrorCard(
                        message = stringResource(R.string.media_voice_mic_denied),
                        retryLabel = stringResource(R.string.action_open_settings),
                        onRetry = {
                            val intent = Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", ctx.packageName, null),
                            )
                            runCatching { ctx.startActivity(intent) }
                        },
                    )
                }
            }
            if (state.failed) {
                item(key = "voice-error") {
                    ErrorCard(
                        message = stringResource(R.string.media_voice_error),
                        retryLabel = stringResource(R.string.media_voice_error_retry),
                        onRetry = {
                            vm.dismissFailure()
                            onRecordTap()
                        },
                    )
                }
            }

            item(key = "voice-clips-title") {
                SectionTitle(text = stringResource(R.string.media_voice_section_clips))
            }

            if (state.clips.isEmpty()) {
                item(key = "voice-empty") {
                    if (state.loading) {
                        SkeletonBlock(height = 132.dp, shape = EggShapes.Card)
                    } else {
                        EmptyState(
                            message = stringResource(R.string.media_voice_empty),
                            actionLabel = stringResource(R.string.media_voice_empty_action),
                            onAction = onRecordTap,
                        )
                    }
                }
            } else {
                item(key = "voice-clips") {
                    ClipList(
                        clips = state.clips,
                        playingId = state.playingId,
                        onPlay = { vm.togglePlay(it) },
                        onShare = { clip ->
                            scope.launch {
                                val file = vm.decryptToCache(clip) ?: return@launch
                                val uri = androidx.core.content.FileProvider.getUriForFile(
                                    ctx, "${ctx.packageName}.fileprovider", file,
                                )
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "audio/m4a"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                ctx.startActivity(Intent.createChooser(send, null))
                            }
                        },
                        onDelete = { pendingDelete = it },
                    )
                }
            }
        }
    }

    // §5.4: deleting a clip is destructive and the vault has no undo.
    val doomed = pendingDelete
    if (doomed != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.media_voice_delete_title)) },
            text = { Text(stringResource(R.string.media_voice_delete_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.delete(doomed)
                        pendingDelete = null
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
 * The headline is the latest measured pitch; the pill is the distance from the
 * very first analysed clip, which is the question people actually come to this
 * screen with. A falling pitch is never drawn in `error` — down is a goal for
 * some people and a non-event for others; the glyph and the sign say which way
 * it went, and the colour stays neutral.
 */
@Composable
private fun PitchTrendCard(clips: List<VoiceClip>) {
    val withPitch = remember(clips) { clips.filter { it.pitchHz != null } }
    val latest = withPitch.firstOrNull()?.pitchHz
    val earliest = withPitch.lastOrNull()?.pitchHz
    val deltaHz = if (latest != null && earliest != null && withPitch.size >= 2) {
        latest - earliest
    } else {
        null
    }
    val latestDate = withPitch.firstOrNull()?.atMs

    EggCard(variant = CardVariant.Low, padding = PaddingValues(18.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                MicroLabel(
                    if (latest != null && latestDate != null) {
                        stringResource(R.string.media_voice_pitch_label, dayMonth(latestDate))
                    } else {
                        stringResource(R.string.voice_clips_count_label)
                    },
                )
                Row(
                    modifier = Modifier.padding(top = 2.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        (latest ?: clips.size).toString(),
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        if (latest != null) {
                            stringResource(R.string.media_voice_hz)
                        } else {
                            pluralStringResource(R.plurals.media_voice_clips_unit, clips.size)
                        },
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
            }
            if (deltaHz != null) DeltaPill(deltaHz)
        }

        Box(modifier = Modifier.padding(top = 12.dp)) {
            // Oldest first, and each point keeps its timestamp: the X axis is
            // proportional to time, never to the index (§5.1). Two clips a year
            // apart must not sit as close as two clips a day apart.
            PitchSparkline(
                points = withPitch.reversed().mapNotNull { clip ->
                    clip.pitchHz?.let { clip.atMs to it }
                },
            )
        }

        if (deltaHz == null) {
            Text(
                stringResource(R.string.media_voice_trend_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun DeltaPill(deltaHz: Int) {
    val glyph = stringResource(
        when {
            deltaHz > 0 -> R.string.media_voice_delta_glyph_up
            deltaHz < 0 -> R.string.media_voice_delta_glyph_down
            else -> R.string.media_voice_delta_glyph_flat
        },
    )
    val value = when {
        deltaHz > 0 -> stringResource(R.string.media_voice_delta_up, deltaHz)
        deltaHz < 0 -> stringResource(R.string.media_voice_delta_down, -deltaHz)
        else -> stringResource(R.string.media_voice_delta_flat)
    }
    val described = when {
        deltaHz > 0 -> stringResource(R.string.media_voice_delta_up_cd, deltaHz)
        deltaHz < 0 -> stringResource(R.string.media_voice_delta_down_cd, -deltaHz)
        else -> stringResource(R.string.media_voice_delta_flat_cd)
    }
    val container = when {
        deltaHz > 0 -> EggColors.successContainer
        deltaHz < 0 -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val content = when {
        deltaHz > 0 -> EggColors.onSuccessContainer
        deltaHz < 0 -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    StatusPill(
        label = "$glyph $value",
        container = container,
        content = content,
        modifier = Modifier.clearAndSetSemantics { contentDescription = described },
    )
}

/**
 * Pitch over time. Below two analysed clips there is no trend to tell, so the
 * card keeps a decorative waveform rather than a lie or a hole.
 */
@Composable
private fun PitchSparkline(points: List<Pair<Long, Int>>) {
    val color = MaterialTheme.colorScheme.primary
    if (points.size < 2) {
        Decorative { StaticWaveform(color = color, n = 34, height = 56.dp) }
        return
    }
    val pitches = points.map { it.second }
    val min = pitches.min()
    val max = pitches.max()
    val range = (max - min).coerceAtLeast(1).toFloat()
    val firstMs = points.first().first
    val spanMs = (points.last().first - firstMs).coerceAtLeast(1L).toFloat()
    val described = stringResource(
        R.string.media_voice_sparkline_cd, points.size, min, max,
    )
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clearAndSetSemantics { contentDescription = described },
    ) {
        val pad = 7.dp.toPx()
        // The terminal dot is drawn at the last X, so the plot is inset by its
        // radius on both sides — otherwise it would be sliced in half.
        val xPad = 5.dp.toPx()
        val plotted = points.map { (atMs, hz) ->
            Offset(
                x = xPad + ((atMs - firstMs) / spanMs) * (size.width - 2 * xPad),
                y = size.height - pad - ((hz - min) / range) * (size.height - 2 * pad),
            )
        }
        val path = Path().apply {
            moveTo(plotted[0].x, plotted[0].y)
            plotted.drop(1).forEach { lineTo(it.x, it.y) }
        }
        drawPath(
            path,
            color,
            style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round),
        )
        // Terminal point, filled and slightly larger — the graphic grammar of
        // §5.1 for "this is where you are now".
        drawCircle(color, radius = 4.2.dp.toPx(), center = plotted.last())
    }
}

@Composable
private fun RecorderCard(
    phase: VoiceViewModel.Phase,
    recordingMs: Long,
    onToggle: () -> Unit,
) {
    val recording = phase == VoiceViewModel.Phase.Recording
    val processing = phase == VoiceViewModel.Phase.Processing
    // A slow breath while the mic is live: the only moving thing on the screen,
    // so there is no doubt about which state we're in.
    val scale by animateFloatAsState(if (recording) 1.05f else 1f, label = "rec-scale")
    val container = if (recording) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }
    val onContainer = if (recording) {
        MaterialTheme.colorScheme.onError
    } else {
        MaterialTheme.colorScheme.onPrimary
    }
    val buttonLabel = stringResource(if (recording) R.string.voice_stop else R.string.voice_record)

    EggCard(variant = CardVariant.Primary) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                onClick = onToggle,
                enabled = !processing,
                modifier = Modifier.size((96 * scale).dp),
                shape = CircleShape,
                color = container,
                contentColor = onContainer,
                shadowElevation = 8.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (processing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(38.dp),
                            color = onContainer,
                            strokeWidth = 3.dp,
                        )
                    } else {
                        Icon(
                            if (recording) Icons.Filled.Stop else Icons.Filled.Mic,
                            contentDescription = buttonLabel,
                            modifier = Modifier.size(42.dp),
                        )
                    }
                }
            }
            Text(
                when (phase) {
                    VoiceViewModel.Phase.Recording -> stringResource(
                        R.string.media_voice_recording_title, formatMmSs(recordingMs),
                    )
                    VoiceViewModel.Phase.Processing ->
                        stringResource(R.string.media_voice_processing_title)
                    VoiceViewModel.Phase.Idle ->
                        stringResource(R.string.media_voice_record_title)
                },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Text(
                when (phase) {
                    VoiceViewModel.Phase.Recording ->
                        stringResource(R.string.media_voice_recording_sub)
                    VoiceViewModel.Phase.Processing ->
                        stringResource(R.string.media_voice_processing_sub)
                    VoiceViewModel.Phase.Idle ->
                        stringResource(R.string.media_voice_record_sub)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ClipList(
    clips: List<VoiceClip>,
    playingId: String?,
    onPlay: (VoiceClip) -> Unit,
    onShare: (VoiceClip) -> Unit,
    onDelete: (VoiceClip) -> Unit,
) {
    EggCard(
        variant = CardVariant.Low,
        padding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
    ) {
        clips.forEachIndexed { index, clip ->
            if (index > 0) CardRule()
            ClipRow(
                clip = clip,
                playing = playingId == clip.id,
                onPlay = { onPlay(clip) },
                onShare = { onShare(clip) },
                onDelete = { onDelete(clip) },
            )
        }
    }
}

@Composable
private fun ClipRow(
    clip: VoiceClip,
    playing: Boolean,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val duration = formatMmSs(clip.durationMs)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            onClick = onPlay,
            modifier = Modifier.size(36.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    if (playing) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(
                        if (playing) R.string.voice_stop else R.string.voice_play,
                    ),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                dayMonthLower(clip.atMs),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                duration,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val pitch = clip.pitchHz
        // A clip whose pitch we couldn't measure shows an em dash, and the dash
        // gets spelled out for screen readers — silence would read as a value.
        val unknownPitch = stringResource(R.string.media_voice_pitch_unknown_cd)
        StatusPill(
            label = if (pitch != null) {
                stringResource(R.string.media_voice_pitch, pitch)
            } else {
                stringResource(R.string.media_voice_pitch_unknown)
            },
            container = MaterialTheme.colorScheme.surfaceContainerHighest,
            content = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = if (pitch == null) {
                Modifier.clearAndSetSemantics { contentDescription = unknownPitch }
            } else {
                Modifier
            },
        )
        Box {
            IconButton(
                onClick = { menuOpen = true },
                modifier = Modifier.size(EggDim.TouchTarget),
            ) {
                Icon(
                    Icons.Filled.MoreHoriz,
                    contentDescription = stringResource(R.string.action_more),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.voice_share)) },
                    leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onShare()
                    },
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.action_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                )
            }
        }
    }
}

@Composable
private fun StaticWaveform(color: Color, n: Int, height: androidx.compose.ui.unit.Dp) {
    // Deterministic decorative bars — the height varies by index so the card
    // looks alive without pretending to plot data we don't have yet.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(height),
    ) {
        for (i in 0 until n) {
            val mag = 6f + abs(sin(i * 1.7).toFloat() * 26f) + (i % 3) * 4f
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(mag.coerceAtMost(height.value).dp)
                    .clip(EggShapes.Pill)
                    .background(color.copy(alpha = 0.45f)),
            )
        }
    }
}

private fun formatMmSs(ms: Long): String {
    val s = (ms / 1000).toInt()
    return "%d:%02d".format(s / 60, s % 60)
}

/** « 21 JUILLET » — the small-caps date of the trend card. */
private fun dayMonth(ms: Long): String =
    SimpleDateFormat("d MMMM", Locale.getDefault()).format(Date(ms)).uppercase()

/** « 21 juillet » — the clip-row date. */
private fun dayMonthLower(ms: Long): String =
    SimpleDateFormat("d MMMM", Locale.getDefault()).format(Date(ms))
