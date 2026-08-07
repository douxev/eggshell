package com.douxev.eggshell.ui.medication

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.douxev.eggshell.R
import com.douxev.eggshell.data.MedicationRepository
import com.douxev.eggshell.data.PlannedDoses
import com.douxev.eggshell.data.ScheduleRepository
import com.douxev.eggshell.punctuality.DoseTiming
import com.douxev.eggshell.punctuality.exactLabel
import com.douxev.eggshell.punctuality.timingOf
import com.douxev.eggshell.reminders.MedAliasPrefs
import com.douxev.eggshell.ui.common.DateTimePickerField
import com.douxev.eggshell.ui.common.ScreenHeader
import com.douxev.eggshell.ui.components.ActionBand
import com.douxev.eggshell.ui.components.CardRule
import com.douxev.eggshell.ui.components.CardVariant
import com.douxev.eggshell.ui.components.EggCard
import com.douxev.eggshell.ui.components.EggFab
import com.douxev.eggshell.ui.components.EmptyState
import com.douxev.eggshell.ui.components.IconTile
import com.douxev.eggshell.ui.components.SectionTitle
import com.douxev.eggshell.ui.components.SkeletonBlock
import com.douxev.eggshell.ui.components.StatusPill
import com.douxev.eggshell.ui.theme.EggDim
import com.douxev.eggshell.ui.theme.EggShapes
import uniffi.transition.DoseSchedule
import uniffi.transition.Medication

/**
 * Médics — one treatment (handoff §6.5).
 *
 * The history is not the raw dose table: it is the schedule's occurrences
 * paired with what was actually logged, so a dose that never happened still
 * shows up as a line saying « manquée ». Intakes recorded before this release
 * carry no planned time and are shown as simply « notée » — we never guess
 * which occurrence they belonged to (D2).
 */
