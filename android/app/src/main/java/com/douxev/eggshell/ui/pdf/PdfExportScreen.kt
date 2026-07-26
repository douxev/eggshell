package com.douxev.eggshell.ui.pdf

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.EnhancedEncryption
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.douxev.eggshell.R
import com.douxev.eggshell.data.AppointmentRepository
import com.douxev.eggshell.data.BleedingRepository
import com.douxev.eggshell.data.HormonesRepository
import com.douxev.eggshell.data.JournalRepository
import com.douxev.eggshell.data.MedicationRepository
import com.douxev.eggshell.data.PdfReportExporter
import com.douxev.eggshell.data.PhotosRepository
import com.douxev.eggshell.data.PlannedDoses
import com.douxev.eggshell.data.SecurePrefs
import com.douxev.eggshell.data.SettingsRepository
import com.douxev.eggshell.data.VoiceRepository
import com.douxev.eggshell.ui.appointments.appointmentTodoItems
import com.douxev.eggshell.ui.common.ScreenHeader
import com.douxev.eggshell.ui.components.ActionBand
import com.douxev.eggshell.ui.components.CardVariant
import com.douxev.eggshell.ui.components.EggCard
import com.douxev.eggshell.ui.components.ErrorCard
import com.douxev.eggshell.ui.components.IconTile
import com.douxev.eggshell.ui.components.ListRow
import com.douxev.eggshell.ui.components.MicroLabel
import com.douxev.eggshell.ui.components.Pill
import com.douxev.eggshell.ui.components.SectionTitle
import com.douxev.eggshell.ui.components.SkeletonBlock
import com.douxev.eggshell.ui.hormones.HormoneCatalog
import com.douxev.eggshell.ui.theme.EggDim
import com.douxev.eggshell.ui.theme.EggShapes
import uniffi.transition.Appointment
import uniffi.transition.BleedingEntry
import uniffi.transition.DoseEvent
import uniffi.transition.HormoneMeasurement
import uniffi.transition.JournalEntry
import uniffi.transition.Medication
import uniffi.transition.PhotoRecord
import uniffi.transition.VoiceClip

/** The five period pills of §6.12. */
enum class ReportPeriod { M1, M3, M6, ALL, CUSTOM }

/**
 * What produced a custom range. It is not a duplicate of [ReportPeriod]: it is
 * what lets the recap line *name* the period (« depuis la dernière
 * consultation ») instead of only dating it.
 */
enum class ReportShortcut { WEEK1, WEEK2, LAST_VISIT, TREATMENT_START, MANUAL }

/** The eight exportable modules, and nothing else (§6.12.4). */
data class ReportModules(
    val medications: Boolean = true,
    val hormones: Boolean = true,
    val weight: Boolean = true,
    val feel: Boolean = true,
    val questions: Boolean = true,
    val bleeding: Boolean = false,
    val voice: Boolean = true,
    /** Never on by default. The only module with that rule (§6.12.4). */
    val photos: Boolean = false,
) {
    val flags: List<Boolean>
        get() = listOf(medications, hormones, weight, feel, questions, bleeding, voice, photos)

    val activeCount: Int get() = flags.count { it }

    /**
     * The estimate the button shows, `1 + ceil(n / 3)` (§6.12.6). It is an
     * estimate on purpose: the renderer knows the real count once it has laid
     * the document out, and that is the one the footer prints.
     */
    val pages: Int get() = 1 + ceil(activeCount / 3.0).toInt()
}

/** How the recap line names the chosen period. */
@StringRes
fun reportOriginRes(period: ReportPeriod, shortcut: ReportShortcut): Int = when (period) {
    ReportPeriod.M1 -> R.string.report_origin_1m
    ReportPeriod.M3 -> R.string.report_origin_3m
    ReportPeriod.M6 -> R.string.report_origin_6m
    ReportPeriod.ALL -> R.string.report_origin_all
    ReportPeriod.CUSTOM -> when (shortcut) {
        ReportShortcut.WEEK1 -> R.string.report_origin_week1
        ReportShortcut.WEEK2 -> R.string.report_origin_week2
        ReportShortcut.LAST_VISIT -> R.string.report_origin_last_visit
        ReportShortcut.TREATMENT_START -> R.string.report_origin_treatment
        ReportShortcut.MANUAL -> R.string.report_origin_manual
    }
}

/**
 * The export configuration, remembered between visits.
 *
 * Before the refonte nothing was persisted: every visit reset the period and
 * re-checked every module, which is exactly the kind of default that gets a
 * photo into a document by accident. Only choices are stored — never a count,
 * never a date of anything that happened, only the two bounds the user picked.
 */
