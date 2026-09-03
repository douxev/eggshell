package com.douxev.eggshell.data

import android.net.Uri
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton
import uniffi.transition.Note
import uniffi.transition.NoteImage

/**
 * The slice of [NotesRepository] the note editor uses.
 *
 * It exists so the editor's save rules — when a note is created, when it is
 * merely updated, and which folder a new one lands in — can be exercised
 * without a vault, a Keystore or a device. That is not abstraction for its own
 * sake: those rules are where "creating a note does nothing" came from, and
 * they were untestable while the only way to reach them was a real
 * SQLCipher-backed repository behind a `Context`.
 */
interface NoteStore {
    suspend fun get(id: Long): Note?

    /**
     * [folderId] has no default on purpose. It used to default to null, and
     * the editor — which never knew which folder it had been opened from —
     * took that default: every note written inside a folder was silently
     * filed at the root, where the person who wrote it did not look for it.
     */
    suspend fun create(title: String, body: String, folderId: Long?): Note
    suspend fun update(id: Long, title: String, body: String): Note
    suspend fun delete(id: Long)
    suspend fun images(noteId: Long): List<NoteImage>
    suspend fun attachImage(noteId: Long, uri: Uri): NoteImage
    suspend fun detachImage(image: NoteImage)
    suspend fun decrypt(image: NoteImage): ByteArray
}

/** The slice of [NoteExporter] the editor uses. See [NoteStore] for why. */
interface NoteArchiver {
    suspend fun exportToCache(noteIds: List<Long>): File
}

@Module
@InstallIn(SingletonComponent::class)
abstract class NotesBindings {
    @Binds
    @Singleton
    abstract fun noteStore(impl: NotesRepository): NoteStore

    @Binds
    @Singleton
    abstract fun noteArchiver(impl: NoteExporter): NoteArchiver
}
