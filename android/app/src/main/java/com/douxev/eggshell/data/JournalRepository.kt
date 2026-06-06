package com.douxev.eggshell.data

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.transition.JournalEntry
import uniffi.transition.NewJournalEntry

@Singleton
class JournalRepository @Inject constructor(
    private val vault: VaultRepository,
) {
    suspend fun list(offset: Long = 0, limit: Long = 50): List<JournalEntry> =
        withContext(Dispatchers.IO) { vault.requireSession().listJournalEntries(offset, limit) }

    suspend fun add(entry: NewJournalEntry): JournalEntry =
        withContext(Dispatchers.IO) { vault.requireSession().addJournalEntry(entry) }

    suspend fun delete(id: Long) =
        withContext(Dispatchers.IO) { vault.requireSession().deleteJournalEntry(id) }

    suspend fun get(id: Long): JournalEntry? =
        withContext(Dispatchers.IO) { vault.requireSession().getJournalEntry(id) }

    /**
     * Update an entry in place. The id stays stable, so any custom slider
     * values keyed on it (see [MetricsRepository]) survive the edit.
     */
    suspend fun replace(id: Long, entry: NewJournalEntry): JournalEntry =
        withContext(Dispatchers.IO) {
            vault.requireSession().updateJournalEntry(id, entry)
        }
}
