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
     * Replace an entry. The core's UDL doesn't expose a true update yet, so
     * we delete-then-add. The returned entry has a fresh id.
     */
    suspend fun replace(id: Long, entry: NewJournalEntry): JournalEntry =
        withContext(Dispatchers.IO) {
            val session = vault.requireSession()
            session.deleteJournalEntry(id)
            session.addJournalEntry(entry)
        }
}