@Singleton
class ReportPrefs @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences = SecurePrefs.get(context, PREFS_NAME)

    fun period(): ReportPeriod = read(KEY_PERIOD, ReportPeriod.entries, ReportPeriod.M3)

    fun shortcut(): ReportShortcut = read(KEY_SHORTCUT, ReportShortcut.entries, ReportShortcut.LAST_VISIT)

    fun customFromMs(): Long = prefs.getLong(KEY_FROM, 0L)

    fun customToMs(): Long = prefs.getLong(KEY_TO, 0L)

    fun modules(): ReportModules {
        val d = ReportModules()
        return ReportModules(
            medications = prefs.getBoolean(KEY_MEDS, d.medications),
            hormones = prefs.getBoolean(KEY_HORMONES, d.hormones),
            weight = prefs.getBoolean(KEY_WEIGHT, d.weight),
            feel = prefs.getBoolean(KEY_FEEL, d.feel),
            questions = prefs.getBoolean(KEY_QUESTIONS, d.questions),
            bleeding = prefs.getBoolean(KEY_BLEEDING, d.bleeding),
            voice = prefs.getBoolean(KEY_VOICE, d.voice),
            photos = prefs.getBoolean(KEY_PHOTOS, d.photos),
        )
    }

    fun savePeriod(period: ReportPeriod, shortcut: ReportShortcut, fromMs: Long, toMs: Long) {
        prefs.edit()
            .putString(KEY_PERIOD, period.name)
            .putString(KEY_SHORTCUT, shortcut.name)
            .putLong(KEY_FROM, fromMs)
            .putLong(KEY_TO, toMs)
            .apply()
    }

    fun saveModules(modules: ReportModules) {
        prefs.edit()
            .putBoolean(KEY_MEDS, modules.medications)
            .putBoolean(KEY_HORMONES, modules.hormones)
            .putBoolean(KEY_WEIGHT, modules.weight)
            .putBoolean(KEY_FEEL, modules.feel)
            .putBoolean(KEY_QUESTIONS, modules.questions)
            .putBoolean(KEY_BLEEDING, modules.bleeding)
            .putBoolean(KEY_VOICE, modules.voice)
            .putBoolean(KEY_PHOTOS, modules.photos)
            .apply()
    }

    private fun <T : Enum<T>> read(key: String, values: List<T>, fallback: T): T {
        val stored = prefs.getString(key, null) ?: return fallback
        return values.firstOrNull { it.name == stored } ?: fallback
    }

    private companion object {
        const val PREFS_NAME = "eggshell_report_export"
        const val KEY_PERIOD = "period"
        const val KEY_SHORTCUT = "shortcut"
        const val KEY_FROM = "from"
        const val KEY_TO = "to"
        const val KEY_MEDS = "m_meds"
        const val KEY_HORMONES = "m_hormones"
        const val KEY_WEIGHT = "m_weight"
        const val KEY_FEEL = "m_feel"
        const val KEY_QUESTIONS = "m_questions"
        const val KEY_BLEEDING = "m_bleeding"
        const val KEY_VOICE = "m_voice"
        const val KEY_PHOTOS = "m_photos"
    }
}

/** How much of each module the chosen period actually holds. */
data class ReportVolumes(
    val molecules: Int = 0,
    val doses: Int = 0,
    val labs: Int = 0,
    val weights: Int = 0,
    val feelEntries: Int = 0,
    val bleedingDays: Int = 0,
    val clips: Int = 0,
    val photos: Int = 0,
    /** Ticked-off items of the next appointment — not a window figure. */
    val questions: Int = 0,
    val questionsAtMs: Long? = null,
    val days: Int = 0,
)

