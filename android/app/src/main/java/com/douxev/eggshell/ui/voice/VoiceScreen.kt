package com.douxev.eggshell.ui.voice

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
        val error: String? = null,
    ) {
        val recording: Boolean get() = phase == Phase.Recording
        val processing: Boolean get() = phase == Phase.Processing
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch { refresh() }
    }

    suspend fun refresh() {
        _state.value = _state.value.copy(clips = repo.list())
    }

    fun startRecording() {
        if (_state.value.phase != Phase.Idle) return
        runCatching { repo.startRecording() }
            .onSuccess {
                _state.value = _state.value.copy(
                    phase = Phase.Recording, recordingMs = 0L, error = null,
                )
            }
            .onFailure { _state.value = _state.value.copy(error = it.message) }
    }

    fun tickRecording(ms: Long) {
        if (_state.value.phase == Phase.Recording) {
            _state.value = _state.value.copy(recordingMs = ms)
        }
    }

    fun stopRecording() {
        // Bail if the user double-taps while we're already wrapping up.
        if (_state.value.phase != Phase.Recording) return
        // Flip the UI to "Processing" immediately so the button shows a
        // spinner and ignores further taps. The actual encrypt + pitch-detect
        // work then runs on the IO dispatcher and can take a few seconds.
        _state.value = _state.value.copy(phase = Phase.Processing, recordingMs = 0L)
        viewModelScope.launch {
            runCatching { repo.stopRecording() }
                .onFailure { _state.value = _state.value.copy(error = it.message) }
            _state.value = _state.value.copy(phase = Phase.Idle, clips = repo.list())
        }
    }

    fun cancelRecording() {
        viewModelScope.launch {
            repo.cancelRecording()
            _state.value = _state.value.copy(phase = Phase.Idle, recordingMs = 0L)
        }
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
            repo.delete(entry)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit = {},
    vm: VoiceViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) vm.startRecording() }

    // Recording timer
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
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.voice_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            Icons.Filled.MoreHoriz,
                            contentDescription = stringResource(R.string.more_title),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            PitchTrendCard(clips = state.clips)
            // Visible reminder that this is a local estimate sensitive to
            // capture conditions — we don't want users overreading a noisy
            // 5 Hz wobble as a real F0 change.
            if (state.clips.any { it.pitchHz != null }) {
                Text(
                    stringResource(R.string.voice_pitch_disclaimer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            RecorderCard(
                phase = state.phase,
                recordingMs = state.recordingMs,
                onToggle = {
                    when (state.phase) {
                        VoiceViewModel.Phase.Recording -> vm.stopRecording()
                        VoiceViewModel.Phase.Idle ->
                            permLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        VoiceViewModel.Phase.Processing -> { /* ignore */ }
                    }
                },
            )

            Text(
                stringResource(R.string.voice_section_clips),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.clips.isEmpty()) {
                Text(
                    stringResource(R.string.voice_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                state.clips.forEach { clip ->
                    ClipRow(
                        clip = clip,
                        playing = state.playingId == clip.id,
                        onPlay = { vm.togglePlay(clip) },
                        onShare = {
                            scope.launch {
                                val file = vm.decryptToCache(clip) ?: return@launch
                                val uri = androidx.core.content.FileProvider.getUriForFile(
                                    ctx, "${ctx.packageName}.fileprovider", file,
                                )
                                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "audio/m4a"
                                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                ctx.startActivity(android.content.Intent.createChooser(send, null))
                            }
                        },
                        onDelete = { vm.delete(clip) },
                    )
                }
            }
            Spacer(Modifier.height(48.dp))
        }
    }
}

@Composable
private fun PitchTrendCard(clips: List<VoiceClip>) {
    // Show the latest clip's F0 as the headline and the delta vs. the very
    // first analysed clip — that's the trans-HRT voice training signal: how
    // far has F0 risen since you started tracking?
    val withPitch = remember(clips) { clips.filter { it.pitchHz != null } }
    val latest = withPitch.firstOrNull()?.pitchHz
    val earliest = withPitch.lastOrNull()?.pitchHz
    val deltaHz = if (latest != null && earliest != null && withPitch.size >= 2) latest - earliest else null

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (latest != null) stringResource(R.string.voice_pitch_label)
                    else stringResource(R.string.voice_clips_count_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                )
                if (latest != null) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            latest.toString(),
                            fontSize = 34.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Hz", style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        when {
                            deltaHz == null -> stringResource(R.string.voice_clips_count_fmt, clips.size)
                            deltaHz > 0 -> stringResource(R.string.voice_pitch_delta_up, deltaHz)
                            deltaHz < 0 -> stringResource(R.string.voice_pitch_delta_down, -deltaHz)
                            else -> stringResource(R.string.voice_pitch_delta_flat)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                    )
                } else {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            clips.size.toString(),
                            fontSize = 34.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.voice_clips_unit),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Text(
                        stringResource(R.string.voice_clips_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                    )
                }
            }
            Box(modifier = Modifier
                .padding(start = 12.dp)
                .size(width = 130.dp, height = 48.dp)) {
                PitchSparkline(
                    pitches = withPitch.mapNotNull { it.pitchHz }.reversed(),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

/**
 * Tiny sparkline of pitch over time. Falls back to a decorative waveform
 * when we don't have enough analysed clips yet to draw a trend.
 */
@Composable
private fun PitchSparkline(pitches: List<Int>, color: Color) {
    if (pitches.size < 2) {
        StaticWaveform(color = color, n = 30)
        return
    }
    val min = pitches.min().toFloat()
    val max = pitches.max().toFloat()
    val range = (max - min).coerceAtLeast(1f)
    androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxWidth().height(48.dp)) {
        val w = size.width
        val h = size.height
        val step = w / (pitches.size - 1)
        val pad = 6f
        val points = pitches.mapIndexed { i, v ->
            val x = i * step
            val y = h - pad - ((v - min) / range) * (h - 2 * pad)
            androidx.compose.ui.geometry.Offset(x, y)
        }
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(points[0].x, points[0].y)
            points.drop(1).forEach { lineTo(it.x, it.y) }
        }
        drawPath(
            path,
            color,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = 2.5f.dp.toPx(),
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            ),
        )
        drawCircle(color, radius = 3.5f.dp.toPx(), center = points.last())
    }
}

