package com.douxev.eggshell.ui.medication

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.douxev.eggshell.R
import com.douxev.eggshell.data.MedicationRepository
import com.douxev.eggshell.data.ScheduleRepository
import com.douxev.eggshell.reminders.DueOccurrence
import com.douxev.eggshell.ui.common.DateRangePickerField
import com.douxev.eggshell.ui.common.DateTimePickerField
import com.douxev.eggshell.ui.common.ScreenHeader
import com.douxev.eggshell.ui.common.TimePickerField
import com.douxev.eggshell.ui.common.clickToDismissKeyboard
import com.douxev.eggshell.ui.components.ActionBand
import com.douxev.eggshell.ui.components.CardVariant
import com.douxev.eggshell.ui.components.EggCard
import com.douxev.eggshell.ui.components.Pill
import com.douxev.eggshell.ui.components.SectionTitle
import com.douxev.eggshell.ui.components.Segmented
import com.douxev.eggshell.ui.theme.EggDim
import com.douxev.eggshell.ui.theme.EggShapes
import uniffi.transition.DoseEvent
import uniffi.transition.DoseSchedule
import uniffi.transition.Medication
import uniffi.transition.NewDoseEvent

@HiltViewModel
class LogDoseViewModel @Inject constructor(
    state: SavedStateHandle,
    private val repo: MedicationRepository,
    private val schedules: ScheduleRepository,
) : ViewModel() {
    private val medicationId: Long = state.get<Long>("id") ?: error("missing medication id")

    /** When > 0, the screen edits this recorded dose instead of logging a new one. */
    val editingDoseId: Long = state.get<Long>("doseId") ?: -1L
    val isEditing: Boolean get() = editingDoseId > 0L

    enum class Status { Idle, Submitting, Done, Error }

    private val _medication = MutableStateFlow<Medication?>(null)
    val medication: StateFlow<Medication?> = _medication.asStateFlow()
    private val _loadedDose = MutableStateFlow<DoseEvent?>(null)
    val loadedDose: StateFlow<DoseEvent?> = _loadedDose.asStateFlow()
    private val _suggestedSite = MutableStateFlow<String?>(null)
    val suggestedSite: StateFlow<String?> = _suggestedSite.asStateFlow()
    private val _status = MutableStateFlow(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val sites: List<String> = repo.standardInjectionSites

    /** The treatment's reminders, so a hand-logged dose can be attached to the
     *  occurrence it answers. Read once: they don't change under this screen. */
    private var knownSchedules: List<DoseSchedule> = emptyList()

    init {
        viewModelScope.launch {
            _medication.value = runCatching { repo.get(medicationId) }.getOrNull()
            knownSchedules = runCatching {
                schedules.listForMedication(medicationId, includeInactive = true)
            }.getOrDefault(emptyList())
            if (editingDoseId > 0L) {
                val dose = runCatching { repo.getDose(editingDoseId) }.getOrNull()
                _loadedDose.value = dose
                // Surface a failed load — the Save button stays gated on
                // loadedDose, but the user must not stare at a silently
                // empty form believing it's the record. The screen shows a
                // localized message for this state.
                if (dose == null) _status.value = Status.Error
            } else {
                _suggestedSite.value = runCatching {
                    repo.suggestNextInjectionSite(medicationId)
                }.getOrNull()
            }
        }
    }

    /**
     * The occurrence an intake logged at [atMs] answers, if any.
     *
     * A dose typed in by hand is worth as much as one ticked from a
     * notification: attaching it to its prescribed time is what keeps it in the
     * punctuality figures instead of counting the occurrence as missed. Outside
     * half a cadence nothing is attached — an ad-hoc dose is not late, it is
     * simply unplanned (D2).
     */
    private fun linkageFor(atMs: Long): Pair<Long?, Long?> {
        var bestScheduleId: Long? = null
        var bestPlanned: Long? = null
        var bestDistance = Long.MAX_VALUE
        knownSchedules.forEach { s ->
            val planned = DueOccurrence.nearest(
                kind = s.kind,
                intervalMinutes = s.intervalMinutes?.toInt(),
                dailyHour = s.dailyHour?.toInt(),
                dailyMinute = s.dailyMinute?.toInt(),
                intervalDays = s.intervalDays?.toInt(),
                anchorMs = s.nextDueAtMs,
                atMs = atMs,
            ) ?: return@forEach
            // A reminder cannot have prescribed anything before it existed.
            if (planned < s.createdAtMs) return@forEach
            val tolerance = DueOccurrence.toleranceMs(
                kind = s.kind,
                intervalMinutes = s.intervalMinutes?.toInt(),
                intervalDays = s.intervalDays?.toInt(),
            )
            val distance = abs(atMs - planned)
            if (distance <= tolerance && distance < bestDistance) {
                bestScheduleId = s.id
                bestPlanned = planned
                bestDistance = distance
            }
        }
        return bestScheduleId to bestPlanned
    }

    fun submit(
        takenAtMs: Long,
        dose: Double?,
        doseUnit: String?,
        route: String?,
        site: String?,
        notes: String?,
    ) {
        _status.value = Status.Submitting
        _error.value = null
        viewModelScope.launch {
            runCatching {
                // On edit, carry the scheduling linkage over untouched — the
                // dose keeps counting against whichever reminder produced it,
                // and we never retro-fit a prescribed time onto a record that
                // was written before this release.
                val prev = _loadedDose.value
                val (scheduleId, scheduledAt) =
                    if (isEditing) prev?.scheduleId to prev?.scheduledAtMs
                    else linkageFor(takenAtMs)
                val event = NewDoseEvent(
                    medicationId = medicationId,
                    takenAtMs = takenAtMs,
                    dose = dose,
                    doseUnit = doseUnit,
                    route = route,
                    injectionSite = site,
                    notes = notes,
                    status = prev?.status ?: "taken",
                    scheduledAtMs = scheduledAt,
                    scheduleId = scheduleId,
                )
                if (isEditing) repo.updateDose(editingDoseId, event) else repo.logDose(event)
            }
                .onSuccess { _status.value = Status.Done }
                .onFailure {
                    _error.value = it.message ?: it::class.simpleName.orEmpty()
                    _status.value = Status.Error
                }
        }
    }

    /** Log the same intake once per day across a span — one core transaction. */
    fun submitRange(
        daysMs: List<Long>,
        dose: Double?,
        doseUnit: String?,
        route: String?,
        site: String?,
        notes: String?,
    ) {
        if (daysMs.isEmpty()) return
        _status.value = Status.Submitting
        _error.value = null
        viewModelScope.launch {
            runCatching {
                repo.logDoses(
                    daysMs.map { atMs ->
                        val (scheduleId, scheduledAt) = linkageFor(atMs)
                        NewDoseEvent(
                            medicationId = medicationId,
                            takenAtMs = atMs,
                            dose = dose,
                            doseUnit = doseUnit,
                            route = route,
                            injectionSite = site,
                            notes = notes,
                            status = "taken",
                            scheduledAtMs = scheduledAt,
                            scheduleId = scheduleId,
                        )
                    }
                )
            }
                .onSuccess { _status.value = Status.Done }
                .onFailure {
                    _error.value = it.message ?: it::class.simpleName.orEmpty()
                    _status.value = Status.Error
                }
        }
    }
}

@Composable
fun LogDoseScreen(
    onDone: () -> Unit,
    onBack: () -> Unit = onDone,
    vm: LogDoseViewModel = hiltViewModel(),
) {
    val med by vm.medication.collectAsState()
    val loadedDose by vm.loadedDose.collectAsState()
    val suggestedSite by vm.suggestedSite.collectAsState()
    val status by vm.status.collectAsState()
    val error by vm.error.collectAsState()
    val isEditing = vm.isEditing

    if (status == LogDoseViewModel.Status.Done) {
        onDone()
        return
    }

    var dose by rememberSaveable { mutableStateOf("") }
    var unit by rememberSaveable { mutableStateOf("") }
    var route by rememberSaveable { mutableStateOf<String?>(null) }
    var site by rememberSaveable { mutableStateOf<String?>(null) }
    var notes by rememberSaveable { mutableStateOf("") }
    // Defaults to now; the user can back-date a dose they forgot to log.
    var takenAtMs by rememberSaveable { mutableStateOf(System.currentTimeMillis()) }
    // Range mode: declare a daily intake over a whole span (e.g. a topical
    // applied every day for months) in one action. The dates survive config
    // changes like the rest of the form (epoch-day saver).
    var rangeMode by rememberSaveable { mutableStateOf(false) }
    var rangeStart by rememberSaveable(stateSaver = OptionalLocalDateSaver) {
        mutableStateOf<LocalDate?>(null)
    }
    var rangeEnd by rememberSaveable(stateSaver = OptionalLocalDateSaver) {
        mutableStateOf<LocalDate?>(null)
    }
    var rangeHour by rememberSaveable { mutableStateOf(12) }
    var rangeMinute by rememberSaveable { mutableStateOf(0) }
    var seededFromDose by rememberSaveable { mutableStateOf(false) }
    val zone = remember { ZoneId.systemDefault() }

    // Pre-fill defaults from the medication once it loads.
    LaunchedEffect(med) {
        med?.let { m ->
            if (!isEditing && dose.isEmpty()) dose = m.defaultDose?.let { formatDoseValue(it) } ?: ""
            if (!isEditing && unit.isEmpty()) unit = m.defaultDoseUnit.orEmpty()
            if (route == null) route = m.route
        }
    }
    // Editing: seed every field from the recorded dose, once.
    LaunchedEffect(loadedDose) {
        val d = loadedDose ?: return@LaunchedEffect
        if (seededFromDose) return@LaunchedEffect
        dose = d.dose?.let { formatDoseValue(it) } ?: ""
        unit = d.doseUnit.orEmpty()
        route = d.route ?: med?.route
        site = d.injectionSite
        notes = d.notes.orEmpty()
        takenAtMs = d.takenAtMs
        seededFromDose = true
    }
    // Suggest the next site only the first time we receive a suggestion and
    // the user hasn't already chosen one.
    LaunchedEffect(suggestedSite) {
        if (site == null) site = suggestedSite
    }

    val isInjection = route?.let { MedicationCatalog.isInjection(it) } == true
    val canSubmit = status != LogDoseViewModel.Status.Submitting &&
        (!isEditing || loadedDose != null) &&
        (!rangeMode || isEditing || (rangeStart != null && rangeEnd != null))

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        bottomBar = {
            ActionBand(alignment = Alignment.Center) {
                Button(
                    enabled = canSubmit,
                    shape = EggShapes.Pill,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val parsedDose = dose.replace(',', '.').toDoubleOrNull()
                        val s = rangeStart
                        val e = rangeEnd
                        if (rangeMode && !isEditing && s != null && e != null) {
                            // The picker forbids a future *day*, but the chosen
                            // hour still applies to today: 22:00 picked at noon
                            // would stamp an intake ten hours ahead. A dose can
                            // only have been taken already, so the last day is
                            // clamped to now.
                            val now = System.currentTimeMillis()
                            val daysMs = generateSequence(s) { it.plusDays(1) }
                                .takeWhile { !it.isAfter(e) }
                                .map { day ->
                                    day.atTime(rangeHour, rangeMinute)
                                        .atZone(zone).toInstant().toEpochMilli()
                                        .coerceAtMost(now)
                                }
                                .toList()
                            vm.submitRange(
                                daysMs = daysMs,
                                dose = parsedDose,
                                doseUnit = unit.ifBlank { null },
                                route = route,
                                site = if (isInjection) site else null,
                                notes = notes.ifBlank { null },
                            )
                        } else {
                            vm.submit(
                                takenAtMs = takenAtMs,
                                dose = parsedDose,
                                doseUnit = unit.ifBlank { null },
                                route = route,
                                site = if (isInjection) site else null,
                                notes = notes.ifBlank { null },
                            )
                        }
                    },
                ) { Text(stringResource(R.string.med_save_dose)) }
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
                    if (isEditing) R.string.med_dose_edit_title else R.string.med_log_dose_title
                ),
                onBack = onBack,
            )

            med?.let { m ->
                EggCard(variant = CardVariant.Low, padding = PaddingValues(horizontal = 18.dp, vertical = 14.dp)) {
                    Text(m.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(MedicationCatalog.kindLabelRes(m.kind)) +
                            MedicationCatalog.SEP +
                            stringResource(MedicationCatalog.routeLabelRes(m.route)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (!isEditing) {
                Segmented(
                    options = listOf(
                        stringResource(R.string.med_dose_mode_single),
                        stringResource(R.string.med_dose_mode_range),
                    ),
                    selectedIndex = if (rangeMode) 1 else 0,
                    onSelect = { rangeMode = it == 1 },
                )
            }

            SectionTitle(stringResource(R.string.meds_section_when))
            if (rangeMode && !isEditing) {
                DateRangePickerField(
                    label = stringResource(R.string.med_dose_range_label),
                    start = rangeStart,
                    end = rangeEnd,
                    onChange = { s, e -> rangeStart = s; rangeEnd = e },
                    allowFuture = false,
                    modifier = Modifier.fillMaxWidth(),
                )
                TimePickerField(
                    label = stringResource(R.string.med_dose_time_label),
                    hour = rangeHour,
                    minute = rangeMinute,
                    onChange = { h, m -> rangeHour = h; rangeMinute = m },
                    modifier = Modifier.fillMaxWidth(),
                )
                val s = rangeStart
                val e = rangeEnd
                if (s != null && e != null) {
                    val dayCount = ChronoUnit.DAYS.between(s, e).toInt() + 1
                    Text(
                        stringResource(R.string.med_dose_range_count_fmt, dayCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                DateTimePickerField(
                    label = stringResource(R.string.med_dose_datetime),
                    atMs = takenAtMs,
                    onChange = { takenAtMs = it },
                    modifier = Modifier.fillMaxWidth(),
                    // A dose can only have been taken in the past, never the future.
                    allowFuture = false,
                )
            }

            SectionTitle(stringResource(R.string.meds_section_dose))
            OutlinedTextField(
                value = dose,
                onValueChange = { dose = it.filter { c -> c.isDigit() || c == '.' || c == ',' } },
                label = { Text(stringResource(R.string.med_field_dose)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                shape = EggShapes.Field,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = unit,
                onValueChange = { unit = it },
                label = { Text(stringResource(R.string.med_field_dose_unit)) },
                singleLine = true,
                shape = EggShapes.Field,
                modifier = Modifier.fillMaxWidth(),
            )

            // Per-dose intake route — defaults to the medication's, editable so
            // an occasional different administration is recorded truthfully.
            Text(
                stringResource(R.string.med_field_route),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ChipGroup(
                options = MedicationCatalog.ROUTES,
                selected = route ?: "",
                labelOf = { stringResource(MedicationCatalog.routeLabelRes(it)) },
                onSelected = { route = it },
            )

            if (isInjection) {
                SectionTitle(stringResource(R.string.meds_section_site))
                suggestedSite?.let { suggested ->
                    Pill(
                        label = stringResource(
                            R.string.med_injection_suggested,
                            stringResource(MedicationCatalog.injectionSiteLabelRes(suggested)),
                        ),
                        selected = site == suggested,
                        onClick = { site = suggested },
                    )
                }
                ChipGroup(
                    options = vm.sites,
                    selected = site ?: "",
                    labelOf = { stringResource(MedicationCatalog.injectionSiteLabelRes(it)) },
                    onSelected = { site = it },
                )
            }

            SectionTitle(stringResource(R.string.meds_section_note))
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text(stringResource(R.string.med_field_notes_optional)) },
                shape = EggShapes.Field,
                modifier = Modifier.fillMaxWidth(),
            )

            if (status == LogDoseViewModel.Status.Error && isEditing && loadedDose == null) {
                Text(
                    stringResource(R.string.med_dose_load_error),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            error?.let {
                Text(
                    stringResource(R.string.med_error_prefix, it),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** rememberSaveable adapter for a nullable LocalDate (stored as epoch-day). */
private val OptionalLocalDateSaver = androidx.compose.runtime.saveable.Saver<LocalDate?, Long>(
    save = { it?.toEpochDay() ?: Long.MIN_VALUE },
    restore = { if (it == Long.MIN_VALUE) null else LocalDate.ofEpochDay(it) },
)
