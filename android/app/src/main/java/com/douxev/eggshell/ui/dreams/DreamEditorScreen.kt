package com.douxev.eggshell.ui.dreams

import android.Manifest
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.RadioButton
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
import java.util.Locale
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

    /**
     * Night pre-set by tapping an empty cell in the calendar, or -1.
     *
     * This is why the calendar is worth having: you remember a dream two days
     * late and file it against the right night without opening a date picker.
     */
    private val presetNightMs: Long = state.get<Long>("night") ?: -1L

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
        /** Id of the clip currently sounding, or null. */
        val playing: Long? = null,
        val transcribeUnavailable: OnDeviceTranscriber.Reason? = null,
        /** The offline model is missing but this phone can fetch it. */
        val modelDownloadable: Boolean = false,
        val downloadingModel: Boolean = false,
        val engines: List<OnDeviceTranscriber.Engine> = emptyList(),
        val selectedEngine: OnDeviceTranscriber.Engine? = null,
        /** The last attempt produced no text. Shown; never inferred silently. */
        val transcribeFailed: Boolean = false,
        val autoTranscribe: Boolean = true,
        val loading: Boolean = true,
        val saved: Boolean = false,
    ) {
        /**
         * What actually stops a transcription, as opposed to what stops
         * *Android's own* recogniser.
         *
         * [transcribeUnavailable] only ever described the system engine. Once
         * the user has picked someone else's, its absence is not an obstacle —
         * gating on it directly is what made a chosen third-party engine
         * unusable while sitting right there in the list.
         */
        val transcribeBlocked: OnDeviceTranscriber.Reason?
            get() = if (selectedEngine != null) null else transcribeUnavailable

        /**
         * True when there is a decision to make. A phone offering only the
         * system engine gets no row; a phone offering anything else does —
         * *including* one where the system engine is missing, which is exactly
         * the phone whose owner most needs to reach the alternative.
         */
        val hasEngineChoice: Boolean
            get() = engines.any { it.component != null }

        /** Whether Android's own on-device recogniser is one of the options. */
        val systemEngineAvailable: Boolean
            get() = engines.any { it.component == null }
    }

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
                    autoTranscribe = transcriber.autoTranscribe,
                    loading = false,
                )
                refreshLanguageSupport()
            } else {
                defs.forEach { values[it.id] = midpoint(it) }
                _state.value = State(
                    nightMs = presetNightMs.takeIf { it > 0L }
                        ?: DreamsRepository.nightOf(System.currentTimeMillis()),
                    definitions = defs,
                    allTags = tags,
                    transcribeUnavailable = transcriber.availability(),
                    autoTranscribe = transcriber.autoTranscribe,
                    loading = false,
                )
                refreshLanguageSupport()
            }
        }
    }

    /**
     * A recogniser that exists but has never been given this language looks
     * exactly like a working one until the first transcription fails. Asking up
     * front is what turns « le modèle n'est pas installé » from a dead end into
     * a button.
     */
    private fun refreshLanguageSupport(tag: String = Locale.getDefault().toLanguageTag()) {
        viewModelScope.launch {
            val downloadable =
                transcriber.languageSupport(tag) is OnDeviceTranscriber.LanguageSupport.Downloadable
            _state.value = _state.value.copy(modelDownloadable = downloadable)
            refreshEngines()
        }
    }

    /** A transcript the user wrote or corrected themselves. */
    fun writeTranscript(audio: DreamAudio, text: String) {
        viewModelScope.launch {
            runCatching { repo.setTranscript(audio.id, text.ifBlank { null }) }
            _state.value = _state.value.copy(
                transcribeFailed = false,
                audio = runCatching { repo.audioFor(dreamId) }.getOrDefault(_state.value.audio),
            )
        }
    }

    fun refreshEngines() {
        _state.value = _state.value.copy(
            engines = transcriber.engines(),
            selectedEngine = transcriber.selectedEngine(),
            autoTranscribe = transcriber.autoTranscribe,
        )
    }

    fun setAutoTranscribe(on: Boolean) {
        transcriber.autoTranscribe = on
        _state.value = _state.value.copy(autoTranscribe = on)
    }

    fun selectEngine(engine: OnDeviceTranscriber.Engine?) {
        transcriber.selectedEngineId = engine?.id
        _state.value = _state.value.copy(selectedEngine = transcriber.selectedEngine())
    }

    fun downloadModel(tag: String) {
        if (_state.value.downloadingModel) return
        viewModelScope.launch {
            _state.value = _state.value.copy(downloadingModel = true)
            val ok = transcriber.downloadLanguage(tag)
            _state.value = _state.value.copy(
                downloadingModel = false,
                // Clear the stale reason so the transcribe buttons come back
                // without having to leave and re-open the dream.
                transcribeUnavailable = if (ok) null else _state.value.transcribeUnavailable,
            )
            refreshLanguageSupport(tag)
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
            if (autoTranscribe && _state.value.transcribeBlocked == null) {
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
        _state.value = _state.value.copy(transcribing = audio.id, transcribeFailed = false)
        try {
            when (val r = transcriber.transcribe(
                audio = plaintext,
                languageTag = languageTag,
                engine = _state.value.selectedEngine,
            )) {
                is OnDeviceTranscriber.Result.Text -> {
                    runCatching { repo.setTranscript(audio.id, r.transcript) }
                }
                is OnDeviceTranscriber.Result.Unavailable -> {
                    _state.value = _state.value.copy(
                        transcribeUnavailable = r.reason,
                        // A chosen engine reporting Unavailable is masked by
                        // transcribeBlocked, which is right — the *system*
                        // engine's absence is not the story — but it would
                        // leave the failure with nowhere to appear.
                        transcribeFailed = _state.value.selectedEngine != null,
                    )
                }
                // Both used to land in `else -> Unit`: the spinner stopped, no
                // text arrived, and nothing said why. That is indistinguishable
                // from the feature not working at all.
                is OnDeviceTranscriber.Result.Failed,
                is OnDeviceTranscriber.Result.NoSpeech -> {
                    _state.value = _state.value.copy(transcribeFailed = true)
                }
            }
        } finally {
            runCatching { plaintext.delete() }
            _state.value = _state.value.copy(
                transcribing = null,
                audio = runCatching { repo.audioFor(dreamId) }.getOrDefault(_state.value.audio),
            )
        }
    }

    /**
     * Play or stop a voice note.
     *
     * This is the fallback that makes the whole feature usable on a phone with
     * no on-device recogniser: without playback, a recording made there could
     * never be got back out. Tapping the one already sounding stops it.
     */
    fun togglePlayback(audio: DreamAudio) {
        viewModelScope.launch {
            if (_state.value.playing == audio.id) {
                repo.stopPlayback()
                _state.value = _state.value.copy(playing = null)
                return@launch
            }
            _state.value = _state.value.copy(playing = audio.id)
            val started = runCatching {
                repo.play(audio) {
                    // Completion arrives off the composition; bounce it back
                    // onto the VM so the button returns to "play".
                    _state.value = _state.value.copy(playing = null)
                }
            }.getOrDefault(false)
            if (!started) _state.value = _state.value.copy(playing = null)
        }
    }

    override fun onCleared() {
        // Leaving the screen has to silence it: a dream reading itself aloud
        // from a screen the user has already left is the exact opposite of
        // what this app is for.
        runCatching { repo.stopPlayback() }
        super.onCleared()
    }

    fun deleteAudio(audio: DreamAudio) {
        viewModelScope.launch {
            if (_state.value.playing == audio.id) {
                repo.stopPlayback()
                _state.value = _state.value.copy(playing = null)
            }
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
    // Read from and written back to the store, not remembered: rememberSaveable
    // survives rotation but not leaving the screen, so the switch silently
    // returned to "on" for every new dream.
    val autoTranscribe = state.autoTranscribe

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
                    playing = state.playing == clip.id,
                    transcribing = state.transcribing == clip.id,
                    canTranscribe = state.transcribeBlocked == null,
                    onPlay = { vm.togglePlayback(clip) },
                    onTranscribe = { vm.transcribeExisting(clip, locale.toLanguageTag()) },
                    onWriteTranscript = { vm.writeTranscript(clip, it) },
                    onDelete = { vm.deleteAudio(clip) },
                )
            }

            // The engine row. Shown whenever there is a choice to make — a
            // phone with only the system engine gets no decision it cannot act
            // on.
            if (state.hasEngineChoice) {
                var picking by remember { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.dreams_engine_title),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        MicroLabel(
                            state.selectedEngine?.label ?: stringResource(
                                // "Moteur du système" as the fallback label was
                                // a lie on the phone that most needs this row:
                                // one with no system recogniser at all, where
                                // nothing is selected and nothing is running.
                                if (state.systemEngineAvailable) {
                                    R.string.dreams_engine_system
                                } else {
                                    R.string.dreams_engine_unset
                                }
                            )
                        )
                    }
                    TextButton(onClick = { picking = true }) {
                        Text(stringResource(R.string.dreams_engine_change))
                    }
                }
                // Stated on the row, not only in the dialog: the person who
                // needs it most is the one who chose weeks ago and has since
                // forgotten that recordings leave for another app.
                if (state.selectedEngine != null) {
                    MicroLabel(stringResource(R.string.dreams_engine_third_party_warning))
                }
                if (picking) {
                    EngineDialog(
                        engines = state.engines,
                        selected = state.selectedEngine,
                        onPick = { vm.selectEngine(it); picking = false },
                        onDismiss = { picking = false },
                    )
                }
            }

            if (state.transcribeFailed) {
                MicroLabel(
                    stringResource(
                        if (state.selectedEngine != null) {
                            R.string.dreams_transcribe_failed_engine
                        } else {
                            R.string.dreams_transcribe_failed
                        }
                    )
                )
            }

            // Two things can put a card here: a hard stop (no recogniser, too
            // old an Android) or a missing model, which is not a stop at all —
            // the phone can fetch it. Only the second gets a button, and it is
            // the case that actually occurs on a current phone.
            val offerDownload = state.modelDownloadable ||
                state.transcribeBlocked == OnDeviceTranscriber.Reason.LanguageNotDownloaded
            if (state.transcribeBlocked != null || offerDownload) {
                EggCard(variant = CardVariant.Outlined) {
                    Text(
                        stringResource(
                            when {
                                offerDownload -> R.string.dreams_transcribe_no_language
                                state.transcribeBlocked ==
                                    OnDeviceTranscriber.Reason.ApiTooOld ->
                                    R.string.dreams_transcribe_api_too_old
                                else -> R.string.dreams_transcribe_no_recognizer
                            }
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // No offline engine at all. The app cannot install one —
                    // it comes from the system's speech service — but it can
                    // stop pretending the user knows where that lives. Three
                    // levels deep in Settings is not findable by description.
                    if (!offerDownload &&
                        state.transcribeBlocked == OnDeviceTranscriber.Reason.NoRecognizer
                    ) {
                        // Answers the question the empty screen otherwise
                        // leaves open: is my transcription app missing, or is
                        // the app not looking? Only an app publishing a
                        // RecognitionService can be used from here — that is
                        // the only hook Android offers.
                        // Two different situations wearing the same card:
                        // nothing installed anywhere, or something installed
                        // and simply not picked yet. Telling the second one to
                        // go hunting in system settings is useless advice with
                        // the answer sitting one row above.
                        MicroLabel(
                            stringResource(
                                if (state.hasEngineChoice) {
                                    R.string.dreams_engine_pick_one
                                } else {
                                    R.string.dreams_engine_none
                                }
                            )
                        )
                        val ctx = LocalContext.current
                        if (!state.hasEngineChoice) {
                            TextButton(
                                onClick = {
                                    runCatching {
                                        ctx.startActivity(
                                            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                    }
                                }
                            ) { Text(stringResource(R.string.dreams_transcribe_open_settings)) }
                        }
                    }
                    if (offerDownload) {
                        // Says what travels, because "downloads something" and
                        // "sends my dream somewhere" read alike, and this app
                        // spent the whole module promising the second never
                        // happens.
                        MicroLabel(stringResource(R.string.dreams_transcribe_download_hint))
                        TextButton(
                            onClick = { vm.downloadModel(locale.toLanguageTag()) },
                            enabled = !state.downloadingModel,
                        ) {
                            Text(
                                stringResource(
                                    if (state.downloadingModel) {
                                        R.string.dreams_transcribe_downloading
                                    } else {
                                        R.string.dreams_transcribe_download
                                    }
                                )
                            )
                        }
                    }
                }
            }

            if (state.transcribeBlocked == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.dreams_auto_transcribe),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        MicroLabel(stringResource(R.string.dreams_auto_transcribe_hint))
                    }
                    Switch(
                        checked = autoTranscribe,
                        onCheckedChange = { vm.setAutoTranscribe(it) },
                    )
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

/**
 * Pick which engine transcribes a dream.
 *
 * The system entry is listed first and named as guaranteed offline, because
 * that is the only one about which the claim can be made. Every other row
 * carries the warning rather than hiding it behind the choice: by the time
 * someone reads a confirmation dialog they have already decided.
 */
@Composable
private fun EngineDialog(
    engines: List<OnDeviceTranscriber.Engine>,
    selected: OnDeviceTranscriber.Engine?,
    onPick: (OnDeviceTranscriber.Engine?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dreams_engine_title)) },
        text = {
            Column {
                engines.forEach { engine ->
                    val isSelected = engine.component == selected?.component
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(engine.component?.let { engine }) }
                            .padding(vertical = 10.dp),
                    ) {
                        RadioButton(selected = isSelected, onClick = {
                            onPick(engine.component?.let { engine })
                        })
                        Column(modifier = Modifier.weight(1f)) {
                            Text(engine.label, style = MaterialTheme.typography.bodyMedium)
                            if (!engine.onDeviceGuaranteed) {
                                MicroLabel(
                                    stringResource(R.string.dreams_engine_third_party_warning)
                                )
                                // The nastiest failure this can produce, said
                                // before the choice rather than after. Android
                                // documents that a recogniser which cannot read
                                // the supplied audio opens the microphone
                                // instead — so a wrong engine does not error,
                                // it returns a fluent transcript of the room
                                // and files it under a dream.
                                MicroLabel(stringResource(R.string.dreams_engine_file_caveat))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) }
        },
    )
}

