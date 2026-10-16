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
    private val mirrors: com.douxev.eggshell.widget.WidgetMirrorUpdater,
) {
    /**
     * Kept in step after every write. A widget configured to show content
     * reads a mirror, not the vault — so an entry saved and not mirrored is a
     * home screen quietly showing the previous state. Cheap when nobody opted
     * in, which is the default, and never allowed to turn a successful write
     * into a failure.
     */
    private suspend fun mirrorChanged() {
        runCatching { mirrors.refresh() }
    }

    suspend fun list(offset: Long = 0, limit: Long = 50): List<JournalEntry> =
        withContext(Dispatchers.IO) { vault.requireSession().listJournalEntries(offset, limit) }

    suspend fun add(entry: NewJournalEntry): JournalEntry =
        withContext(Dispatchers.IO) { vault.requireSession().addJournalEntry(entry) }
            .also { mirrorChanged() }

    suspend fun delete(id: Long) =
        withContext(Dispatchers.IO) { vault.requireSession().deleteJournalEntry(id) }
            .also { mirrorChanged() }

    suspend fun get(id: Long): JournalEntry? =
        withContext(Dispatchers.IO) { vault.requireSession().getJournalEntry(id) }

    /**
     * Update an entry in place. The id stays stable, so any custom slider
     * values keyed on it (see [MetricsRepository]) survive the edit.
     */
    suspend fun replace(id: Long, entry: NewJournalEntry): JournalEntry =
        withContext(Dispatchers.IO) {
            vault.requireSession().updateJournalEntry(id, entry)
        }.also { mirrorChanged() }
}