@HiltViewModel
class MedicationDetailViewModel @Inject constructor(
    state: SavedStateHandle,
    private val repo: MedicationRepository,
    private val schedules: ScheduleRepository,
    private val medAlias: MedAliasPrefs,
    private val plannedDoses: PlannedDoses,
) : ViewModel() {

    private val medicationId: Long = state.get<Long>("id") ?: error("missing medication id")

    /** How an intake sits against its prescribed time. */
    enum class Timing { OnTime, Late, Missed, Skipped, Unlinked }

    /** One line of the history: a real intake, or an occurrence nobody answered. */
    data class HistoryEntry(
        val key: String,
        /** Null for a missed occurrence — there is no dose row to edit. */
        val doseId: Long?,
        /** The real time when logged, the planned one when missed. */
        val atMs: Long,
        val timing: Timing,
        val deltaMin: Int?,
        val dose: Double?,
        val doseUnit: String?,
        val route: String?,
        val injectionSite: String?,
        /**
         * The occurrence this line stands for, when it is one nobody answered.
         * Carried so a missed dose can be logged *against its own slot* rather
         * than as a loose intake: writing `scheduledAtMs` back is what lets
         * [PlannedDoses] pair the two exactly instead of guessing by proximity,
         * and it is the difference between the history healing and the same
         * occurrence still reading « manquée » next to a duplicate intake.
         */
        val plannedAtMs: Long? = null,
        val scheduleId: Long? = null,
    ) {
        /** A missed occurrence can be answered after the fact; nothing else can. */
        val isRecoverable: Boolean get() = doseId == null && plannedAtMs != null
    }

    private val _medication = MutableStateFlow<Medication?>(null)
    val medication: StateFlow<Medication?> = _medication.asStateFlow()
    private val _schedules = MutableStateFlow<List<DoseSchedule>>(emptyList())
    val schedulesState: StateFlow<List<DoseSchedule>> = _schedules.asStateFlow()
    private val _alias = MutableStateFlow<String?>(null)
    val alias: StateFlow<String?> = _alias.asStateFlow()
    private val _history = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val history: StateFlow<List<HistoryEntry>> = _history.asStateFlow()
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /**
     * How late this user usually is, in minutes — the figure the « Régularité »
     * card already reports. It seeds the time proposed when a missed dose is
     * caught up, because "I took it, just not when the app expected" is what a
     * missed occurrence almost always turns out to mean, and someone who is
     * habitually forty minutes late did not take this one on the hour either.
     */
    private val _meanDelayMin = MutableStateFlow(0)
    val meanDelayMin: StateFlow<Int> = _meanDelayMin.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _loading.value = true
            _medication.value = runCatching { repo.get(medicationId) }.getOrNull()
            _schedules.value = runCatching {
                schedules.listForMedication(medicationId, includeInactive = true)
            }.getOrDefault(emptyList())
            _alias.value = medAlias.get(medicationId)
            _history.value = buildHistory()
            _loading.value = false
        }
    }

    private suspend fun buildHistory(): List<HistoryEntry> {
        val now = System.currentTimeMillis()
        val from = now - WINDOW_MS
        val doses = runCatching { repo.listDoses(medicationId, 0, HISTORY_LIMIT) }
            .getOrDefault(emptyList())
        val window = runCatching {
            plannedDoses.window(fromMs = from, toMs = now, medicationId = medicationId)
        }.getOrNull()

        // This treatment's own habit when it has one, the user's across every
        // treatment when it does not. A schedule added last week may hold
        // nothing but missed occurrences, and prefilling those at exactly the
        // prescribed minute would quietly assert a punctuality the user has
        // never once shown.
        _meanDelayMin.value = window?.stats?.meanDelayMin?.takeIf { it != 0 }
            ?: runCatching {
                plannedDoses.window(fromMs = from, toMs = now).stats.meanDelayMin
            }.getOrDefault(0)

        val out = ArrayList<HistoryEntry>()
        val paired = HashSet<Long>()

        window?.occurrences?.forEach { occurrence ->
            val event = occurrence.event
            if (event == null) {
                out += HistoryEntry(
                    key = "planned-${occurrence.scheduleId}-${occurrence.plannedAtMs}",
                    doseId = null,
                    atMs = occurrence.plannedAtMs,
                    timing = Timing.Missed,
                    deltaMin = null,
                    dose = null,
                    doseUnit = null,
                    route = null,
                    injectionSite = null,
                    plannedAtMs = occurrence.plannedAtMs,
                    scheduleId = occurrence.scheduleId,
                )
            } else {
                paired += event.id
                val delta = occurrence.deltaMin
                out += HistoryEntry(
                    key = "dose-${event.id}",
                    doseId = event.id,
                    atMs = event.takenAtMs,
                    timing = when (timingOf(delta, MedicationCatalog.ON_TIME_TOLERANCE_MIN)) {
                        DoseTiming.Late -> Timing.Late
                        else -> Timing.OnTime
                    },
                    deltaMin = delta,
                    dose = event.dose,
                    doseUnit = event.doseUnit,
                    route = event.route,
                    injectionSite = event.injectionSite,
                )
            }
        }

        // Everything else the vault holds for this treatment: ad-hoc intakes,
        // declared skips, and the whole pre-punctuality history. They are real
        // — they just have no prescribed time to be measured against.
        val missedAt = window?.occurrences?.filter { it.event == null }?.map { it.plannedAtMs }
            .orEmpty()
        doses.filterNot { it.id in paired }.forEach { event ->
            // A declared skip inside the window is already on screen as the
            // « manquée » occurrence it answered (D2 counts a skip as a miss).
            // Listing the event too would show the same dose twice.
            val alreadyShownAsMissed = event.status == "skipped" &&
                missedAt.any { kotlin.math.abs(it - event.takenAtMs) <= SKIP_MATCH_MS }
            if (alreadyShownAsMissed) return@forEach
            out += HistoryEntry(
                key = "dose-${event.id}",
                doseId = event.id,
                atMs = event.takenAtMs,
                timing = if (event.status == "skipped") Timing.Skipped else Timing.Unlinked,
                deltaMin = null,
                dose = event.dose,
                doseUnit = event.doseUnit,
                route = event.route,
                injectionSite = event.injectionSite,
            )
        }

        // The card is one block, not a lazy list: cap it so a treatment logged
        // twice a day for two years can't turn the screen into a wall.
        return out.sortedByDescending { it.atMs }.take(HISTORY_ROWS)
    }

    fun setAlias(alias: String?) {
        medAlias.set(medicationId, alias)
        _alias.value = alias?.takeIf { it.isNotBlank() }
        // Re-resolve the plain-text mirror so the new alias takes effect on
        // already-scheduled reminders (only visible in ALIAS mode).
        viewModelScope.launch { runCatching { schedules.syncFromDb() } }
    }


    /**
     * Catch up a missed occurrence: log the intake the user did take, at
     * [takenAtMs], against the slot that was expecting it.
     *
     * The dose, unit and route come from the medication's defaults — this is a
     * one-tap correction, and a user who took something other than their usual
     * dose has the full log screen for it.
     *
     * `scheduledAtMs` is written so the intake is bound to *this* occurrence.
     * Without it the matcher falls back to nearest-in-time, which on a
     * twice-daily treatment can let the caught-up dose answer the neighbouring
     * slot instead and leave both lines wrong. The schedule is deliberately NOT
     * advanced: this is back-fill of a slot already in the past, not the
     * answering of the one currently due.
     */
    fun logMissedDose(entry: HistoryEntry, takenAtMs: Long) {
        val plannedAtMs = entry.plannedAtMs ?: return
        viewModelScope.launch {
            val med = _medication.value
            runCatching {
                repo.logDose(
                    uniffi.transition.NewDoseEvent(
                        medicationId = medicationId,
                        takenAtMs = takenAtMs,
                        dose = med?.defaultDose,
                        doseUnit = med?.defaultDoseUnit,
                        route = med?.route,
                        injectionSite = null,
                        notes = null,
                        status = "taken",
                        scheduledAtMs = plannedAtMs,
                        scheduleId = entry.scheduleId,
                    )
                )
            }
            refresh()
        }
    }

    /** Remove a single logged dose from the history. */
    fun deleteDose(id: Long) {
        viewModelScope.launch {
            runCatching { repo.deleteDose(id) }
            refresh()
        }
    }

    /** Archive (hide, reversible) the medication, then leave the screen — only
     *  on success, so a failed write doesn't navigate away as if it worked. */
    fun archive(onArchived: () -> Unit) {
        viewModelScope.launch {
            val ok = runCatching { repo.setArchived(medicationId, true) }.isSuccess
            if (ok) onArchived() else refresh()
        }
    }

    /** Put an archived treatment back in circulation. Stays on the screen: the
     *  user is looking at it, and the header has to redraw without the notice. */
    fun unarchive() {
        viewModelScope.launch {
            runCatching { repo.setArchived(medicationId, false) }
            refresh()
        }
    }

    /** Permanently delete the medication: tear down off-vault reminder state
     *  first (it reads the schedule ids), then delete the row + cascade. Only
     *  navigates away when the delete actually succeeds — otherwise a half-done
     *  delete would silently look successful. */
    fun deleteMedication(onDeleted: () -> Unit) {
        viewModelScope.launch {
            val ok = runCatching {
                schedules.deleteMedicationCleanup(medicationId)
                repo.delete(medicationId)
            }.isSuccess
            if (ok) onDeleted() else refresh()
        }
    }

    private companion object {
        const val WINDOW_MS = 30L * 24L * 60L * 60L * 1000L
        const val HISTORY_LIMIT = 50L
        const val HISTORY_ROWS = 60
        /** How far a declared skip may sit from the occurrence it answered. */
        const val SKIP_MATCH_MS = 12L * 60L * 60L * 1000L
    }
}

