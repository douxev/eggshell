package com.douxev.eggshell.ui.today

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
import com.douxev.eggshell.data.JournalRepository
import com.douxev.eggshell.data.MedicationRepository
import com.douxev.eggshell.data.ScheduleRepository
import com.douxev.eggshell.reminders.LabReminderManager
import com.douxev.eggshell.reminders.LabReminderPrefs
import uniffi.transition.DoseSchedule
import uniffi.transition.Medication

@HiltViewModel
class TodayViewModel @Inject constructor(
    private val schedules: ScheduleRepository,
    private val medications: MedicationRepository,
    private val journals: JournalRepository,
    private val labs: LabReminderManager,
    private val features: FeaturesPrefs,
) : ViewModel() {

    data class TodayItem(
        val scheduleId: Long,
        val medication: Medication,
        val scheduledAtMs: Long,
        val done: Boolean,
    )

    /** A future reminder line (med or non-med) shown in the Rappels widget. */
    data class UpcomingReminder(
        val kind: Kind,
        val title: String,
        val subtitle: String,
        val dueAtMs: Long,
        /** Which medication ID to open when the row is tapped (null for non-med). */
        val medicationId: Long?,
    ) {
        enum class Kind { Medication, Lab, Photo, Voice }
    }

    /** Mirror of [FeaturesPrefs] flags, surfaced to the screen so it can hide
     *  sections that don't apply when a feature is disabled. */
    data class FeatureGates(
        val medications: Boolean,
        val journal: Boolean,
        val photos: Boolean,
        val voice: Boolean,
    )

    data class State(
        val items: List<TodayItem> = emptyList(),
        val moodTrend: List<Int> = emptyList(),
        val upcomingReminders: List<UpcomingReminder> = emptyList(),
        /** Whether the user has any medication at all. Lets the Today screen
         *  tell apart "no schedule yet" from "no medication yet" so the
         *  hero-card CTA can route to the right setup screen. */
        val hasMedications: Boolean = false,
        val gates: FeatureGates = FeatureGates(
            medications = true, journal = true, photos = false, voice = false,
        ),
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            // Snapshot the flags upfront so the rest of the pipeline is
            // a pure function of them — avoids races where a toggle flips
            // between query and state-assignment.
            val gates = FeatureGates(
                medications = features.medications.value,
                journal = features.journal.value,
                photos = features.photoTab.value,
                voice = features.voiceTab.value,
            )

            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val startOfDay = today.atStartOfDay(zone).toInstant().toEpochMilli()
            val startOfTomorrow = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val now = System.currentTimeMillis()

            // Medication-derived state is empty when the Médics feature is off
            // — saves a DB hit and short-circuits the upcoming-reminders mix.
            var hasMeds = false
            val (items, futureMeds) = if (!gates.medications) {
                emptyList<TodayItem>() to emptyList<UpcomingReminder>()
            } else {
                val allMeds = runCatching { medications.list() }.getOrDefault(emptyList())
                hasMeds = allMeds.isNotEmpty()
                val medById = allMeds.associateBy { it.id }
                val activeSchedules: List<DoseSchedule> = allMeds.flatMap { med ->
                    runCatching { schedules.listForMedication(med.id, includeInactive = false) }
                        .getOrDefault(emptyList())
                }
                val todayItems = activeSchedules
                    .filter { it.nextDueAtMs in startOfDay until startOfTomorrow }
                    .sortedBy { it.nextDueAtMs }
                    .mapNotNull { s ->
                        val med = medById[s.medicationId] ?: return@mapNotNull null
                        val doses = runCatching {
                            medications.listDoses(med.id, 0, 5)
                        }.getOrDefault(emptyList())
                        val done = doses.any { it.takenAtMs in startOfDay until startOfTomorrow }
                        TodayItem(s.id, med, s.nextDueAtMs, done)
                    }
                val medReminders = activeSchedules
                    .filter { it.nextDueAtMs >= startOfTomorrow }
                    .mapNotNull { s ->
                        val med = medById[s.medicationId] ?: return@mapNotNull null
                        UpcomingReminder(
                            kind = UpcomingReminder.Kind.Medication,
                            title = med.name,
                            subtitle = scheduleSubtitle(s, med),
                            dueAtMs = s.nextDueAtMs,
                            medicationId = med.id,
                        )
                    }
                todayItems to medReminders
            }

            // Mood trend only when the journal is enabled.
            val mood: List<Int> = if (!gates.journal) emptyList() else {
                val recent = runCatching { journals.list(0, 14) }.getOrDefault(emptyList())
                recent.mapNotNull { it.mood?.toInt() }.reversed()
            }

            // Lab/photo/voice reminders: lab is always allowed; photo/voice
            // only if their respective feature is on.
            val futureLabs = runCatching { labs.list() }.getOrDefault(emptyList())
                .filter { it.nextDueAtMs >= now }
                .mapNotNull { entry ->
                    val kind = when (entry.category) {
                        LabReminderPrefs.CATEGORY_PHOTO -> UpcomingReminder.Kind.Photo
                        LabReminderPrefs.CATEGORY_VOICE -> UpcomingReminder.Kind.Voice
                        else -> UpcomingReminder.Kind.Lab
                    }
                    when (kind) {
                        UpcomingReminder.Kind.Photo -> if (!gates.photos) return@mapNotNull null
                        UpcomingReminder.Kind.Voice -> if (!gates.voice) return@mapNotNull null
                        else -> Unit
                    }
                    UpcomingReminder(
                        kind = kind,
                        title = entry.label,
                        subtitle = labSubtitle(entry),
                        dueAtMs = entry.nextDueAtMs,
                        medicationId = null,
                    )
                }
            val upcoming = (futureMeds + futureLabs)
                .sortedBy { it.dueAtMs }
                .take(4)

            _state.value = State(
                items = items,
                moodTrend = mood,
                upcomingReminders = upcoming,
                hasMedications = hasMeds,
                gates = gates,
            )
        }
    }

    private fun scheduleSubtitle(s: DoseSchedule, med: Medication): String = when (s.kind) {
        "interval" -> {
            val hours = (s.intervalMinutes?.toInt() ?: 0) / 60
            "Toutes les $hours h"
        }
        "daily" -> String.format(
            "Tous les jours à %02d:%02d",
            s.dailyHour?.toInt() ?: 0,
            s.dailyMinute?.toInt() ?: 0,
        )
        "days_interval" -> String.format(
            "Tous les %d j à %02d:%02d",
            s.intervalDays?.toInt() ?: 0,
            s.dailyHour?.toInt() ?: 0,
            s.dailyMinute?.toInt() ?: 0,
        )
        else -> med.route
    }

    private fun labSubtitle(entry: com.douxev.eggshell.reminders.LabReminderPrefs.Entry): String =
        when (entry.kind) {
            "interval" -> "Tous les ${entry.intervalDays ?: 0} j"
            "daily" -> String.format(
                "Tous les jours à %02d:%02d",
                entry.dailyHour ?: 0,
                entry.dailyMinute ?: 0,
            )
            else -> ""
        }

    /**
     * Mark a scheduled dose as taken now: log a dose event with the
     * medication's default dose, then bump the schedule's next due time
     * forward by one occurrence.
     */
    fun markDoneNow(item: TodayItem) {
        viewModelScope.launch {
            runCatching {
                medications.logDose(
                    uniffi.transition.NewDoseEvent(
                        medicationId = item.medication.id,
                        takenAtMs = System.currentTimeMillis(),
                        dose = item.medication.defaultDose,
                        doseUnit = item.medication.defaultDoseUnit,
                        route = item.medication.route,
                        injectionSite = null,
                        notes = null,
                        scheduleId = item.scheduleId,
                    )
                )
                schedules.advanceToNextOccurrence(item.scheduleId)
            }
            refresh()
        }
    }
}
