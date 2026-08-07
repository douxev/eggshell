package com.douxev.eggshell.data

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.transition.MetricDefinition
import uniffi.transition.MetricDefinitionUpdate
import uniffi.transition.MetricValue
import uniffi.transition.NewMetricDefinition

/**
 * The shared customizable-metric model: slider DEFINITIONS (catalog) and their
 * per-entry VALUES, used by both the feelings journal (domain [DOMAIN_JOURNAL])
 * and the bleeding/cycle tracker (domain [DOMAIN_BLEEDING]).
 *
 * Built-in journal gauges (mood/dysphoria/…) are seeded with a [columnName] and
 * keep their value in the journal_entries columns; built-in bleeding gauges and
 * every user-defined slider store their value here, keyed on the parent entry
 * id (stable thanks to the in-place update added to the core).
 */
@Singleton
class MetricsRepository @Inject constructor(
    private val vault: VaultRepository,
) {
    suspend fun definitions(domain: String, includeArchived: Boolean = false): List<MetricDefinition> =
        withContext(Dispatchers.IO) {
            vault.requireSession().listMetricDefinitions(domain, includeArchived)
        }

    suspend fun addDefinition(def: NewMetricDefinition): MetricDefinition =
        withContext(Dispatchers.IO) { vault.requireSession().addMetricDefinition(def) }

    suspend fun updateDefinition(id: Long, upd: MetricDefinitionUpdate) =
        withContext(Dispatchers.IO) { vault.requireSession().updateMetricDefinition(id, upd) }

    /** Soft-deletes a custom definition (built-ins are rejected by the core). */
    suspend fun archiveDefinition(id: Long) =
        withContext(Dispatchers.IO) { vault.requireSession().archiveMetricDefinition(id) }

    suspend fun values(domain: String, entryId: Long): List<MetricValue> =
        withContext(Dispatchers.IO) { vault.requireSession().listMetricValues(domain, entryId) }

    suspend fun replaceValues(domain: String, entryId: Long, values: List<MetricValue>) =
        withContext(Dispatchers.IO) {
            vault.requireSession().replaceMetricValues(domain, entryId, values)
        }

    companion object {
        const val DOMAIN_JOURNAL = "journal"
        const val DOMAIN_BLEEDING = "bleeding"
        /** Sleep sliders of the dream journal, seeded by migration 0016. */
        const val DOMAIN_DREAMS = "dreams"
    }
}
