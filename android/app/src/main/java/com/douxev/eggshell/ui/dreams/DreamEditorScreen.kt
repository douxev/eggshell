package com.douxev.eggshell.ui.dreams

import android.Manifest
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Subject
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.douxev.eggshell.R
import com.douxev.eggshell.data.DreamsRepository
import com.douxev.eggshell.data.MetricsRepository
import com.douxev.eggshell.data.dreams.OnDeviceTranscriber
import com.douxev.eggshell.ui.common.MetricSliderStack
import com.douxev.eggshell.ui.common.ScreenHeader
import com.douxev.eggshell.ui.common.clickToDismissKeyboard
import com.douxev.eggshell.ui.common.rememberLocale
import com.douxev.eggshell.ui.components.ActionBand
import com.douxev.eggshell.ui.components.CardVariant
import com.douxev.eggshell.ui.components.EggCard
import com.douxev.eggshell.ui.components.MicroLabel
import com.douxev.eggshell.ui.components.SectionTitle
import com.douxev.eggshell.ui.theme.EggDim
import com.douxev.eggshell.ui.theme.EggShapes
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import uniffi.transition.DreamAudio
import uniffi.transition.DreamTag
import uniffi.transition.MetricDefinition
import uniffi.transition.MetricValue

@HiltViewModel
class DreamEditorViewModel @Inject constructor(
    state: SavedStateHandle,
    private val repo: DreamsRepository,
    private val metrics: MetricsRepository,
    private val transcriber: OnDeviceTranscriber,
) : ViewModel() {

    /** > 0 when editing an existing dream. */
    private val editingId: Long = state.get<Long>("id") ?: -1L
    val isEditing: Boolean get() = editingId > 0L

    data class State(
        val nightMs: Long = DreamsRepository.nightOf(System.currentTimeMillis()),
        val title: String = "",
        val body: String = "",
        val lucid: Boolean = false,
        val definitions: List<MetricDefinition> = emptyList(),
        val allTags: List<DreamTag> = emptyList(),
        val selectedTagIds: Set<Long> = emptySet(),
        val audio: List<DreamAudio> = emptyList(),
        val recording: Boolean = false,
        val transcribing: Long? = null,
        val transcribeUnavailable: OnDeviceTranscriber.Reason? = null,
        val loading: Boolean = true,
        val saved: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Slider values, keyed by metric definition id. */
    val values: SnapshotStateMap<Long, Float> = mutableStateMapOf()

    /**
     * The dream row id. A voice note has to hang off a real row, so an unsaved
     * dream is persisted the moment recording starts — see [ensureSaved].
     */
    private var dreamId: Long = -1L

    init { load() }

    private fun load() {
        viewModelScope.launch {
            val defs = runCatching {
                metrics.definitions(MetricsRepository.DOMAIN_DREAMS)
            }.getOrDefault(emptyList()).filter { it.enabled && !it.archived }
            val tags = runCatching { repo.tags() }.getOrDefault(emptyList())

            if (editingId > 0L) {
                dreamId = editingId
                val d = runCatching { repo.get(editingId) }.getOrNull()
                val stored = runCatching {
                    metrics.values(MetricsRepository.DOMAIN_DREAMS, editingId)
                }.getOrDefault(emptyList())
                stored.forEach { values[it.metricId] = it.value.toFloat() }
                defs.forEach { values.putIfAbsent(it.id, midpoint(it)) }
                _state.value = State(
                    nightMs = d?.nightMs ?: DreamsRepository.nightOf(System.currentTimeMillis()),
                    title = d?.title.orEmpty(),
                    body = d?.body.orEmpty(),
                    lucid = d?.lucid == true,
                    definitions = defs,
                    allTags = tags,
                    selectedTagIds = runCatching { repo.tagsFor(editingId) }
                        .getOrDefault(emptyList()).map { it.id }.toSet(),
                    audio = runCatching { repo.audioFor(editingId) }.getOrDefault(emptyList()),
                    transcribeUnavailable = transcriber.availability(),
                    loading = false,
                )
            } else {
                defs.forEach { values[it.id] = midpoint(it) }
                _state.value = State(
                    definitions = defs,
                    allTags = tags,
                    transcribeUnavailable = transcriber.availability(),
                    loading = false,
                )
            }
        }
    }

    /** Mid-scale, so an untouched slider records "no opinion" rather than zero. */
    private fun midpoint(d: MetricDefinition): Float =
        ((d.minValue.toInt() + d.maxValue.toInt()) / 2).toFloat()

    fun setNight(ms: Long) { _state.value = _state.value.copy(nightMs = ms) }
    fun setTitle(v: String) { _state.value = _state.value.copy(title = v) }
    fun setBody(v: String) { _state.value = _state.value.copy(body = v) }
    fun setLucid(v: Boolean) { _state.value = _state.value.copy(lucid = v) }

    fun toggleTag(tagId: Long) {
        val cur = _state.value.selectedTagIds
        _state.value = _state.value.copy(
            selectedTagIds = if (tagId in cur) cur - tagId else cur + tagId,
        )
    }

    fun createTag(label: String) {
        if (label.isBlank()) return
        viewModelScope.launch {
            // Get-or-create in the core, so typing a tag that already exists
            // selects it instead of erroring or duplicating it.
            val tag = runCatching { repo.addTag(label.trim()) }.getOrNull() ?: return@launch
            _state.value = _state.value.copy(
                allTags = runCatching { repo.tags() }.getOrDefault(_state.value.allTags),
                selectedTagIds = _state.value.selectedTagIds + tag.id,
            )
        }
    }

    /**
     * Persist enough of the dream that attachments have something to hang off.
     *
     * Recording is the one action that cannot wait for Save: audio lands in a
     * row keyed by dream id, and a dream with no id yet has nowhere to put it.
     */
    private suspend fun ensureSaved(): Long {
        if (dreamId > 0L) return dreamId
        val s = _state.value
        val created = runCatching {
            repo.add(s.nightMs, s.title, s.body, s.lucid)
        }.getOrNull() ?: return -1L
        dreamId = created.id
        return dreamId
    }

    fun startRecording() {
        viewModelScope.launch {
            if (ensureSaved() <= 0L) return@launch
            if (repo.startRecording()) {
                _state.value = _state.value.copy(recording = true)
            }
        }
    }

    fun stopRecording(languageTag: String, autoTranscribe: Boolean) {
        viewModelScope.launch {
            val id = dreamId
            if (id <= 0L) return@launch
            val result = runCatching { repo.stopRecording(id) }.getOrNull()
            _state.value = _state.value.copy(
                recording = false,
                audio = runCatching { repo.audioFor(id) }.getOrDefault(emptyList()),
            )
            if (result == null) return@launch
            val (row, plaintext) = result
            if (autoTranscribe && _state.value.transcribeUnavailable == null) {
                transcribe(row, plaintext, languageTag)
            } else {
                // Nothing else will read it; the plaintext copy must not linger.
                runCatching { plaintext.delete() }
            }
        }
    }

    fun cancelRecording() {
        viewModelScope.launch {
            runCatching { repo.cancelRecording() }
            _state.value = _state.value.copy(recording = false)
        }
    }

    /**
     * Transcribe an existing clip, which means decrypting it again.
     *
     * The plaintext copy is deleted in a `finally`: it exists only for the
     * length of the call, and a failure part-way through is exactly when it
     * would otherwise be left behind.
     */
    fun transcribeExisting(audio: DreamAudio, languageTag: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(transcribing = audio.id)
            val plain = runCatching { repo.decryptToCache(audio) }.getOrNull()
            if (plain == null) {
                _state.value = _state.value.copy(transcribing = null)
                return@launch
            }
            transcribe(audio, plain, languageTag)
        }
    }

    private suspend fun transcribe(audio: DreamAudio, plaintext: File, languageTag: String) {
        _state.value = _state.value.copy(transcribing = audio.id)
        try {
            when (val r = transcriber.transcribe(plaintext, languageTag)) {
                is OnDeviceTranscriber.Result.Text -> {
                    runCatching { repo.setTranscript(audio.id, r.transcript) }
                }
                is OnDeviceTranscriber.Result.Unavailable -> {
                    _state.value = _state.value.copy(transcribeUnavailable = r.reason)
                }
                else -> Unit
            }
        } finally {
            runCatching { plaintext.delete() }
            _state.value = _state.value.copy(
                transcribing = null,
                audio = runCatching { repo.audioFor(dreamId) }.getOrDefault(_state.value.audio),
            )
        }
    }

    fun deleteAudio(audio: DreamAudio) {
        viewModelScope.launch {
            runCatching { repo.deleteAudio(audio) }
            _state.value = _state.value.copy(
                audio = runCatching { repo.audioFor(dreamId) }.getOrDefault(emptyList()),
            )
        }
    }

    fun save() {
        viewModelScope.launch {
            val s = _state.value
            val id = if (dreamId > 0L) {
                runCatching { repo.update(dreamId, s.nightMs, s.title, s.body, s.lucid) }
                    .getOrNull()?.id ?: return@launch
            } else {
                runCatching { repo.add(s.nightMs, s.title, s.body, s.lucid) }
                    .getOrNull()?.id ?: return@launch
            }
            dreamId = id

            // Tags: replace the set wholesale rather than diffing. tag/untag are
            // both idempotent in the core, so this is cheap and cannot drift.
            val existing = runCatching { repo.tagsFor(id) }.getOrDefault(emptyList())
                .map { it.id }.toSet()
            (s.selectedTagIds - existing).forEach { runCatching { repo.tag(id, it) } }
            (existing - s.selectedTagIds).forEach { runCatching { repo.untag(id, it) } }

            runCatching {
                metrics.replaceValues(
                    MetricsRepository.DOMAIN_DREAMS,
                    id,
                    s.definitions.mapNotNull { def ->
                        values[def.id]?.let {
                            MetricValue(metricId = def.id, value = it.roundToInt().toUInt())
                        }
                    },
                )
            }
            _state.value = _state.value.copy(saved = true)
        }
    }

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch {
            if (dreamId > 0L) runCatching { repo.delete(dreamId) }
            onDone()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DreamEditorScreen(
    onDone: () -> Unit,
    onBack: () -> Unit,
    vm: DreamEditorViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val locale = rememberLocale()
    val nightFmt = remember(locale) { SimpleDateFormat("EEEE d MMMM yyyy", locale) }

    var newTag by rememberSaveable { mutableStateOf("") }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var autoTranscribe by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(state.saved) { if (state.saved) onDone() }

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) vm.startRecording() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        bottomBar = {
            ActionBand(alignment = Alignment.Center) {
                Button(
                    onClick = { vm.save() },
                    shape = EggShapes.Pill,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.action_save)) }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .clickToDismissKeyboard()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = EggDim.ScreenMargin),
            verticalArrangement = Arrangement.spacedBy(EggDim.BlockGap),
        ) {
            ScreenHeader(
                title = stringResource(
                    if (vm.isEditing) R.string.dreams_edit_title else R.string.dreams_new_title
                ),
                onBack = onBack,
                actions = {
                    if (vm.isEditing) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.dreams_delete),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
            )

            // The night, first and prominent: it is what the entry is about,
            // and it is not the day the user is typing on.
            EggCard(variant = CardVariant.Low, onClick = { showDatePicker = true }) {
                MicroLabel(stringResource(R.string.dreams_night_label))
                Text(
                    nightFmt.format(Date(state.nightMs))
                        .replaceFirstChar { it.titlecase(locale) },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 2.dp),
                )
                MicroLabel(
                    stringResource(R.string.dreams_night_hint),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            OutlinedTextField(
                value = state.title,
                onValueChange = vm::setTitle,
                label = { Text(stringResource(R.string.dreams_field_title)) },
                singleLine = true,
                shape = EggShapes.Field,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = state.body,
                onValueChange = vm::setBody,
                label = { Text(stringResource(R.string.dreams_field_body)) },
                minLines = 5,
                shape = EggShapes.Field,
                modifier = Modifier.fillMaxWidth(),
            )

            EggCard(variant = CardVariant.Low) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.dreams_lucid_label),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        MicroLabel(stringResource(R.string.dreams_lucid_hint))
                    }
                    Switch(checked = state.lucid, onCheckedChange = vm::setLucid)
                }
            }

            // -- Voice notes ------------------------------------------------
            SectionTitle(stringResource(R.string.dreams_section_voice))
            MicroLabel(stringResource(R.string.dreams_voice_hint))

            state.audio.forEach { clip ->
                AudioRow(
                    clip = clip,
                    transcribing = state.transcribing == clip.id,
                    canTranscribe = state.transcribeUnavailable == null,
                    onTranscribe = { vm.transcribeExisting(clip, locale.toLanguageTag()) },
                    onDelete = { vm.deleteAudio(clip) },
                )
            }

            state.transcribeUnavailable?.let { reason ->
                EggCard(variant = CardVariant.Outlined) {
                    Text(
                        stringResource(
                            when (reason) {
                                OnDeviceTranscriber.Reason.ApiTooOld ->
                                    R.string.dreams_transcribe_api_too_old
                                OnDeviceTranscriber.Reason.NoRecognizer ->
                                    R.string.dreams_transcribe_no_recognizer
                                OnDeviceTranscriber.Reason.LanguageNotDownloaded ->
                                    R.string.dreams_transcribe_no_language
                            }
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (state.transcribeUnavailable == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.dreams_auto_transcribe),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        MicroLabel(stringResource(R.string.dreams_auto_transcribe_hint))
                    }
                    Switch(checked = autoTranscribe, onCheckedChange = { autoTranscribe = it })
                }
            }

            Button(
                onClick = {
                    if (state.recording) {
                        vm.stopRecording(locale.toLanguageTag(), autoTranscribe)
                    } else if (hasMicPermission(context)) {
                        vm.startRecording()
                    } else {
                        micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                shape = EggShapes.Pill,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    if (state.recording) Icons.Filled.Stop else Icons.Filled.Mic,
                    contentDescription = null,
                )
                Text(
                    stringResource(
                        if (state.recording) R.string.dreams_record_stop
                        else R.string.dreams_record_start
                    ),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            // -- Sleep sliders ----------------------------------------------
            if (state.definitions.isNotEmpty()) {
                SectionTitle(stringResource(R.string.dreams_section_sleep))
                MetricSliderStack(definitions = state.definitions, values = vm.values)
            }

            // -- Tags ---------------------------------------------------------
            SectionTitle(stringResource(R.string.dreams_section_tags))
            MicroLabel(stringResource(R.string.dreams_tags_hint))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                state.allTags.forEach { tag ->
                    FilterChip(
                        selected = tag.id in state.selectedTagIds,
                        onClick = { vm.toggleTag(tag.id) },
                        label = { Text(tag.label) },
                        shape = EggShapes.Pill,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ),
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = newTag,
                    onValueChange = { newTag = it.take(40) },
                    label = { Text(stringResource(R.string.dreams_tag_new)) },
                    singleLine = true,
                    shape = EggShapes.Field,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { vm.createTag(newTag); newTag = "" },
                    enabled = newTag.isNotBlank(),
                ) { Text(stringResource(R.string.dreams_tag_add)) }
            }

            Spacer(Modifier.height(12.dp))
        }
    }

    if (showDatePicker) {
        NightPickerDialog(
            initialMs = state.nightMs,
            onDismiss = { showDatePicker = false },
            onPick = { vm.setNight(it); showDatePicker = false },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.dreams_delete_title)) },
            text = { Text(stringResource(R.string.dreams_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.delete(onDone)
                }) {
                    Text(
                        stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun AudioRow(
    clip: DreamAudio,
    transcribing: Boolean,
    canTranscribe: Boolean,
    onTranscribe: () -> Unit,
    onDelete: () -> Unit,
) {
    EggCard(
        variant = CardVariant.Low,
        padding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                formatDuration(clip.durationMs),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .padding(start = 10.dp)
                    .weight(1f),
            )
            if (clip.transcript == null && canTranscribe) {
                TextButton(onClick = onTranscribe, enabled = !transcribing) {
                    Icon(
                        Icons.Filled.Subject,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        stringResource(
                            if (transcribing) R.string.dreams_transcribing
                            else R.string.dreams_transcribe
                        ),
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(EggDim.TouchTarget)) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.dreams_audio_delete),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
        clip.transcript?.takeIf { it.isNotBlank() }?.let { text ->
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun NightPickerDialog(
    initialMs: Long,
    onDismiss: () -> Unit,
    onPick: (Long) -> Unit,
) {
    val state = androidx.compose.material3.rememberDatePickerState(
        initialSelectedDateMillis = initialMs,
    )
    androidx.compose.material3.DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val picked = state.selectedDateMillis
                if (picked == null) {
                    onDismiss()
                } else {
                    // The picker reports UTC midnight; reinterpret those y/m/d
                    // at the local zone so the night cannot shift a day in a
                    // negative-offset timezone.
                    val date = java.time.Instant.ofEpochMilli(picked)
                        .atZone(java.time.ZoneOffset.UTC).toLocalDate()
                    onPick(DreamsRepository.nightOfDate(date))
                }
            }) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    ) {
        androidx.compose.material3.DatePicker(state = state)
    }
}

private fun hasMicPermission(context: Context): Boolean =
    androidx.core.content.ContextCompat.checkSelfPermission(
        context, Manifest.permission.RECORD_AUDIO,
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED

private fun formatDuration(ms: Long): String {
    val total = (ms / 1000).toInt()
    return "%d:%02d".format(total / 60, total % 60)
}