@Composable
private fun RecorderCard(
    phase: VoiceViewModel.Phase,
    recordingMs: Long,
    onToggle: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val scale by animateFloatAsState(
                if (phase == VoiceViewModel.Phase.Recording) 1.06f else 1f,
                label = "rec-scale",
            )
            val container = when (phase) {
                VoiceViewModel.Phase.Recording -> MaterialTheme.colorScheme.error
                VoiceViewModel.Phase.Processing -> MaterialTheme.colorScheme.surfaceContainerHighest
                VoiceViewModel.Phase.Idle -> MaterialTheme.colorScheme.primary
            }
            Box(
                modifier = Modifier
                    .size((76 * scale).dp)
                    .clip(CircleShape)
                    .background(container),
                contentAlignment = Alignment.Center,
            ) {
                when (phase) {
                    VoiceViewModel.Phase.Processing -> {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(36.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 3.dp,
                        )
                    }
                    else -> {
                        IconButton(onClick = onToggle, modifier = Modifier.size(76.dp)) {
                            Icon(
                                if (phase == VoiceViewModel.Phase.Recording)
                                    Icons.Filled.Stop else Icons.Filled.Mic,
                                contentDescription = stringResource(
                                    if (phase == VoiceViewModel.Phase.Recording)
                                        R.string.voice_stop else R.string.voice_record
                                ),
                                tint = if (phase == VoiceViewModel.Phase.Recording)
                                    MaterialTheme.colorScheme.onError
                                else MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(36.dp),
                            )
                        }
                    }
                }
            }
            Text(
                when (phase) {
                    VoiceViewModel.Phase.Recording -> stringResource(
                        R.string.voice_recording_fmt,
                        formatMmSs(recordingMs),
                    )
                    VoiceViewModel.Phase.Processing ->
                        stringResource(R.string.voice_processing_title)
                    VoiceViewModel.Phase.Idle -> stringResource(R.string.voice_record_hint)
                },
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                when (phase) {
                    VoiceViewModel.Phase.Processing ->
                        stringResource(R.string.voice_processing_sub)
                    else -> stringResource(R.string.voice_record_sub)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
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
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                IconButton(onClick = onPlay, modifier = Modifier.size(44.dp)) {
                    Icon(
                        if (playing) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        contentDescription = stringResource(
                            if (playing) R.string.voice_stop else R.string.voice_play
                        ),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(modifier = Modifier
                .padding(horizontal = 12.dp)
                .weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        formatDate(clip.atMs),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    if (clip.pitchHz != null) {
                        Text(
                            "${clip.pitchHz} Hz",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        formatMmSs(clip.durationMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StaticWaveform(
                    color = MaterialTheme.colorScheme.primary,
                    n = 28,
                    height = 28.dp,
                )
            }
            IconButton(onClick = onShare) {
                Icon(
                    Icons.Filled.Share,
                    contentDescription = stringResource(R.string.voice_share),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.action_delete),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StaticWaveform(
    color: Color,
    n: Int = 28,
    height: androidx.compose.ui.unit.Dp = 28.dp,
) {
    // Deterministic decorative bars — height varies by index so each card
    // looks distinct without requiring actual amplitude analysis.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.height(height),
    ) {
        for (i in 0 until n) {
            val mag = 4f + abs(sin(i * 1.7).toFloat() * 18f) + (i % 3) * 3f
            Box(
                modifier = Modifier
                    .width(2.5.dp)
                    .height(mag.coerceAtMost(height.value).dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color.copy(alpha = 0.55f)),
            )
        }
    }
}

private fun formatMmSs(ms: Long): String {
    val s = (ms / 1000).toInt()
    return "%d:%02d".format(s / 60, s % 60)
}

private fun formatDate(ms: Long): String =
    SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(ms))
