package com.douxev.eggshell.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.douxev.eggshell.data.FeaturesPrefs
import com.douxev.eggshell.data.HormonesRepository
import com.douxev.eggshell.data.JournalRepository
import com.douxev.eggshell.data.MedicationRepository
import com.douxev.eggshell.data.ModuleBadgePrefs
import com.douxev.eggshell.data.PlannedDoses
import com.douxev.eggshell.data.ScheduleRepository
import com.douxev.eggshell.reminders.LabReminderManager
import com.douxev.eggshell.reminders.LabReminderPrefs
import uniffi.transition.DoseSchedule
import uniffi.transition.Medication
import uniffi.transition.NewDoseEvent
import uniffi.transition.NewJournalEntry

/**
 * State of the launcher home — the only root screen of the refonte.
 *
 * It answers three questions at a glance: what is the next dose, how do you
 * feel today, and what is waiting for you in each module. Everything the user
 * does daily happens from here without navigating.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val schedules: ScheduleRepository,
    private val medications: MedicationRepository,
    private val plannedDoses: PlannedDoses,
    private val journals: JournalRepository,
    private val hormones: HormonesRepository,
    private val labs: LabReminderManager,
    private val features: FeaturesPrefs,
    private val badges: ModuleBadgePrefs,
) : ViewModel() {

    /** One dose expected today. */
    data class DoseItem(
        val scheduleId: Long,
        val medication: Medication,
        val scheduledAtMs: Long,
        val done: Boolean,
    )

    /** The single reminder line under the dose card, plus how many follow. */
    data class ReminderLine(val text: String, val dueAtMs: Long, val othersCount: Int)

    /** Which launcher tiles are shown. Navigation never changes shape: only
     *  the tiles come and go, the routes always exist. */
    data class Modules(
        val meds: Boolean = true,
        val appointments: Boolean = false,
        val journal: Boolean = true,
        val bleeding: Boolean = false,
        val labs: Boolean = true,
        val weight: Boolean = true,
        val photos: Boolean = false,
        val voice: Boolean = false,
        // Matches the FeaturesPrefs default, so the tile is not absent for the
        // frame before the flags flow emits.
        val notes: Boolean = true,
        val dreams: Boolean = true,
    )

    /** A launcher badge. Counters win over dots, and at most two are shown. */
    sealed interface Badge {
        data class Counter(val count: Int) : Badge
        data object News : Badge
    }

    data class State(
        val doses: List<DoseItem> = emptyList(),
        val nextDose: DoseItem? = null,
        val hasMedications: Boolean = false,
        /** 1..5, or null when nothing was recorded today. */
        val moodFace: Int? = null,
        val reminder: ReminderLine? = null,
        val modules: Modules = Modules(),
        val badges: Map<ModuleBadgePrefs.Module, Badge> = emptyMap(),
        val loading: Boolean = true,
    ) {
        val takenCount: Int get() = doses.count { it.done }
        val plannedCount: Int get() = doses.size
        val allTaken: Boolean get() = doses.isNotEmpty() && nextDose == null
    }

    // The flags are plain StateFlows readable without suspending, so the very
    // first frame already draws the user's own launcher instead of the defaults
    // and then jumping to their real selection.
    private val _state = MutableStateFlow(State(modules = currentModules(), loading = true))
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * True while a « Marquer comme pris » write is in flight. Kept out of
     * [State] because [reload] rebuilds that wholesale, which would clear the
     * guard in the middle of the very write it protects.
     */
    private val _markingTaken = MutableStateFlow(false)
    val markingTaken: StateFlow<Boolean> = _markingTaken.asStateFlow()

    init { refresh() }

    private fun currentModules() = Modules(
        meds = features.medications.value,
        appointments = features.appointments.value,
        journal = features.journal.value,
        bleeding = features.bleeding.value,
        labs = features.hormones.value,
        weight = features.weightTracking.value,
        photos = features.photoTab.value,
        voice = features.voiceTab.value,
        notes = features.notes.value,
        dreams = features.dreams.value,
    )

    fun refresh() {
        viewModelScope.launch { reload() }
    }

    /** The body of [refresh], awaitable so a write can finish before the state
     *  it invalidates is rebuilt. */
    private suspend fun reload() {
        val modules = currentModules()

        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val startOfDay = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val startOfTomorrow = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val now = System.currentTimeMillis()

        var hasMeds = false
        var doses: List<DoseItem> = emptyList()
        var futureMedDue: List<Pair<String, Long>> = emptyList()
        if (modules.meds) {
            val allMeds = runCatching { medications.list() }.getOrDefault(emptyList())
            hasMeds = allMeds.isNotEmpty()
            val medById = allMeds.associateBy { it.id }

            // The day's doses come from the same occurrence grid Médics and
            // the PDF read, so the three can never disagree. Two things this
            // buys us that reading `nextDueAtMs` could not:
            //  - "pris" is resolved per occurrence, so a twice-daily
            //    treatment keeps its evening dose after the morning one is
            //    ticked;
            //  - ticking a dose advances the schedule to tomorrow, which
            //    used to make it vanish from today and collapse the card to
            //    "0/0 · aucune prise programmée" right after a success.
            doses = runCatching { plannedDoses.window(startOfDay, startOfTomorrow) }
                .getOrNull()
                ?.occurrences
                ?.sortedBy { it.plannedAtMs }
                ?.mapNotNull { occurrence ->
                    val med = medById[occurrence.medicationId] ?: return@mapNotNull null
                    DoseItem(
                        scheduleId = occurrence.scheduleId,
                        medication = med,
                        scheduledAtMs = occurrence.plannedAtMs,
                        done = occurrence.event != null,
                    )
                }
                .orEmpty()

            val active: List<DoseSchedule> = allMeds.flatMap { med ->
                runCatching { schedules.listForMedication(med.id, includeInactive = false) }
                    .getOrDefault(emptyList())
            }
            futureMedDue = active
                .filter { it.nextDueAtMs >= startOfTomorrow }
                .mapNotNull { s ->
                    val med = medById[s.medicationId] ?: return@mapNotNull null
                    med.name to s.nextDueAtMs
                }
        }

        // Today's mood, so a second tap on a face corrects the day instead
        // of stacking a second entry.
        val moodFace = if (!modules.journal) null else {
            todayEntry(startOfDay, startOfTomorrow)?.mood?.toInt()?.let(::faceOf)
        }

        val futureLabs = runCatching { labs.list() }.getOrDefault(emptyList())
            .filter { it.nextDueAtMs >= now }
            .filter {
                when (it.category) {
                    LabReminderPrefs.CATEGORY_PHOTO -> modules.photos
                    LabReminderPrefs.CATEGORY_VOICE -> modules.voice
                    else -> true
                }
            }
            .map { it.label to it.nextDueAtMs }
        val upcoming = (futureMedDue + futureLabs).sortedBy { it.second }
        val reminder = upcoming.firstOrNull()?.let { (label, dueAt) ->
            ReminderLine(text = label, dueAtMs = dueAt, othersCount = upcoming.size - 1)
        }

        _state.value = State(
            doses = doses,
            nextDose = doses.firstOrNull { !it.done },
            hasMedications = hasMeds,
            moodFace = moodFace,
            reminder = reminder,
            modules = modules,
            badges = computeBadges(modules, doses),
            loading = false,
        )
    }

    /**
     * Marks the next dose as taken now. The planned time travels with the
     * event — that is what makes the offset ("+1 h 47") computable later; the
     * offset itself is never stored.
     *
     * Guarded against a second tap while the first write is still travelling:
     * the two taps carry the same occurrence, so the vault would hold two
     * intakes for one dose and every count downstream would read one too many.
     * The guard is only released once the reloaded state no longer offers that
     * dose.
     */
    fun markTakenNow(item: DoseItem) {
        if (_markingTaken.value) return
        _markingTaken.value = true
        viewModelScope.launch {
            runCatching {
                medications.logDose(
                    NewDoseEvent(
                        medicationId = item.medication.id,
                        takenAtMs = System.currentTimeMillis(),
                        dose = item.medication.defaultDose,
                        doseUnit = item.medication.defaultDoseUnit,
                        route = item.medication.route,
                        injectionSite = null,
                        notes = null,
                        status = "taken",
                        scheduledAtMs = item.scheduledAtMs,
                        scheduleId = item.scheduleId,
                    )
                )
                schedules.advanceToNextOccurrence(item.scheduleId)
            }
            try {
                reload()
            } finally {
                _markingTaken.value = false
            }
        }
    }

    /**
     * "Décaler" — re-show the reminder later without moving the schedule
     * itself, exactly like the notification's snooze action.
     */
    fun snooze(item: DoseItem) {
        runCatching { schedules.snoozeReminder(item.scheduleId) }
    }

    /**
     * One tap on a face records the day's mood immediately — no validation
     * step. Tapping again corrects the same entry rather than creating a
     * second one.
     */
    fun setMoodFace(face: Int) {
        viewModelScope.launch {
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val startOfDay = today.atStartOfDay(zone).toInstant().toEpochMilli()
            val startOfTomorrow = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val mood = moodValueOf(face).toUInt()
            runCatching {
                val existing = todayEntry(startOfDay, startOfTomorrow)
                if (existing == null) {
                    journals.add(
                        NewJournalEntry(
                            atMs = System.currentTimeMillis(),
                            mood = mood,
                            dysphoria = null,
                            euphoria = null,
                            libido = null,
                            energy = null,
                            freeText = null,
                            sideEffects = null,
                        )
                    )
                } else {
                    journals.replace(
                        existing.id,
                        NewJournalEntry(
                            atMs = existing.atMs,
                            mood = mood,
                            dysphoria = existing.dysphoria,
                            euphoria = existing.euphoria,
                            libido = existing.libido,
                            energy = existing.energy,
                            freeText = existing.freeText,
                            sideEffects = existing.sideEffects,
                        )
                    )
                }
            }
            refresh()
        }
    }

    fun markModuleOpened(module: ModuleBadgePrefs.Module) {
        badges.markOpened(module)
        refresh()
    }

    private suspend fun todayEntry(startOfDay: Long, startOfTomorrow: Long) =
        runCatching { journals.list(0, 20) }.getOrDefault(emptyList())
            .firstOrNull { it.atMs in startOfDay until startOfTomorrow }

    /**
     * At most two badges on screen, counters before dots, then launcher order.
     * More than that turns the home into a wall of red.
     */
    private suspend fun computeBadges(
        modules: Modules,
        doses: List<DoseItem>,
    ): Map<ModuleBadgePrefs.Module, Badge> {
        val counters = buildList {
            if (modules.meds) {
                val pending = doses.count { !it.done }
                if (pending > 0) add(ModuleBadgePrefs.Module.Meds to Badge.Counter(pending))
            }
        }
        val dots = buildList {
            if (modules.labs) {
                // No "list every measurement" call exists, and the per-analyte
                // list is ASC, so the newest sample is the last of each series.
                val analytes = runCatching { hormones.distinct() }.getOrDefault(emptyList())
                val newest = analytes.maxOfOrNull { analyte ->
                    runCatching { hormones.listForHormone(analyte) }
                        .getOrDefault(emptyList())
                        .maxOfOrNull { it.atMs } ?: 0L
                } ?: 0L
                if (newest > badges.lastOpened(ModuleBadgePrefs.Module.Labs)) {
                    add(ModuleBadgePrefs.Module.Labs to Badge.News)
                }
            }
        }
        return (counters + dots).take(MAX_BADGES).toMap()
    }

    private companion object {
        const val MAX_BADGES = 2

        /** Face 1..5 → a 0-10 mood, centred on the five buckets. */
        fun moodValueOf(face: Int): Int = when (face) {
            1 -> 0
            2 -> 3
            3 -> 5
            4 -> 8
            else -> 10
        }

        /** Inverse of [moodValueOf], for any stored 0-10 value. */
        fun faceOf(mood: Int): Int = when {
            mood <= 1 -> 1
            mood <= 4 -> 2
            mood <= 6 -> 3
            mood <= 9 -> 4
            else -> 5
        }
    }
}
