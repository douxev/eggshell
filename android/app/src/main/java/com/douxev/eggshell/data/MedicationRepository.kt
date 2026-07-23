package com.douxev.eggshell.data

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.transition.DoseEvent
import uniffi.transition.Medication
import uniffi.transition.NewDoseEvent
import uniffi.transition.NewMedication
import uniffi.transition.NewTreatmentChange
import uniffi.transition.TreatmentChange
import uniffi.transition.standardInjectionSites

/**
 * Thin wrapper around the open Vault that runs all UniFFI calls off the main
 * thread and hides the `requireSession()` call from view models.
 */
@Singleton
class MedicationRepository @Inject constructor(
    private val vault: VaultRepository,
) {
    suspend fun list(includeArchived: Boolean = false): List<Medication> =
        withContext(Dispatchers.IO) { vault.requireSession().listMedications(includeArchived) }

    suspend fun get(id: Long): Medication? =
        withContext(Dispatchers.IO) { vault.requireSession().getMedication(id) }

    suspend fun add(med: NewMedication, nowMs: Long = System.currentTimeMillis()): Medication =
        withContext(Dispatchers.IO) { vault.requireSession().addMedication(med, nowMs) }

    suspend fun setArchived(id: Long, archived: Boolean) =
        withContext(Dispatchers.IO) { vault.requireSession().setMedicationArchived(id, archived) }

    /** Full overwrite of an existing medication (used by the edit screen). */
    suspend fun update(id: Long, med: NewMedication) =
        withContext(Dispatchers.IO) { vault.requireSession().updateMedication(id, med) }

    suspend fun logDose(dose: NewDoseEvent): DoseEvent =
        withContext(Dispatchers.IO) { vault.requireSession().logDose(dose) }

    /** Batch insert (one core transaction) — the "log a date range" flow. */
    suspend fun logDoses(doses: List<NewDoseEvent>): List<DoseEvent> =
        withContext(Dispatchers.IO) { vault.requireSession().logDoses(doses) }

    /** Overwrite a recorded dose in place (fix route/date/amount after the fact). */
    suspend fun updateDose(id: Long, dose: NewDoseEvent): DoseEvent =
        withContext(Dispatchers.IO) { vault.requireSession().updateDose(id, dose) }

    suspend fun getDose(id: Long): DoseEvent? =
        withContext(Dispatchers.IO) { vault.requireSession().getDose(id) }

    suspend fun listDoses(medicationId: Long, offset: Long = 0, limit: Long = 50): List<DoseEvent> =
        withContext(Dispatchers.IO) { vault.requireSession().listDoses(medicationId, offset, limit) }

    /** Remove a single recorded dose from the history. */
    suspend fun deleteDose(id: Long) =
        withContext(Dispatchers.IO) { vault.requireSession().deleteDose(id) }

    /** Hard-delete a medication and its cascaded history (doses, schedules,
     *  treatment changes). Off-vault alarms/prefs are cleaned up by
     *  [ScheduleRepository.deleteMedicationCleanup] — call that first. */
    suspend fun delete(id: Long) =
        withContext(Dispatchers.IO) { vault.requireSession().deleteMedication(id) }

    /** All dose events (taken / skipped / missed) across meds in a window. */
    suspend fun listDoseEventsBetween(fromMs: Long, toMs: Long): List<DoseEvent> =
        withContext(Dispatchers.IO) { vault.requireSession().listDoseEventsBetween(fromMs, toMs) }

    suspend fun logTreatmentChange(change: NewTreatmentChange): TreatmentChange =
        withContext(Dispatchers.IO) { vault.requireSession().logTreatmentChange(change) }

    suspend fun listTreatmentChanges(fromMs: Long, toMs: Long): List<TreatmentChange> =
        withContext(Dispatchers.IO) { vault.requireSession().listTreatmentChanges(fromMs, toMs) }

    suspend fun suggestNextInjectionSite(medicationId: Long, historyDepth: Long = 10): String? =
        withContext(Dispatchers.IO) {
            vault.requireSession().suggestNextInjectionSite(medicationId, historyDepth)
        }

    /** Static list of canonical injection-site identifiers shipped by the core. */
    val standardInjectionSites: List<String> by lazy { standardInjectionSites() }
}
