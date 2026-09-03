package com.douxev.eggshell.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.douxev.eggshell.ui.notes.IMAGE_SCHEME

/**
 * Export notes as a zip of markdown plus their images.
 *
 * Never a bare `.md`. Exporting markdown on its own silently drops every
 * attachment — the exact trap Joplin documents in its own export notes — and a
 * note whose pictures vanished is worse than no export, because the loss is
 * invisible until someone looks for them.
 *
 * The layout is the de-facto interchange shape used by both Obsidian vaults and
 * Joplin's raw export: markdown at the root, images in an `assets/` folder
 * beside it, referenced by relative path. That imports cleanly into either.
 */
@Singleton
class NoteExporter @Inject constructor(
    private val notes: NotesRepository,
    @ApplicationContext private val context: Context,
) : NoteArchiver {
    private val shareDir: File by lazy {
        File(context.cacheDir, "note_export").apply { mkdirs() }
    }

    /**
     * Write a zip for [noteIds] and return it, ready for a share sheet.
     *
     * Decrypted content lands in the cache for as long as the share takes;
     * previous exports are swept first, and the app's background purge clears
     * the directory when it leaves the foreground.
     */
    override suspend fun exportToCache(noteIds: List<Long>): File = withContext(Dispatchers.IO) {
        runCatching { shareDir.listFiles()?.forEach { it.delete() } }
        val out = File(shareDir, "notes-${System.currentTimeMillis()}.zip")

        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            val usedNames = HashSet<String>()
            for (id in noteIds) {
                val note = notes.get(id) ?: continue
                val images = runCatching { notes.images(id) }.getOrDefault(emptyList())

                // Rewrite the in-app references into relative paths another
                // editor can follow. The scheme is ours; `assets/<n>.jpg` is
                // what every markdown tool already understands.
                var body = note.body
                images.forEachIndexed { index, img ->
                    val assetName = "assets/${id}-$index.jpg"
                    body = body.replace("$IMAGE_SCHEME:${img.id}", assetName)
                    runCatching {
                        zip.putNextEntry(ZipEntry(assetName))
                        zip.write(notes.decrypt(img))
                        zip.closeEntry()
                    }
                }

                val name = uniqueMarkdownName(note.title, id, usedNames)
                zip.putNextEntry(ZipEntry(name))
                // Title as an H1 so the file stands on its own once the note's
                // metadata is gone.
                zip.write("# ${note.title}\n\n$body\n".toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        out.deleteOnExit()
        out
    }

    /** Wipe decrypted exports. Called from the app's background purge. */
    fun purgeExports() {
        runCatching { shareDir.listFiles()?.forEach { it.delete() } }
    }

    /**
     * A filename a filesystem will accept, that stays recognisable, and that
     * cannot collide with another note's.
     */
    private fun uniqueMarkdownName(title: String, id: Long, used: MutableSet<String>): String {
        val slug = title.trim()
            .replace(Regex("""[^\p{L}\p{N} _-]"""), "")
            .replace(Regex("""\s+"""), "-")
            .take(60)
            .trim('-')
            .ifBlank { "note" }
        var candidate = "$slug.md"
        // Two notes may legitimately share a title; the id disambiguates
        // rather than one silently overwriting the other inside the archive.
        if (!used.add(candidate)) {
            candidate = "$slug-$id.md"
            used.add(candidate)
        }
        return candidate
    }
}
