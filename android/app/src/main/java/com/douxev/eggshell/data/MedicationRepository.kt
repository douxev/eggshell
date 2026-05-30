package com.douxev.eggshell.data

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.transition.DoseEvent
import uniffi.transition.Medication
import uniffi.transition.NewDoseEvent
import uniffi.transition.NewMedication
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

    suspend fun logDose(dose: NewDoseEvent): DoseEvent =
        withContext(Dispatchers.IO) { vault.requireSession().logDose(dose) }

    suspend fun listDoses(medicationId: Long, offset: Long = 0, limit: Long = 50): List<DoseEvent> =
        withContext(Dispatchers.IO) { vault.requireSession().listDoses(medicationId, offset, limit) }

    suspend fun suggestNextInjectionSite(medicationId: Long, historyDepth: Long = 10): String? =
        withContext(Dispatchers.IO) {
            vault.requireSession().suggestNextInjectionSite(medicationId, historyDepth)
        }

    /** Static list of canonical injection-site identifiers shipped by the core. */
    val standardInjectionSites: List<String> by lazy { standardInjectionSites() }
}
