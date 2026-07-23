package com.douxev.eggshell.data

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.transition.BleedingEntry
import uniffi.transition.NewBleedingEntry

/**
 * Bleeding / cycle tracking entries. Scalar fields (date, spotting flag, note)
 * live here; the slider values live in [MetricsRepository] under
 * [MetricsRepository.DOMAIN_BLEEDING]. Edits are in place so the entry id stays
 * stable for its slider values.
 */
@Singleton
class BleedingRepository @Inject constructor(
    private val vault: VaultRepository,
) {
    suspend fun list(offset: Long = 0, limit: Long = 200): List<BleedingEntry> =
        withContext(Dispatchers.IO) { vault.requireSession().listBleedingEntries(offset, limit) }

    suspend fun get(id: Long): BleedingEntry? =
        withContext(Dispatchers.IO) { vault.requireSession().getBleedingEntry(id) }

    suspend fun add(entry: NewBleedingEntry): BleedingEntry =
        withContext(Dispatchers.IO) { vault.requireSession().addBleedingEntry(entry) }

    /** Batch insert (one core transaction) — the "log a span of days" flow. */
    suspend fun addMany(entries: List<NewBleedingEntry>): List<BleedingEntry> =
        withContext(Dispatchers.IO) { vault.requireSession().addBleedingEntries(entries) }

    suspend fun update(id: Long, entry: NewBleedingEntry): BleedingEntry =
        withContext(Dispatchers.IO) { vault.requireSession().updateBleedingEntry(id, entry) }

    suspend fun delete(id: Long) =
        withContext(Dispatchers.IO) { vault.requireSession().deleteBleedingEntry(id) }
}
