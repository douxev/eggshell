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
import uniffi.transition.NewPhotoRecord
import uniffi.transition.PhotoRecord

@Singleton
class PhotosRepository @Inject constructor(
    private val vault: VaultRepository,
    @ApplicationContext private val context: Context,
) {
    private val photosDir: File by lazy {
        File(context.filesDir, "photos").apply { mkdirs() }
    }
    private val shareCacheDir: File by lazy {
        File(context.cacheDir, "photo_export").apply { mkdirs() }
    }

    suspend fun list(offset: Long = 0, limit: Long = 200): List<PhotoRecord> =
        withContext(Dispatchers.IO) { vault.requireSession().listPhotoRecords(offset, limit) }

    /**
     * Read the photo from a content URI, strip EXIF metadata (GPS, camera
     * model, original timestamp — anything that could deanonymise the user
     * or reveal location), re-encode as JPEG, encrypt the ciphertext to disk
     * via `<id>.tmp` + fsync + rename, then INSERT the DB row. If the INSERT
     * fails we delete the renamed file so we don't leak orphan ciphertext.
     */
    suspend fun importFromUri(uri: Uri, category: String?) = withContext(Dispatchers.IO) {
        val session = vault.requireSession()
        val cleaned = readAndStripExif(uri)
            ?: error("could not decode image at $uri")
        val ciphertext = session.encryptBlob(cleaned)
        val id = UUID.randomUUID().toString()
        val tmp = File(photosDir, "$id.tmp")
        val final = File(photosDir, "$id.bin")

        // 1. Write ciphertext to .tmp and fsync so we know it's on disk.
        FileOutputStream(tmp).use { fos ->
            fos.write(ciphertext)
            fos.fd.sync()
        }
        // 2. Atomic rename to its final name.
        if (!tmp.renameTo(final)) {
            // Best-effort copy-fallback for file systems that refuse cross-dir
            // rename (shouldn't happen — same dir — but defend anyway).
            tmp.copyTo(final, overwrite = true)
            tmp.delete()
        }
        // 3. Now insert the DB row. If this throws we delete the file so we
        //    don't leave orphan ciphertext that the orphan scan would also
        //    have to clean up.
        try {
            session.addPhotoRecord(
                NewPhotoRecord(
                    atMs = System.currentTimeMillis(),
                    category = category,
                    filePath = final.absolutePath,
                    notes = null,
                )
            )
        } catch (t: Throwable) {
            final.delete()
            throw t
        }
    }

    suspend fun decryptToBytes(record: PhotoRecord): ByteArray = withContext(Dispatchers.IO) {
        val ciphertext = File(record.filePath).readBytes()
        vault.requireSession().decryptBlob(ciphertext)
    }

    suspend fun delete(record: PhotoRecord) = withContext(Dispatchers.IO) {
        runCatching { File(record.filePath).delete() }
        vault.requireSession().deletePhotoRecord(record.id)
    }

    /**
     * Decrypts the photo into a temp JPEG in the FileProvider-shared sub-cache
     * and returns the file. Callers share via Intent.ACTION_SEND; we clean
     * stale files (older than 10 min) on each call so a crash doesn't leave
     * decrypted images sitting around indefinitely.
     */
    suspend fun decryptToCache(record: PhotoRecord): File = withContext(Dispatchers.IO) {
        purgeStaleCache()
        val plain = decryptToBytes(record)
        val out = File(shareCacheDir, "photo-${record.id}-${System.currentTimeMillis()}.jpg")
        out.writeBytes(plain)
        out.deleteOnExit()
        out
    }

    /**
     * Wipe every file in the share cache. Called on lock / app pause so
     * decrypted thumbnails don't linger after the user steps away.
     */
    fun purgeAllCache() {
        runCatching {
            shareCacheDir.listFiles()?.forEach { it.delete() }
        }
    }

    private fun purgeStaleCache() {
        val cutoff = System.currentTimeMillis() - STALE_CACHE_MS
        runCatching {
            shareCacheDir.listFiles()?.forEach { f ->
                if (f.lastModified() < cutoff) f.delete()
            }
        }
    }

    /**
     * Compare the on-disk `*.bin` ciphertext files against the DB's
     * `photo_records.file_path` set. Anything in the directory that has no
     * matching DB row is orphaned (mid-write crash, race delete) and
     * deleted. Also wipes any stale `.tmp` files. Call at unlock.
     */
    suspend fun cleanupOrphans() = withContext(Dispatchers.IO) {
        val session = vault.requireSession()
        // Page through all records — list() is keyed off the photo_records
        // table; a large library still fits in memory because we only keep
        // the file paths, not the blobs.
        val tracked = HashSet<String>()
        var offset = 0L
        val pageSize = 500L
        while (true) {
            val page = session.listPhotoRecords(offset, pageSize)
            if (page.isEmpty()) break
            page.forEach { tracked.add(File(it.filePath).name) }
            if (page.size < pageSize.toInt()) break
            offset += pageSize
        }
        photosDir.listFiles()?.forEach { f ->
            val name = f.name
            if (name.endsWith(".tmp")) {
                f.delete(); return@forEach
            }
            if (name.endsWith(".bin") && name !in tracked) {
                f.delete()
            }
        }
    }

    /**
     * Decode the incoming image, drop every EXIF tag, recompress as JPEG at
     * a quality (92) that keeps the visual fidelity our use case needs.
     *
     * Why decode-and-recompress instead of just rewriting the EXIF block:
     *  - Some apps embed GPS / camera info in vendor-specific maker notes
     *    or in XMP that ExifInterface.removeAttribute doesn't touch.
     *  - The orientation tag matters: we apply it via Matrix before
     *    re-encoding so the resulting JPEG always reads as orientation 1
     *    (top-left) with no metadata to follow.
     */
    private fun readAndStripExif(uri: Uri): ByteArray? {
        val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return null
        val orientation = runCatching {
            ExifInterface(raw.inputStream())
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val src = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return null
        val oriented = applyOrientation(src, orientation)
        val out = ByteArrayOutputStream(raw.size)
        oriented.compress(Bitmap.CompressFormat.JPEG, 92, out)
        if (oriented !== src) oriented.recycle()
        src.recycle()
        return out.toByteArray()
    }

    private fun applyOrientation(src: Bitmap, exifOrientation: Int): Bitmap {
        if (exifOrientation == ExifInterface.ORIENTATION_NORMAL ||
            exifOrientation == ExifInterface.ORIENTATION_UNDEFINED) return src
        val m = android.graphics.Matrix()
        when (exifOrientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return src
        }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    companion object {
        private const val STALE_CACHE_MS = 10L * 60L * 1000L // 10 minutes
    }
}