@HiltViewModel
class PdfExportViewModel @Inject constructor(
    private val exporter: PdfReportExporter,
    private val prefs: ReportPrefs,
    private val appointments: AppointmentRepository,
    private val medications: MedicationRepository,
    private val hormones: HormonesRepository,
    private val journals: JournalRepository,
    private val bleeding: BleedingRepository,
    private val photos: PhotosRepository,
    private val voice: VoiceRepository,
    private val plannedDoses: PlannedDoses,
    private val settings: SettingsRepository,
) : ViewModel() {

    /**
     * Everything the screen counts, read once. Every repository but the dose
     * log answers whole lists, so windowing in memory is both cheaper and
     * instant — which is what makes the sheet's figures follow the dates as
     * the user drags them.
     */
    private data class Corpus(
        val medications: List<Medication> = emptyList(),
        val labs: List<HormoneMeasurement> = emptyList(),
        val weights: List<HormoneMeasurement> = emptyList(),
        val journal: List<JournalEntry> = emptyList(),
        val bleeding: List<BleedingEntry> = emptyList(),
        val clips: List<VoiceClip> = emptyList(),
        val photos: List<PhotoRecord> = emptyList(),
        val appointments: List<Appointment> = emptyList(),
    )

    data class State(
        val loading: Boolean = true,
        val period: ReportPeriod = ReportPeriod.M3,
        val shortcut: ReportShortcut = ReportShortcut.LAST_VISIT,
        val fromMs: Long = 0L,
        val toMs: Long = 0L,
        val modules: ReportModules = ReportModules(),
        /**
         * The two fields of the document's identity box, read from the vault.
         * They live in this state and nowhere else — no copy of them reaches a
         * preference file.
         */
        val identityName: String? = null,
        val identityBirth: LocalDate? = null,
        val volumes: ReportVolumes = ReportVolumes(),
        /** Live figures for the range being drafted in the sheet. */
        val draftVolumes: ReportVolumes? = null,
        /** Last appointment already past — anchors « depuis la dernière consultation ». */
        val lastVisitMs: Long? = null,
        /** Oldest treatment — anchors « depuis le début du traitement ». */
        val treatmentStartMs: Long? = null,
        val generating: Boolean = false,
        val error: String? = null,
        val generatedFile: File? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var corpus = Corpus()
    private var earliestMs: Long? = null
    private val zone: ZoneId = ZoneId.systemDefault()

    init { load() }

    fun load() {
        viewModelScope.launch {
            corpus = readCorpus()
            val now = System.currentTimeMillis()
            val lastVisit = corpus.appointments.filter { it.atMs <= now }.maxOfOrNull { it.atMs }
            val treatmentStart = corpus.medications.minOfOrNull { it.createdAtMs }
            earliestMs = listOfNotNull(
                treatmentStart,
                corpus.labs.minOfOrNull { it.atMs },
                corpus.weights.minOfOrNull { it.atMs },
                corpus.journal.minOfOrNull { it.atMs },
                corpus.bleeding.minOfOrNull { it.atMs },
                corpus.clips.minOfOrNull { it.atMs },
                corpus.photos.minOfOrNull { it.atMs },
            ).minOrNull()

            val storedFrom = prefs.customFromMs()
            val storedTo = prefs.customToMs()
            val hasStoredRange = storedFrom in 1 until storedTo
            // A custom period with no usable bounds left is not a period at
            // all — fall back to the default preset rather than to 1970.
            val period = prefs.period().let {
                if (it == ReportPeriod.CUSTOM && !hasStoredRange) ReportPeriod.M3 else it
            }
            val shortcut = prefs.shortcut()
            val (from, to) = if (period == ReportPeriod.CUSTOM) {
                storedFrom to storedTo
            } else {
                rangeFor(period, now)
            }
            _state.value = _state.value.copy(
                loading = false,
                period = period,
                shortcut = shortcut,
                fromMs = from,
                toMs = to,
                modules = prefs.modules(),
                lastVisitMs = lastVisit,
                treatmentStartMs = treatmentStart,
            )
            readIdentity()
            refreshVolumes()
        }
    }

    /**
     * Commit the identity sheet. A blank name or a missing date is stored as an
     * *absence* — [SettingsRepository] deletes the key rather than writing an
     * empty string, so the document's both-or-nothing rule reads the vault the
     * same way whether a field was never filled or emptied afterwards.
     */
    fun saveIdentity(name: String?, birth: LocalDate?) {
        viewModelScope.launch {
            runCatching {
                settings.setReportPersonName(name)
                settings.setReportBirthDate(birth)
            }
            readIdentity()
        }
    }

    fun clearIdentity() {
        viewModelScope.launch {
            runCatching { settings.clearReportIdentity() }
            readIdentity()
        }
    }

    /**
     * The vault is the truth, so the row is refreshed *from* it rather than from
     * what was just handed to the writer: a write that failed can then never
     * leave the screen promising a name the document has never seen.
     */
    private suspend fun readIdentity() {
        _state.value = _state.value.copy(
            identityName = runCatching { settings.reportPersonName() }.getOrNull(),
            identityBirth = runCatching { settings.reportBirthDate() }.getOrNull(),
        )
    }

    /** One of the four immediate pills — « Personnalisé » goes through the sheet. */
    fun setPeriod(period: ReportPeriod) {
        if (period == ReportPeriod.CUSTOM) return
        val (from, to) = rangeFor(period, System.currentTimeMillis())
        prefs.savePeriod(period, ReportShortcut.MANUAL, from, to)
        _state.value = _state.value.copy(period = period, fromMs = from, toMs = to)
        refreshVolumes()
    }

    /** Commit the sheet: the range becomes the period, and the pill moves. */
    fun applyCustom(fromMs: Long, toMs: Long, shortcut: ReportShortcut) {
        val from = minOf(fromMs, toMs)
        val to = maxOf(fromMs, toMs)
        prefs.savePeriod(ReportPeriod.CUSTOM, shortcut, from, to)
        _state.value = _state.value.copy(
            period = ReportPeriod.CUSTOM,
            shortcut = shortcut,
            fromMs = from,
            toMs = to,
            draftVolumes = null,
        )
        refreshVolumes()
    }

    /** Figures for a range the user is still drafting; nothing is committed. */
    fun previewRange(fromMs: Long, toMs: Long) {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                draftVolumes = volumesFor(minOf(fromMs, toMs), maxOf(fromMs, toMs)),
            )
        }
    }

    fun clearDraft() {
        _state.value = _state.value.copy(draftVolumes = null)
    }

    fun setModules(modules: ReportModules) {
        prefs.saveModules(modules)
        _state.value = _state.value.copy(modules = modules)
    }

    /** Bounds of a preset, as the recap line and the volumes both read them. */
    private fun rangeFor(period: ReportPeriod, now: Long): Pair<Long, Long> {
        val end = Instant.ofEpochMilli(now).atZone(zone)
        val start = when (period) {
            ReportPeriod.M1 -> end.minusMonths(1)
            ReportPeriod.M3 -> end.minusMonths(3)
            ReportPeriod.M6 -> end.minusMonths(6)
            // D3: « Tout » is genuinely unbounded. The oldest datum only dates
            // the recap line — the document itself is never cut off.
            ReportPeriod.ALL -> Instant.ofEpochMilli(earliestMs ?: now).atZone(zone)
            ReportPeriod.CUSTOM -> Instant.ofEpochMilli(_state.value.fromMs).atZone(zone)
        }
        return start.toInstant().toEpochMilli() to now
    }

    fun generate() {
        val snapshot = _state.value
        viewModelScope.launch {
            _state.value = snapshot.copy(generating = true, error = null)
            runCatching {
                exporter.generate(
                    PdfReportExporter.Options(
                        fromMs = snapshot.fromMs,
                        toMs = snapshot.toMs,
                        treatments = snapshot.modules.medications,
                        hormones = snapshot.modules.hormones,
                        weight = snapshot.modules.weight,
                        feelings = snapshot.modules.feel,
                        questions = snapshot.modules.questions,
                        bleeding = snapshot.modules.bleeding,
                        voice = snapshot.modules.voice,
                        photos = snapshot.modules.photos,
                    )
                )
            }
                .onSuccess { _state.value = _state.value.copy(generating = false, generatedFile = it) }
                .onFailure { _state.value = _state.value.copy(generating = false, error = it.message ?: "") }
        }
    }

    fun consumeGeneratedFile(): File? {
        val f = _state.value.generatedFile
        _state.value = _state.value.copy(generatedFile = null)
        return f
    }

    fun onShareFailed(message: String?) {
        _state.value = _state.value.copy(generatedFile = null, error = message ?: "")
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    private fun refreshVolumes() {
        viewModelScope.launch {
            val s = _state.value
            _state.value = _state.value.copy(volumes = volumesFor(s.fromMs, s.toMs))
        }
    }

    private suspend fun volumesFor(fromMs: Long, toMs: Long): ReportVolumes {
        val range = fromMs..toMs
        val window = runCatching { plannedDoses.window(fromMs, toMs) }.getOrNull()
        val logged: List<DoseEvent> = window?.let { w ->
            w.occurrences.mapNotNull { it.event } +
                w.offGrid.mapNotNull { it.event } +
                w.withoutPlannedTime
        } ?: emptyList()
        val covered = buildSet {
            window?.occurrences?.forEach { add(it.medicationId) }
            logged.forEach { add(it.medicationId) }
        }
        val now = System.currentTimeMillis()
        val next = corpus.appointments.filter { it.atMs > now }.minByOrNull { it.atMs }
        return ReportVolumes(
            molecules = covered.size,
            doses = logged.size,
            labs = corpus.labs.count { it.atMs in range },
            weights = corpus.weights.count { it.atMs in range },
            feelEntries = corpus.journal.count { it.atMs in range },
            bleedingDays = corpus.bleeding
                .filter { it.atMs in range }
                .map { Instant.ofEpochMilli(it.atMs).atZone(zone).toLocalDate() }
                .distinct()
                .size,
            clips = corpus.clips.count { it.atMs in range },
            photos = corpus.photos.count { it.atMs in range },
            questions = appointmentTodoItems(next?.todo).size,
            questionsAtMs = next?.atMs,
            days = ChronoUnit.DAYS.between(
                Instant.ofEpochMilli(fromMs).atZone(zone).toLocalDate(),
                Instant.ofEpochMilli(toMs).atZone(zone).toLocalDate(),
            ).toInt().coerceAtLeast(0),
        )
    }

    private suspend fun readCorpus(): Corpus {
        val analytes = runCatching { hormones.distinct() }.getOrDefault(emptyList())
        val measurements = analytes.associateWith { analyte ->
            runCatching { hormones.listForHormone(analyte, 0, MEASURE_LIMIT) }.getOrDefault(emptyList())
        }
        return Corpus(
            medications = runCatching { medications.list(includeArchived = true) }.getOrDefault(emptyList()),
            // Weight shares the hormone storage but is its own section of the
            // document (D3), so the two never share a count either.
            labs = measurements.filterKeys { it != HormoneCatalog.WEIGHT }.values.flatten(),
            weights = measurements[HormoneCatalog.WEIGHT].orEmpty(),
            journal = runCatching { journals.list(0, ENTRY_LIMIT) }.getOrDefault(emptyList()),
            bleeding = runCatching { bleeding.list(0, ENTRY_LIMIT) }.getOrDefault(emptyList()),
            clips = runCatching { voice.list() }.getOrDefault(emptyList()),
            photos = runCatching { photos.list(0, ENTRY_LIMIT) }.getOrDefault(emptyList()),
            appointments = runCatching { appointments.list(0, ENTRY_LIMIT) }.getOrDefault(emptyList()),
        )
    }

    private companion object {
        const val MEASURE_LIMIT = 2000L
        const val ENTRY_LIMIT = 2000L
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PdfExportScreen(
    onBack: () -> Unit,
    vm: PdfExportViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    val ctx = LocalContext.current
    var sheetOpen by remember { mutableStateOf(false) }
    var identitySheetOpen by remember { mutableStateOf(false) }

    // Whenever a fresh PDF lands, hand it off to the system share sheet. This
    // runs in a LaunchedEffect (not the composition body) so it fires once per
    // generated file instead of on every recomposition, and any failure
    // (misconfigured FileProvider path, no app to receive the share) surfaces
    // as an inline error instead of crashing the screen.
    val pendingFile = state.generatedFile
    LaunchedEffect(pendingFile) {
        val file = pendingFile ?: return@LaunchedEffect
        runCatching {
            val uri = FileProvider.getUriForFile(
                ctx, "${ctx.packageName}.fileprovider", file,
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(Intent.createChooser(send, null))
            vm.consumeGeneratedFile()
        }.onFailure { vm.onShareFailed(it.message) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        bottomBar = {
            ActionBand(alignment = Alignment.Center) {
                GenerateButton(
                    modules = state.modules,
                    generating = state.generating,
                    onClick = vm::generate,
                )
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(
                start = EggDim.ScreenMargin,
                end = EggDim.ScreenMargin,
                top = 4.dp,
                bottom = EggDim.BlockGap,
            ),
            verticalArrangement = Arrangement.spacedBy(EggDim.BlockGap),
        ) {
            item {
                ScreenHeader(title = stringResource(R.string.report_title), onBack = onBack)
            }

            item { IntentCard() }

            // Deliberately here and not in Réglages: identifying data belongs
            // next to the decision to hand a document over, not in a list of
            // app-wide preferences where it would quietly become a default.
            item {
                if (state.loading) {
                    SkeletonBlock(height = 64.dp, shape = EggShapes.ListRow)
                } else {
                    IdentityRow(
                        name = state.identityName,
                        birth = state.identityBirth,
                        onClick = { identitySheetOpen = true },
                    )
                }
            }

            // §6.12: the order is imposed — the period first, the content next.
            item { SectionTitle(stringResource(R.string.report_section_period)) }

            item {
                PeriodPills(
                    selected = state.period,
                    onSelect = vm::setPeriod,
                    onCustom = { sheetOpen = true },
                )
            }

            item {
                if (state.loading) {
                    SkeletonBlock(height = 64.dp, shape = EggShapes.Note)
                } else {
                    PeriodRecap(
                        fromMs = state.fromMs,
                        toMs = state.toMs,
                        days = state.volumes.days,
                        originRes = reportOriginRes(state.period, state.shortcut),
                        onChange = { sheetOpen = true },
                    )
                }
            }

            item {
                // « Tout cocher » deliberately stops short of Photos: forcing
                // them on would contradict « Jamais incluses par défaut ». Once
                // the other seven are on, the action flips to « Tout décocher »
                // and that one *does* clear everything, Photos included.
                val everythingButPhotos = state.modules.copy(photos = false) == ALL_BUT_PHOTOS
                SectionTitle(
                    text = stringResource(R.string.report_section_content),
                    action = stringResource(
                        if (everythingButPhotos) R.string.report_uncheck_all else R.string.report_check_all
                    ),
                    onAction = {
                        vm.setModules(
                            if (everythingButPhotos) NOTHING else state.modules.copy(
                                medications = true, hormones = true, weight = true,
                                feel = true, questions = true, bleeding = true, voice = true,
                            )
                        )
                    },
                )
            }

            item {
                if (state.loading) {
                    SkeletonBlock(height = 420.dp, shape = EggShapes.Card)
                } else {
                    ModuleCard(
                        modules = state.modules,
                        volumes = state.volumes,
                        onChange = vm::setModules,
                    )
                }
            }

            item { OfflineNote() }

            if (state.error != null) {
                item {
                    // The exception text stays out of the UI: it can name a
                    // cache path, and this screen is one screenshot away from
                    // being shown to someone else.
                    ErrorCard(
                        message = stringResource(R.string.report_error),
                        retryLabel = stringResource(R.string.report_retry),
                        onRetry = {
                            vm.dismissError()
                            vm.generate()
                        },
                    )
                }
            }
        }
    }

    if (sheetOpen) {
        CustomPeriodSheet(
            state = state,
            onPreview = vm::previewRange,
            onDismiss = {
                sheetOpen = false
                vm.clearDraft()
            },
            onApply = { from, to, shortcut ->
                sheetOpen = false
                vm.applyCustom(from, to, shortcut)
            },
        )
    }

    if (identitySheetOpen) {
        IdentitySheet(
            name = state.identityName,
            birth = state.identityBirth,
            onDismiss = { identitySheetOpen = false },
            onErase = {
                identitySheetOpen = false
                vm.clearIdentity()
            },
            onApply = { name, birth ->
                identitySheetOpen = false
                vm.saveIdentity(name, birth)
            },
        )
    }
}

// ---------------------------------------------------------------------------
// Blocks
// ---------------------------------------------------------------------------

/** Every module but Photos — what « Tout cocher » is allowed to reach. */
private val ALL_BUT_PHOTOS = ReportModules(bleeding = true, photos = false)

private val NOTHING = ReportModules(
    medications = false, hormones = false, weight = false, feel = false,
    questions = false, bleeding = false, voice = false, photos = false,
)

@Composable
private fun IntentCard() {
    EggCard(variant = CardVariant.Primary) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Filled.PictureAsPdf,
                contentDescription = null,
                modifier = Modifier.size(26.dp),
            )
            Column {
                Text(
                    stringResource(R.string.report_intro_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.report_intro_sub),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current.copy(alpha = 0.82f),
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}

/**
 * « Identité sur le rapport ».
 *
 * The subtitle is the state, spelled out: the two values when the document will
 * carry them, and otherwise a sentence saying the report stays silent. §7.4.2
 * prints the box only when both fields are filled, so a half-filled pair says
 * so instead of letting the user believe a name alone will appear.
 */
@Composable
private fun IdentityRow(
    name: String?,
    birth: LocalDate?,
    onClick: () -> Unit,
) {
    val subtitle = when {
        name != null && birth != null ->
            stringResource(R.string.report_identity_set_fmt, name, longDayLabel(birth))
        name != null || birth != null -> stringResource(R.string.report_identity_partial)
        else -> stringResource(R.string.report_identity_none)
    }
    ListRow(
        title = stringResource(R.string.report_identity_row),
        subtitle = subtitle,
        leading = {
            IconTile {
                Icon(
                    Icons.Filled.Badge,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        onClick = onClick,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PeriodPills(
    selected: ReportPeriod,
    onSelect: (ReportPeriod) -> Unit,
    onCustom: () -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        val presets = listOf(
            ReportPeriod.M1 to R.string.report_period_1m,
            ReportPeriod.M3 to R.string.report_period_3m,
            ReportPeriod.M6 to R.string.report_period_6m,
            ReportPeriod.ALL to R.string.report_period_all,
        )
        presets.forEach { (period, labelRes) ->
            Pill(
                label = stringResource(labelRes),
                selected = selected == period,
                onClick = { onSelect(period) },
            )
        }
        Pill(
            label = stringResource(R.string.report_period_custom),
            selected = selected == ReportPeriod.CUSTOM,
            onClick = onCustom,
        )
    }
}

/**
 * The dated recap, visible for every preset — not only for « Personnalisé ».
 * It is what makes the pills honest: a period is a pair of dates, and the user
 * gets to read them before the document is made.
 */
@Composable
private fun PeriodRecap(
    fromMs: Long,
    toMs: Long,
    days: Int,
    @StringRes originRes: Int,
    onChange: () -> Unit,
) {
    Surface(
        onClick = onChange,
        modifier = Modifier.fillMaxWidth(),
        shape = EggShapes.Note,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = EggDim.TouchTarget)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.CalendarMonth,
                contentDescription = null,
                modifier = Modifier.size(19.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    rangeLabel(fromMs, toMs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stringResource(
                        R.string.report_recap_fmt,
                        pluralStringResource(R.plurals.report_count_days, days, days),
                        stringResource(originRes),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                stringResource(R.string.report_change),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ModuleCard(
    modules: ReportModules,
    volumes: ReportVolumes,
    onChange: (ReportModules) -> Unit,
) {
    EggCard(
        variant = CardVariant.Low,
        padding = PaddingValues(horizontal = 18.dp, vertical = 6.dp),
    ) {
        val rows = moduleRows(modules, volumes, onChange)
        rows.forEachIndexed { index, row ->
            ModuleRow(row)
            if (index != rows.lastIndex) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
            }
        }
    }
}

/** One switch line: what it is, and how much of it the period actually holds. */
private data class ModuleRowSpec(
    val title: String,
    val subtitle: String,
    val subtitleColor: Color?,
    val checked: Boolean,
    val onCheckedChange: (Boolean) -> Unit,
)

@Composable
private fun moduleRows(
    modules: ReportModules,
    volumes: ReportVolumes,
    onChange: (ReportModules) -> Unit,
): List<ModuleRowSpec> {
    val none = stringResource(R.string.report_empty_period)

    fun volumeOr(count: Int, text: String): String = if (count == 0) none else text

    val questionsSub = when {
        volumes.questionsAtMs == null -> stringResource(R.string.report_mod_questions_none)
        volumes.questions == 0 -> none
        else -> stringResource(
            R.string.report_mod_questions_sub_fmt,
            pluralStringResource(R.plurals.report_count_notes, volumes.questions, volumes.questions),
            dayMonthLabel(volumes.questionsAtMs),
        )
    }

    return listOf(
        ModuleRowSpec(
            title = stringResource(R.string.report_mod_meds),
            subtitle = volumeOr(
                volumes.molecules,
                pluralStringResource(R.plurals.report_count_molecules, volumes.molecules, volumes.molecules),
            ),
            subtitleColor = null,
            checked = modules.medications,
            onCheckedChange = { onChange(modules.copy(medications = it)) },
        ),
        ModuleRowSpec(
            title = stringResource(R.string.report_mod_hormones),
            subtitle = volumeOr(
                volumes.labs,
                pluralStringResource(R.plurals.report_count_labs_sub, volumes.labs, volumes.labs),
            ),
            subtitleColor = null,
            checked = modules.hormones,
            onCheckedChange = { onChange(modules.copy(hormones = it)) },
        ),
        ModuleRowSpec(
            title = stringResource(R.string.report_mod_weight),
            subtitle = volumeOr(
                volumes.weights,
                pluralStringResource(R.plurals.report_count_weights, volumes.weights, volumes.weights),
            ),
            subtitleColor = null,
            checked = modules.weight,
            onCheckedChange = { onChange(modules.copy(weight = it)) },
        ),
        ModuleRowSpec(
            title = stringResource(R.string.report_mod_feel),
            // Not a volume but a promise: the free text never leaves the app.
            subtitle = volumeOr(volumes.feelEntries, stringResource(R.string.report_mod_feel_sub)),
            subtitleColor = null,
            checked = modules.feel,
            onCheckedChange = { onChange(modules.copy(feel = it)) },
        ),
        ModuleRowSpec(
            title = stringResource(R.string.report_mod_questions),
            subtitle = questionsSub,
            subtitleColor = null,
            checked = modules.questions,
            onCheckedChange = { onChange(modules.copy(questions = it)) },
        ),
        ModuleRowSpec(
            title = stringResource(R.string.report_mod_bleeding),
            subtitle = volumeOr(
                volumes.bleedingDays,
                pluralStringResource(
                    R.plurals.report_count_bleeding_days, volumes.bleedingDays, volumes.bleedingDays,
                ),
            ),
            subtitleColor = null,
            checked = modules.bleeding,
            onCheckedChange = { onChange(modules.copy(bleeding = it)) },
        ),
        ModuleRowSpec(
            title = stringResource(R.string.report_mod_voice),
            subtitle = volumeOr(
                volumes.clips,
                stringResource(
                    R.string.report_mod_voice_sub_fmt,
                    pluralStringResource(R.plurals.report_count_clips, volumes.clips, volumes.clips),
                ),
            ),
            subtitleColor = null,
            checked = modules.voice,
            onCheckedChange = { onChange(modules.copy(voice = it)) },
        ),
        ModuleRowSpec(
            title = stringResource(R.string.report_mod_photos),
            // Stays red even when the period holds photos: the sentence is the
            // guard-rail, not a count.
            subtitle = stringResource(R.string.report_mod_photos_sub),
            subtitleColor = MaterialTheme.colorScheme.error,
            checked = modules.photos,
            onCheckedChange = { onChange(modules.copy(photos = it)) },
        ),
    )
}

@Composable
private fun ModuleRow(spec: ModuleRowSpec) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = spec.checked,
                onValueChange = spec.onCheckedChange,
                role = Role.Switch,
            )
            .heightIn(min = EggDim.TouchTarget)
            .padding(vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                spec.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                spec.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = spec.subtitleColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // The row owns the gesture, so the switch is decoration for touch and
        // the toggleable node carries the state for TalkBack.
        Switch(checked = spec.checked, onCheckedChange = null)
    }
}

@Composable
private fun OfflineNote() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, EggShapes.Note)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Filled.EnhancedEncryption,
            contentDescription = null,
            modifier = Modifier.size(19.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            stringResource(R.string.report_encrypted_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun GenerateButton(
    modules: ReportModules,
    generating: Boolean,
    onClick: () -> Unit,
) {
    val active = modules.activeCount
    Button(
        onClick = onClick,
        enabled = active > 0 && !generating,
        shape = EggShapes.Pill,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
    ) {
        if (generating) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = MaterialTheme.colorScheme.onPrimary,
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.report_generating), style = MaterialTheme.typography.titleMedium)
        } else {
            Icon(Icons.Filled.PictureAsPdf, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                if (active == 0) {
                    stringResource(R.string.report_nothing)
                } else {
                    pluralStringResource(R.plurals.report_generate_fmt, modules.pages, modules.pages)
                },
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// « Période personnalisée »
// ---------------------------------------------------------------------------

private enum class DateField { From, To }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CustomPeriodSheet(
    state: PdfExportViewModel.State,
    onPreview: (Long, Long) -> Unit,
    onDismiss: () -> Unit,
    onApply: (Long, Long, ReportShortcut) -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }
    val now = remember { System.currentTimeMillis() }

    // « Depuis la dernière consultation » is the default choice (§6.12.3); it
    // falls back to the start of treatment when there is no past appointment.
    val initialShortcut = when {
        state.period == ReportPeriod.CUSTOM -> state.shortcut
        state.lastVisitMs != null -> ReportShortcut.LAST_VISIT
        state.treatmentStartMs != null -> ReportShortcut.TREATMENT_START
        else -> ReportShortcut.MANUAL
    }
    var shortcut by remember { mutableStateOf(initialShortcut) }
    var fromMs by remember {
        mutableStateOf(
            when {
                state.period == ReportPeriod.CUSTOM -> state.fromMs
                initialShortcut == ReportShortcut.LAST_VISIT -> state.lastVisitMs ?: state.fromMs
                initialShortcut == ReportShortcut.TREATMENT_START -> state.treatmentStartMs ?: state.fromMs
                else -> state.fromMs
            }
        )
    }
    var toMs by remember { mutableStateOf(if (state.period == ReportPeriod.CUSTOM) state.toMs else now) }
    var active by remember { mutableStateOf<DateField?>(null) }
    var picking by remember { mutableStateOf<DateField?>(null) }

    LaunchedEffect(fromMs, toMs) { onPreview(fromMs, toMs) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = EggShapes.Sheet,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
        ) {
            Text(
                stringResource(R.string.report_sheet_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                stringResource(R.string.report_sheet_sub),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
            ) {
                ShortcutChip(
                    label = stringResource(R.string.report_shortcut_week1),
                    selected = shortcut == ReportShortcut.WEEK1,
                    onClick = {
                        shortcut = ReportShortcut.WEEK1
                        fromMs = Instant.ofEpochMilli(now).atZone(zone).minusWeeks(1)
                            .toInstant().toEpochMilli()
                        toMs = now
                    },
                )
                ShortcutChip(
                    label = stringResource(R.string.report_shortcut_week2),
                    selected = shortcut == ReportShortcut.WEEK2,
                    onClick = {
                        shortcut = ReportShortcut.WEEK2
                        fromMs = Instant.ofEpochMilli(now).atZone(zone).minusWeeks(2)
                            .toInstant().toEpochMilli()
                        toMs = now
                    },
                )
                ShortcutChip(
                    label = stringResource(R.string.report_shortcut_last_visit),
                    selected = shortcut == ReportShortcut.LAST_VISIT,
                    // Nothing to anchor on until a consultation has happened.
                    enabled = state.lastVisitMs != null,
                    onClick = {
                        state.lastVisitMs?.let {
                            shortcut = ReportShortcut.LAST_VISIT
                            fromMs = it
                            toMs = now
                        }
                    },
                )
                ShortcutChip(
                    label = stringResource(R.string.report_shortcut_treatment),
                    selected = shortcut == ReportShortcut.TREATMENT_START,
                    enabled = state.treatmentStartMs != null,
                    onClick = {
                        state.treatmentStartMs?.let {
                            shortcut = ReportShortcut.TREATMENT_START
                            fromMs = it
                            toMs = now
                        }
                    },
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                DateBox(
                    label = stringResource(R.string.report_field_from),
                    value = shortDayLabel(fromMs),
                    focused = active == DateField.From,
                    onClick = {
                        active = DateField.From
                        picking = DateField.From
                    },
                    modifier = Modifier.weight(1f),
                )
                DateBox(
                    label = stringResource(R.string.report_field_to),
                    value = shortDayLabel(toMs),
                    focused = active == DateField.To,
                    onClick = {
                        active = DateField.To
                        picking = DateField.To
                    },
                    modifier = Modifier.weight(1f),
                )
            }

            val draft = state.draftVolumes ?: state.volumes
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainer, EggShapes.Field)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(
                        R.string.report_volumes_fmt,
                        pluralStringResource(R.plurals.report_count_days, draft.days, draft.days),
                        pluralStringResource(R.plurals.report_count_labs, draft.labs, draft.labs),
                        pluralStringResource(R.plurals.report_count_doses, draft.doses, draft.doses),
                        pluralStringResource(
                            R.plurals.report_count_feel, draft.feelEntries, draft.feelEntries,
                        ),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.height(56.dp),
                ) { Text(stringResource(R.string.report_cancel)) }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { onApply(fromMs, toMs, shortcut) },
                    shape = EggShapes.Pill,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    modifier = Modifier.height(56.dp),
                ) { Text(stringResource(R.string.report_apply)) }
            }
        }
    }

    picking?.let { field ->
        val current = if (field == DateField.From) fromMs else toMs
        DayPickerDialog(
            atMs = current,
            onDismiss = { picking = null },
            onPick = { day ->
                picking = null
                // Typing dates by hand is what makes a range « personnalisée »:
                // the shortcut that seeded it no longer describes it.
                shortcut = ReportShortcut.MANUAL
                if (field == DateField.From) {
                    fromMs = day.atStartOfDay(zone).toInstant().toEpochMilli()
                } else {
                    toMs = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
                }
            },
        )
    }
}

// ---------------------------------------------------------------------------
// « Identité sur le rapport »
// ---------------------------------------------------------------------------

/**
 * Both fields are optional, and the sheet says what leaving them empty costs
 * rather than nudging towards filling them in. What it must never do is offer a
 * dotted line: §7.4.2 wants the box complete or absent, so « Effacer » erases
 * the pair and there is no way to end up with a document showing half of one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IdentitySheet(
    name: String?,
    birth: LocalDate?,
    onDismiss: () -> Unit,
    onErase: () -> Unit,
    onApply: (String?, LocalDate?) -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }
    var draftName by remember { mutableStateOf(name.orEmpty()) }
    var draftBirth by remember { mutableStateOf(birth) }
    var picking by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = EggShapes.Sheet,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
        ) {
            Text(
                stringResource(R.string.report_identity_sheet_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                stringResource(R.string.report_identity_sheet_sub),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )

            OutlinedTextField(
                value = draftName,
                onValueChange = { draftName = it },
                label = { Text(stringResource(R.string.report_identity_field_name)) },
                singleLine = true,
                shape = EggShapes.Field,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp),
            )

            DateBox(
                label = stringResource(R.string.report_identity_field_birth),
                value = draftBirth?.let { longDayLabel(it) }
                    ?: stringResource(R.string.report_identity_date_empty),
                onClick = { picking = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Erasing is its own action, not « save with empty fields »: it
                // deletes both keys from the vault instead of storing blanks.
                TextButton(
                    onClick = onErase,
                    enabled = name != null || birth != null,
                    modifier = Modifier.height(56.dp),
                ) {
                    Text(
                        stringResource(R.string.report_identity_erase),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { onApply(draftName, draftBirth) },
                    shape = EggShapes.Pill,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    modifier = Modifier.height(56.dp),
                ) { Text(stringResource(R.string.report_identity_save)) }
            }
        }
    }

    if (picking) {
        DayPickerDialog(
            atMs = (draftBirth ?: LocalDate.now(zone))
                .atStartOfDay(zone).toInstant().toEpochMilli(),
            allowFuture = false,
            onDismiss = { picking = false },
            onPick = {
                picking = false
                draftBirth = it
            },
        )
    }
}

@Composable
private fun ShortcutChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = { Text(label, style = MaterialTheme.typography.labelLarge) },
        shape = RoundedCornerShape(10.dp),
        leadingIcon = if (selected) {
            {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
        } else {
            null
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
            selectedLeadingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    )
}

/**
 * The bordered date field of the sheets. It takes an already-formatted [value]
 * rather than an instant: the identity sheet has a field that can legitimately
 * hold no date at all, and « Non renseignée » is a value like any other here.
 */
@Composable
private fun DateBox(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focused: Boolean = false,
) {
    val outline = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = EggShapes.Field,
        color = Color.Transparent,
        // The focused field is the one whose picker was last opened: 1,5 dp of
        // primary, so which date you are about to change is never ambiguous.
        border = BorderStroke(if (focused) 1.5.dp else 1.dp, outline),
    ) {
        Column(
            modifier = Modifier
                .heightIn(min = EggDim.TouchTarget)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            MicroLabel(
                label,
                color = if (focused) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayPickerDialog(
    atMs: Long,
    onDismiss: () -> Unit,
    onPick: (LocalDate) -> Unit,
    /** False for a date of birth: it cannot be ahead of today. */
    allowFuture: Boolean = true,
) {
    val zone = remember { ZoneId.systemDefault() }
    val selectableDates = remember(allowFuture) {
        if (allowFuture) {
            DatePickerDefaults.AllDates
        } else {
            object : SelectableDates {
                // The picker reports UTC-midnight millis; reject any day after
                // local today (expressed as the next day's UTC midnight).
                private val maxExclusive = LocalDate.now(zone).plusDays(1)
                    .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis < maxExclusive
                override fun isSelectableYear(year: Int) = year <= LocalDate.now(zone).year
            }
        }
    }
    val pickerState = rememberDatePickerState(
        // The picker speaks UTC midnight; seed it with the local calendar day
        // so a range picked in the evening doesn't slide by one.
        initialSelectedDateMillis = Instant.ofEpochMilli(atMs).atZone(zone).toLocalDate()
            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = selectableDates,
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val picked = pickerState.selectedDateMillis
                    if (picked != null) {
                        onPick(Instant.ofEpochMilli(picked).atZone(ZoneOffset.UTC).toLocalDate())
                    } else {
                        onDismiss()
                    }
                },
                enabled = pickerState.selectedDateMillis != null,
            ) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    ) {
        DatePicker(state = pickerState)
    }
}

// ---------------------------------------------------------------------------
// Dates
// ---------------------------------------------------------------------------

/**
 * « 26 avril → 26 juillet 2026 ». The year is printed once, on the end date,
 * unless the range straddles two years — then both carry it.
 */
@Composable
private fun rangeLabel(fromMs: Long, toMs: Long): String {
    val locale = Locale.getDefault()
    val zone = remember { ZoneId.systemDefault() }
    val from = Instant.ofEpochMilli(fromMs).atZone(zone).toLocalDate()
    val to = Instant.ofEpochMilli(toMs).atZone(zone).toLocalDate()
    val withYear = DateTimeFormatter.ofPattern("d MMMM yyyy", locale)
    val withoutYear = DateTimeFormatter.ofPattern("d MMMM", locale)
    val start = if (from.year == to.year) withoutYear.format(from) else withYear.format(from)
    return stringResource(R.string.report_range_fmt, start, withYear.format(to))
}

/** « 26 avr. 2026 » — the abbreviated form the date fields use. */
@Composable
private fun shortDayLabel(atMs: Long): String {
    val locale = Locale.getDefault()
    val zone = remember { ZoneId.systemDefault() }
    return DateTimeFormatter.ofPattern("d MMM yyyy", locale)
        .format(Instant.ofEpochMilli(atMs).atZone(zone).toLocalDate())
}

/**
 * « 3 février 1996 » — the same long form the document prints, so what the
 * screen shows and what the PDF carries are read as one value.
 */
@Composable
private fun longDayLabel(day: LocalDate): String {
    val locale = Locale.getDefault()
    return DateTimeFormatter.ofPattern("d MMMM yyyy", locale).format(day)
}

/** « 12 août » — a date inside a sentence, so no year and no weekday. */
@Composable
private fun dayMonthLabel(atMs: Long): String {
    val locale = Locale.getDefault()
    val zone = remember { ZoneId.systemDefault() }
    return DateTimeFormatter.ofPattern("d MMMM", locale)
        .format(Instant.ofEpochMilli(atMs).atZone(zone).toLocalDate())
}