@Composable
private fun AudioRow(
    clip: DreamAudio,
    playing: Boolean,
    transcribing: Boolean,
    canTranscribe: Boolean,
    onPlay: () -> Unit,
    onTranscribe: () -> Unit,
    onWriteTranscript: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var editing by remember(clip.id) { mutableStateOf(false) }
    EggCard(
        variant = CardVariant.Low,
        padding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPlay, modifier = Modifier.size(EggDim.TouchTarget)) {
                Icon(
                    if (playing) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(
                        if (playing) R.string.dreams_audio_stop else R.string.dreams_audio_play
                    ),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                formatDuration(clip.durationMs),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .padding(start = 4.dp)
                    .weight(1f),
            )
            // Always available, engine or no engine. A keyboard with voice
            // input — Whisper, Gboard, anything — dictates straight into the
            // field, which is the one path Android leaves open when the
            // transcription app is an IME: no app may invoke another's
            // keyboard, but every text field can receive one.
            if (clip.transcript == null && !canTranscribe) {
                TextButton(onClick = { editing = true }) {
                    Icon(
                        Icons.Filled.Subject,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        stringResource(R.string.dreams_transcript_write),
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
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
                modifier = Modifier
                    .padding(top = 8.dp)
                    .clickable { editing = true },
            )
            // Editable even when a machine wrote it. On-device models are the
            // less accurate ones, and a dream is exactly the material they get
            // wrong — a transcript you cannot correct is one you stop trusting.
            MicroLabel(stringResource(R.string.dreams_transcript_edit_hint))
        }
    }

    if (editing) {
        TranscriptDialog(
            initial = clip.transcript.orEmpty(),
            onSave = { onWriteTranscript(it); editing = false },
            onDismiss = { editing = false },
        )
    }
}

/**
 * Write or correct a voice note's transcript by hand.
 *
 * This is what makes the module work on a phone with no usable engine, and it
 * is not a consolation prize: the field takes dictation from whatever keyboard
 * the user already trusts. Android forbids one app from driving another's IME,
 * so a Whisper *keyboard* can never be called by Eggshell — but it can type
 * into it, and nothing decrypted leaves the app either way.
 */
@Composable
private fun TranscriptDialog(
    initial: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dreams_transcript_write)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 140.dp),
                    placeholder = { Text(stringResource(R.string.dreams_transcript_hint)) },
                )
                MicroLabel(stringResource(R.string.dreams_transcript_dictate_hint))
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text.trim()) }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
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
