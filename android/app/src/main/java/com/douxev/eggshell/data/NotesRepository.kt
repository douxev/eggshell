package com.douxev.eggshell.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.transition.NewNote
import uniffi.transition.NewNoteImage
import uniffi.transition.Note
import uniffi.transition.NoteImage

/**
 * Notes and the images embedded in them.
 *
 * Images get their own directory rather than sharing `photos/`. That is not
 * tidiness: [PhotosRepository.cleanupOrphans] deletes every `.bin` under
 * `photos/` whose basename is absent from `photo_records`, and it runs at every
 * unlock — note images living there would be gone the first time the user
 * locked the app. Giving them a photo_records row instead would put private
 * note attachments into the progress-photo gallery and the doctor PDF, trading
 * silent data loss for silent data leakage.
 */
@Singleton
class NotesRepository @Inject constructor(
    private val vault: VaultRepository,
    @ApplicationContext private val context: Context,
) {
    private val imagesDir: File by lazy {
        File(context.filesDir, "note_images").apply { mkdirs() }
    }
    private val cacheDir: File by lazy {
        File(context.cacheDir, "note_images").apply { mkdirs() }
    }

    suspend fun list(folderId: Long?): List<Note> = withContext(Dispatchers.IO) {
        vault.requireSession().listNotes(folderId)
    }

    suspend fun folders(parentId: Long?): List<uniffi.transition.NoteFolder> =
        withContext(Dispatchers.IO) { vault.requireSession().listNoteFolders(parentId) }

    suspend fun createFolder(name: String, parentId: Long?): uniffi.transition.NoteFolder =
        withContext(Dispatchers.IO) {
            vault.requireSession().addNoteFolder(
                uniffi.transition.NewNoteFolder(
                    name = name, parentId = parentId, createdMs = System.currentTimeMillis(),
                )
            )
        }

    suspend fun renameFolder(id: Long, name: String) = withContext(Dispatchers.IO) {
        vault.requireSession().renameNoteFolder(id, name)
    }

    /** How many notes a folder deletion would take with it, subfolders included. */
    suspend fun folderContentsCount(id: Long): Long = withContext(Dispatchers.IO) {
        vault.requireSession().noteFolderContentsCount(id)
    }

    /**
     * Delete a folder, everything nested inside it, and the image files those
     * notes owned.
     *
     * The paths are collected BEFORE the delete: the SQL cascade removes the
     * rows that name them, and after that nothing on disk says which files
     * belonged to anything.
     */
    suspend fun deleteFolder(id: Long) = withContext(Dispatchers.IO) {
        val session = vault.requireSession()
        val doomed = runCatching { session.noteImagePathsUnderFolder(id) }.getOrDefault(emptyList())
        session.deleteNoteFolder(id)
        doomed.forEach { runCatching { File(it).delete() } }
    }

    suspend fun moveToFolder(noteId: Long, folderId: Long?) = withContext(Dispatchers.IO) {
        vault.requireSession().moveNoteToFolder(noteId, folderId)
    }

    suspend fun get(id: Long): Note? = withContext(Dispatchers.IO) {
        vault.requireSession().getNote(id)
    }

    suspend fun create(title: String, body: String, folderId: Long? = null): Note =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            vault.requireSession().addNote(
                NewNote(
                    folderId = folderId, title = title, body = body,
                    createdMs = now, updatedMs = now,
                )
            )
        }

    suspend fun update(id: Long, title: String, body: String): Note = withContext(Dispatchers.IO) {
        vault.requireSession().updateNote(id, title, body, System.currentTimeMillis())
    }

    /**
     * Delete the note, its image rows (cascaded in SQL) and their ciphertext.
     *
     * The files are read BEFORE the row goes: once the cascade has run there is
     * nothing left to say which files belonged to this note, and they would sit
     * on disk until the orphan sweep noticed.
     */
    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        val session = vault.requireSession()
        val doomed = runCatching { session.noteImages(id) }.getOrDefault(emptyList())
        session.deleteNote(id)
        doomed.forEach { runCatching { File(it.filePath).delete() } }
    }

    suspend fun reorder(idsInOrder: List<Long>) = withContext(Dispatchers.IO) {
        vault.requireSession().reorderNotes(idsInOrder)
    }

    // -- images --------------------------------------------------------------

    suspend fun images(noteId: Long): List<NoteImage> = withContext(Dispatchers.IO) {
        vault.requireSession().noteImages(noteId)
    }

    /**
     * Encrypt a picked image into the note's own store.
     *
     * Same tmp + fsync + rename dance as photos, and the same EXIF strip: an
     * attachment dropped into a note is exactly as revealing as one added to
     * the gallery, so it must not carry GPS or camera identity either.
     */
    suspend fun attachImage(noteId: Long, uri: Uri): NoteImage = withContext(Dispatchers.IO) {
        val session = vault.requireSession()
        val cleaned = readAndStripExif(uri) ?: error("could not decode image at $uri")
        val ciphertext = session.encryptBlob(cleaned)
        val id = UUID.randomUUID().toString()
        val tmp = File(imagesDir, "$id.tmp")
        val final = File(imagesDir, "$id.bin")
        FileOutputStream(tmp).use { it.write(ciphertext); it.fd.sync() }
        if (!tmp.renameTo(final)) {
            tmp.copyTo(final, overwrite = true)
            tmp.delete()
        }
        val position = images(noteId).size.toLong()
        try {
            session.addNoteImage(
                NewNoteImage(noteId = noteId, filePath = final.absolutePath, position = position)
            )
        } catch (t: Throwable) {
            final.delete()
            throw t
        }
    }

    suspend fun detachImage(image: NoteImage) = withContext(Dispatchers.IO) {
        vault.requireSession().deleteNoteImage(image.id)
        runCatching { File(image.filePath).delete() }
    }

    suspend fun decrypt(image: NoteImage): ByteArray = withContext(Dispatchers.IO) {
        vault.requireSession().decryptBlob(File(image.filePath).readBytes())
    }

    /** Wipe decrypted copies. Called from the app's background purge. */
    fun purgeAllCache() {
        runCatching { cacheDir.listFiles()?.forEach { it.delete() } }
    }

    /**
     * Delete ciphertext with no row behind it — a crash between the file write
     * and the INSERT, or a note deleted while its files were unreachable.
     *
     * Compares BASENAMES, not full paths: a restored backup carries rows whose
     * `file_path` was absolute on the source device, and the core repoints them
     * at import. Matching on the whole path would make every restored image
     * look orphaned and delete the lot at the first unlock.
     */
    suspend fun cleanupOrphans() = withContext(Dispatchers.IO) {
        val tracked = vault.requireSession().allNoteImagePaths()
            .map { File(it).name }
            .toHashSet()
        imagesDir.listFiles()?.forEach { f ->
            if (f.name.endsWith(".tmp")) { f.delete(); return@forEach }
            if (f.name.endsWith(".bin") && f.name !in tracked) f.delete()
        }
    }

    private fun readAndStripExif(uri: Uri): ByteArray? {
        val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val orientation = runCatching {
            ExifInterface(raw.inputStream())
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val src = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return null
        val oriented = applyOrientation(src, orientation)
        val out = ByteArrayOutputStream(raw.size)
        oriented.compress(Bitmap.CompressFormat.JPEG, 90, out)
        if (oriented !== src) oriented.recycle()
        src.recycle()
        return out.toByteArray()
    }

    private fun applyOrientation(src: Bitmap, exifOrientation: Int): Bitmap {
        if (exifOrientation == ExifInterface.ORIENTATION_NORMAL ||
            exifOrientation == ExifInterface.ORIENTATION_UNDEFINED
        ) return src
        val m = android.graphics.Matrix()
        when (exifOrientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            else -> return src
        }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }
}