@Composable
fun MedicationDetailScreen(
    onLogDose: () -> Unit,
    onEditDose: (Long) -> Unit,
    onManageReminders: () -> Unit,
    onEditMedication: () -> Unit,
    onBack: () -> Unit,
    vm: MedicationDetailViewModel = hiltViewModel(),
) {
    val med by vm.medication.collectAsState()
    val schedules by vm.schedulesState.collectAsState()
    val alias by vm.alias.collectAsState()
    val history by vm.history.collectAsState()
    val loading by vm.loading.collectAsState()
    val meanDelayMin by vm.meanDelayMin.collectAsState()

    var editingAlias by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var doseToDelete by remember { mutableStateOf<Long?>(null) }
    // The missed occurrence the user tapped, awaiting confirmation.
    var missedToLog by remember {
        mutableStateOf<MedicationDetailViewModel.HistoryEntry?>(null)
    }

    LaunchedEffect(Unit) { vm.refresh() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        bottomBar = {
            ActionBand {
                EggFab(
                    icon = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.med_log_dose),
                    onClick = onLogDose,
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
                ScreenHeader(
                    title = med?.name ?: stringResource(R.string.med_detail_loading),
                    onBack = onBack,
                    actions = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = onEditMedication,
                                modifier = Modifier.size(EggDim.TouchTarget),
                            ) {
                                Icon(
                                    Icons.Filled.Edit,
                                    contentDescription = stringResource(R.string.action_edit),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
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
                            OverflowMenu(
                                expanded = menuOpen,
                                archived = med?.archived == true,
                                onDismiss = { menuOpen = false },
                                onArchive = {
                                    menuOpen = false
                                    vm.archive(onArchived = onBack)
                                },
                                onUnarchive = {
                                    menuOpen = false
                                    vm.unarchive()
                                },
                                onDelete = {
                                    menuOpen = false
                                    confirmDelete = true
                                },
                            )
                        }
                    },
                )
            }

            if (loading && med == null) {
                item { SkeletonBlock(height = 150.dp) }
                item { SkeletonBlock(height = 120.dp) }
                item { SkeletonBlock(height = 188.dp) }
            }

            med?.let { m ->
                item { IdentityCard(med = m, alias = alias, onEditAlias = { editingAlias = true }) }
                if (m.archived) {
                    item {
                        EggCard(variant = CardVariant.Outlined) {
                            Text(
                                stringResource(R.string.meds_archived_notice),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // One door to the reminders, not a pile of half-manageable cards.
            // Creating, pausing, editing and deleting all live behind it —
            // splitting them across screens is what let "delete" go missing for
            // a paused reminder.
            item { SectionTitle(text = stringResource(R.string.meds_schedules)) }
            item {
                RemindersEntryRow(
                    activeCount = schedules.count { it.active },
                    pausedCount = schedules.count { !it.active },
                    onClick = onManageReminders,
                )
            }

            item { SectionTitle(text = stringResource(R.string.med_history)) }
            item {
                if (history.isEmpty()) {
                    if (!loading) {
                        EmptyState(
                            message = stringResource(R.string.med_history_empty),
                            actionLabel = stringResource(R.string.med_log_dose),
                            onAction = onLogDose,
                        )
                    }
                } else {
                    HistoryCard(
                        entries = history,
                        onEdit = onEditDose,
                        onDelete = { doseToDelete = it },
                        onCatchUp = { missedToLog = it },
                    )
                }
            }
        }
    }

    if (editingAlias) {
        AliasDialog(
            initial = alias.orEmpty(),
            onDismiss = { editingAlias = false },
            onSave = {
                vm.setAlias(it.ifBlank { null })
                editingAlias = false
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.med_delete_title)) },
            text = { Text(stringResource(R.string.med_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.deleteMedication(onDeleted = onBack)
                }) {
                    Text(
                        stringResource(R.string.med_delete_confirm),
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

    missedToLog?.let { entry ->
        CatchUpDoseDialog(
            entry = entry,
            meanDelayMin = meanDelayMin,
            onDismiss = { missedToLog = null },
            onConfirm = { takenAtMs ->
                vm.logMissedDose(entry, takenAtMs)
                missedToLog = null
            },
        )
    }

    doseToDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { doseToDelete = null },
            title = { Text(stringResource(R.string.med_dose_delete_title)) },
            text = { Text(stringResource(R.string.med_dose_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteDose(id)
                    doseToDelete = null
                }) {
                    Text(
                        stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { doseToDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/**
 * Confirms catching up a missed dose, with the time open to correction before
 * anything is written.
 *
 * The proposed instant is the prescribed one plus the user's usual delay, not
 * "now": a dose missed on Tuesday was not taken on Friday afternoon when the
 * user finally opened the app, and defaulting to the current time would write
 * an offset of several days into the very punctuality figures this screen
 * reports. Defaulting to the prescribed minute would be the opposite lie — a
 * perfect record the user never had — so the habit already measured is the
 * honest starting guess, and it stays a guess the user can move.
 *
 * The field refuses future instants: an intake that has not happened yet is not
 * one to catch up.
 */
@Composable
private fun CatchUpDoseDialog(
    entry: MedicationDetailViewModel.HistoryEntry,
    meanDelayMin: Int,
    onDismiss: () -> Unit,
    onConfirm: (takenAtMs: Long) -> Unit,
) {
    val planned = entry.plannedAtMs ?: return
    // Seeded once per occurrence: recomputing on recomposition would drag the
    // field back to the proposal after every edit the user makes.
    var takenAtMs by rememberSaveable(entry.key) {
        mutableStateOf(
            (planned + meanDelayMin * 60_000L).coerceAtMost(System.currentTimeMillis())
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.med_catch_up_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(
                        R.string.med_catch_up_body_fmt,
                        rememberDateTimeText(planned),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                DateTimePickerField(
                    label = stringResource(R.string.med_catch_up_taken_at),
                    atMs = takenAtMs,
                    onChange = { takenAtMs = it },
                    allowFuture = false,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (meanDelayMin != 0) {
                    Text(
                        stringResource(R.string.med_catch_up_mean_delay_fmt, meanDelayMin),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(takenAtMs) }) {
                Text(stringResource(R.string.med_catch_up_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun OverflowMenu(
    expanded: Boolean,
    archived: Boolean,
    onDismiss: () -> Unit,
    onArchive: () -> Unit,
    onUnarchive: () -> Unit,
    onDelete: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (archived) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.meds_unarchive)) },
                leadingIcon = { Icon(Icons.Filled.Unarchive, contentDescription = null) },
                onClick = onUnarchive,
            )
        } else {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.med_archive)) },
                leadingIcon = { Icon(Icons.Filled.Archive, contentDescription = null) },
                onClick = onArchive,
            )
        }
        DropdownMenuItem(
            text = {
                Text(
                    stringResource(R.string.med_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            },
            leadingIcon = {
                Icon(
                    Icons.Filled.DeleteOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            onClick = onDelete,
        )
    }
}

/**
 * The identity card. Its tile deliberately uses the *strong* `primary` pair
 * inside a `primaryContainer` card — the inversion is what makes it read as the
 * subject of the screen rather than one more row.
 */
@Composable
private fun IdentityCard(med: Medication, alias: String?, onEditAlias: () -> Unit) {
    EggCard(variant = CardVariant.Primary) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val accent: Color? = med.color?.let { Color(it.toInt()) }
            IconTile(
                size = 52.dp,
                shape = IdentityTileShape,
                container = accent ?: MaterialTheme.colorScheme.primary,
            ) {
                Icon(
                    MedicationCatalog.routeIcon(med.route),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    formatDoseWithUnit(med.defaultDose, med.defaultDoseUnit)
                        ?.let { stringResource(R.string.meds_dose_per_intake_fmt, it) }
                        ?: stringResource(R.string.meds_dose_unspecified),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(MedicationCatalog.kindLabelRes(med.kind)) +
                        MedicationCatalog.SEP +
                        stringResource(MedicationCatalog.routeLabelRes(med.route)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
                )
            }
        }

        med.notes?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
                modifier = Modifier.padding(top = 10.dp),
            )
        }

        CardRule(modifier = Modifier.padding(top = 16.dp), alpha = 0.22f)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onEditAlias)
                .padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Notifications,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
            )
            Text(
                stringResource(R.string.meds_alias_row),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                modifier = Modifier.weight(1f),
            )
            Text(
                alias?.takeIf { it.isNotBlank() } ?: stringResource(R.string.med_alias_none),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/**
 * The single door to this treatment's reminders.
 *
 * It replaces the stack of per-schedule cards that used to sit here. Those
 * cards could pause and edit a reminder but not delete one, while the settings
 * hub could delete but never listed a paused reminder — so a paused reminder
 * was reachable from a screen that could not remove it and absent from the one
 * that could. Collapsing the whole set of operations behind one row is what
 * makes that state impossible rather than merely fixed.
 */
@Composable
private fun RemindersEntryRow(activeCount: Int, pausedCount: Int, onClick: () -> Unit) {
    EggCard(
        variant = CardVariant.Low,
        padding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
        onClick = onClick,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(container = MaterialTheme.colorScheme.primaryContainer) {
                Icon(
                    Icons.Filled.Notifications,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.meds_reminders_manage),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    when {
                        activeCount == 0 && pausedCount == 0 ->
                            stringResource(R.string.meds_reminders_none)
                        // The paused count is stated rather than folded into a
                        // total: it is the one a user comes here to act on.
                        pausedCount > 0 -> stringResource(
                            R.string.meds_reminders_count_paused_fmt,
                            activeCount,
                            pausedCount,
                        )
                        else -> stringResource(R.string.meds_reminders_count_fmt, activeCount)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The history card. Its 6/18 padding is what lets each rule run edge to edge
 * inside the card, so the lines read as one block rather than as five cards.
 */
@Composable
private fun HistoryCard(
    entries: List<MedicationDetailViewModel.HistoryEntry>,
    onEdit: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onCatchUp: (MedicationDetailViewModel.HistoryEntry) -> Unit,
) {
    EggCard(
        variant = CardVariant.Low,
        padding = PaddingValues(horizontal = 18.dp, vertical = 6.dp),
    ) {
        entries.forEachIndexed { index, entry ->
            if (index > 0) CardRule(alpha = 0.14f)
            HistoryRow(
                entry = entry,
                onEdit = onEdit,
                onDelete = onDelete,
                onCatchUp = onCatchUp,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryRow(
    entry: MedicationDetailViewModel.HistoryEntry,
    onEdit: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onCatchUp: (MedicationDetailViewModel.HistoryEntry) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val (glyph, glyphTint) = when (entry.timing) {
        MedicationDetailViewModel.Timing.OnTime -> Icons.Filled.CheckCircle to scheme.tertiary
        MedicationDetailViewModel.Timing.Late -> Icons.Filled.CheckCircle to scheme.secondary
        MedicationDetailViewModel.Timing.Missed -> Icons.Filled.Close to scheme.error
        MedicationDetailViewModel.Timing.Skipped -> Icons.Filled.Close to scheme.error
        MedicationDetailViewModel.Timing.Unlinked ->
            Icons.Filled.CheckCircle to scheme.onSurfaceVariant
    }
    val pillLabel = when (entry.timing) {
        MedicationDetailViewModel.Timing.Missed -> stringResource(R.string.meds_missed)
        MedicationDetailViewModel.Timing.Skipped -> stringResource(R.string.meds_skipped)
        MedicationDetailViewModel.Timing.Unlinked -> stringResource(R.string.meds_logged)
        else -> deltaLabelText(
            exactLabel(entry.deltaMin, MedicationCatalog.ON_TIME_TOLERANCE_MIN)
        )
    }
    val pillContainer = when (entry.timing) {
        MedicationDetailViewModel.Timing.Late -> scheme.secondaryContainer
        MedicationDetailViewModel.Timing.Missed,
        MedicationDetailViewModel.Timing.Skipped -> scheme.errorContainer
        else -> scheme.surfaceContainerHighest
    }
    val pillContent = when (entry.timing) {
        MedicationDetailViewModel.Timing.Late -> scheme.onSecondaryContainer
        MedicationDetailViewModel.Timing.Missed,
        MedicationDetailViewModel.Timing.Skipped -> scheme.onErrorContainer
        else -> scheme.onSurfaceVariant
    }

    val detail = buildList {
        formatDoseWithUnit(entry.dose, entry.doseUnit)?.let(::add)
        entry.route?.let { add(stringResource(MedicationCatalog.routeLabelRes(it))) }
        entry.injectionSite?.let {
            add(stringResource(MedicationCatalog.injectionSiteLabelRes(it)))
        }
    }
    val deleteLabel = stringResource(R.string.med_dose_delete)
    val catchUpLabel = stringResource(R.string.med_catch_up_action)
    val doseId = entry.doseId

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { base ->
                when {
                    doseId != null -> base
                        .semantics {
                            customActions = listOf(
                                CustomAccessibilityAction(deleteLabel) { onDelete(doseId); true },
                            )
                        }
                        .combinedClickable(
                            onClick = { onEdit(doseId) },
                            onLongClick = { onDelete(doseId) },
                        )
                    // A missed occurrence has no dose row to edit, but it does
                    // have a slot to fill — tapping it is how the dose that was
                    // actually taken gets recorded against it.
                    entry.isRecoverable -> base.clickable(onClickLabel = catchUpLabel) {
                        onCatchUp(entry)
                    }
                    else -> base
                }
            }
            .padding(vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            glyph,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = glyphTint,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                rememberDateTimeText(entry.atMs),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                if (detail.isEmpty()) {
                    stringResource(R.string.meds_not_logged)
                } else {
                    detail.joinToString(MedicationCatalog.SEP)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        StatusPill(label = pillLabel, container = pillContainer, content = pillContent)
    }
}

@Composable
private fun AliasDialog(
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.med_alias_dialog_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(40) },
                label = { Text(stringResource(R.string.med_field_notif_alias)) },
                supportingText = { Text(stringResource(R.string.med_field_notif_alias_hint)) },
                singleLine = true,
                shape = EggShapes.Field,
                modifier = Modifier.fillMaxWidth(),
            )
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

/**
 * « Aujourd'hui · 08:04 », « Hier · 21:47 », « Dimanche · 08:00 », then the
 * plain date. A relative day is what the user actually remembers.
 */
@Composable
private fun rememberDateTimeText(atMs: Long): String {
    val context = LocalContext.current
    val locale = Locale.getDefault()
    return remember(atMs, locale) {
        val zone = ZoneId.systemDefault()
        val at = Instant.ofEpochMilli(atMs).atZone(zone)
        val today = LocalDate.now(zone)
        val days = java.time.temporal.ChronoUnit.DAYS.between(at.toLocalDate(), today)
        val day = when {
            days == 0L -> context.getString(R.string.today_section_today)
            days == 1L -> context.getString(R.string.meds_yesterday)
            days in 2..6 -> at.dayOfWeek.getDisplayName(TextStyle.FULL_STANDALONE, locale)
                .replaceFirstChar { it.titlecase(locale) }
            else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                .withLocale(locale)
                .format(at)
        }
        val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
            .withLocale(locale)
            .format(at)
        day + MedicationCatalog.SEP + time
    }
}

/** The identity tile: 52 dp at radius 16 (§6.5), one notch above a list tile. */
private val IdentityTileShape = RoundedCornerShape(16.dp)
