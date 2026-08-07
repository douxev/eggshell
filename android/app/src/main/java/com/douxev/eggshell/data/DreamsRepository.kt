package com.douxev.eggshell.data

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.transition.Dream
import uniffi.transition.DreamAudio
import uniffi.transition.DreamTag
import uniffi.transition.NewDream
import uniffi.transition.NewDreamAudio

/**
 * The dream journal: entries, their tags, and their voice notes.
 *
 * Voice notes get their own `dream_audio/` directory rather than sharing
 * `voice/`. That is not tidiness — [VoiceRepository] sweeps every `.bin` under
 * `voice/` whose id is absent from `voice_clips`, and it runs at each unlock,
 * so a dream recording living there would be deleted the first time the app
 * locked. Giving it a `voice_clips` row instead would put it in the voice
 * training gallery next to pitch measurements, which is a different feature
 * with a different audience. Silent data loss on one side, silent leakage on
 * the other; a separate directory avoids both.
 */
@Singleton
class DreamsRepository @Inject constructor(
    private val vault: VaultRepository,
    @ApplicationContext private val context: Context,
) {
    private val audioDir: File by lazy {
        File(context.filesDir, "dream_audio").apply { mkdirs() }
    }
    private val cacheDir: File by lazy {
        File(context.cacheDir, "dream_audio").apply { mkdirs() }
    }

    private var player: MediaPlayer? = null
    private var playingTmp: File? = null
    private var recorder: MediaRecorder? = null
    private var recordingTmp: File? = null
    private var recordingStartedAtMs: Long = 0L

    // -- Dreams --------------------------------------------------------------

    suspend fun list(tagId: Long? = null, limit: Long = 200, offset: Long = 0): List<Dream> =
        withContext(Dispatchers.IO) {
            vault.requireSession().listDreams(tagId, limit, offset)
        }

    suspend fun listBetween(fromMs: Long, toMs: Long): List<Dream> =
        withContext(Dispatchers.IO) {
            vault.requireSession().listDreamsBetween(fromMs, toMs)
        }

    suspend fun get(id: Long): Dream? =
        withContext(Dispatchers.IO) { vault.requireSession().getDream(id) }

    suspend fun add(
        nightMs: Long,
        title: String,
        body: String,
        lucid: Boolean,
    ): Dream = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        vault.requireSession().addDream(
            NewDream(
                nightMs = nightMs,
                title = title,
                body = body,
                lucid = lucid,
                createdMs = now,
                updatedMs = now,
            )
        )
    }

    /**
     * In-place update, which is what keeps the id stable — and the id is what
     * the tags and the voice notes hang off. The journal's delete-then-add
     * idiom would silently take both with it.
     */
    suspend fun update(
        id: Long,
        nightMs: Long,
        title: String,
        body: String,
        lucid: Boolean,
    ): Dream = withContext(Dispatchers.IO) {
        vault.requireSession().updateDream(
            id, nightMs, title, body, lucid, System.currentTimeMillis(),
        )
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        // Read the paths before the row goes: the cascade drops the audio rows,
        // and after that nothing knows which files to wipe.
        val paths = runCatching { vault.requireSession().dreamAudio(id).map { it.filePath } }
            .getOrDefault(emptyList())
        vault.requireSession().deleteDream(id)
        paths.forEach { runCatching { wipe(File(it)) } }
    }

    // -- Tags ----------------------------------------------------------------

    suspend fun tags(): List<DreamTag> =
        withContext(Dispatchers.IO) { vault.requireSession().listDreamTags() }

    suspend fun tagsFor(dreamId: Long): List<DreamTag> =
        withContext(Dispatchers.IO) { vault.requireSession().tagsForDream(dreamId) }

    /** Get-or-create: typing a tag you already have lands on it. */
    suspend fun addTag(label: String, color: Long? = null): DreamTag =
        withContext(Dispatchers.IO) {
            vault.requireSession().addDreamTag(label, color, System.currentTimeMillis())
        }

    suspend fun renameTag(id: Long, label: String) =
        withContext(Dispatchers.IO) { vault.requireSession().renameDreamTag(id, label) }

    suspend fun deleteTag(id: Long) =
        withContext(Dispatchers.IO) { vault.requireSession().deleteDreamTag(id) }

    suspend fun tag(dreamId: Long, tagId: Long) =
        withContext(Dispatchers.IO) { vault.requireSession().tagDream(dreamId, tagId) }

    suspend fun untag(dreamId: Long, tagId: Long) =
        withContext(Dispatchers.IO) { vault.requireSession().untagDream(dreamId, tagId) }

    // -- Voice notes ---------------------------------------------------------

    suspend fun audioFor(dreamId: Long): List<DreamAudio> =
        withContext(Dispatchers.IO) { vault.requireSession().dreamAudio(dreamId) }

    fun startRecording(): Boolean {
        if (recorder != null) return false
        val tmp = File(context.cacheDir, "dream-rec-${UUID.randomUUID()}.m4a")
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        return runCatching {
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioEncodingBitRate(96_000)
            rec.setAudioSamplingRate(44_100)
            rec.setOutputFile(tmp.absolutePath)
            rec.prepare()
            rec.start()
            recorder = rec
            recordingTmp = tmp
            recordingStartedAtMs = System.currentTimeMillis()
            true
        }.getOrElse {
            runCatching { rec.release() }
            wipe(tmp)
            false
        }
    }

    /**
     * Stop, encrypt, and attach to [dreamId].
     *
     * Returns the plaintext temp file alongside the row so the caller can hand
     * it straight to the transcriber: transcription needs audio a recogniser
     * can open, and decrypting the blob again just to re-derive what we already
     * have in hand would write a second plaintext copy to disk. The caller
     * wipes it — see [wipe] — the moment it is done.
     */
    suspend fun stopRecording(dreamId: Long): Pair<DreamAudio, File>? =
        withContext(Dispatchers.IO) {
            val rec = recorder ?: return@withContext null
            val tmp = recordingTmp ?: return@withContext null
            val startedAt = recordingStartedAtMs
            recorder = null
            recordingTmp = null
            runCatching { rec.stop() }
            runCatching { rec.release() }

            val durationMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
            val bytes = runCatching { tmp.readBytes() }.getOrNull()
            if (bytes == null || bytes.isEmpty()) {
                wipe(tmp)
                return@withContext null
            }

            val session = vault.requireSession()
            val ciphertext = session.encryptBlob(bytes)
            val final = File(audioDir, "${UUID.randomUUID()}.bin")
            FileOutputStream(final).use { fos ->
                fos.write(ciphertext)
                fos.fd.sync()
            }
            val row = session.addDreamAudio(
                NewDreamAudio(
                    dreamId = dreamId,
                    filePath = final.absolutePath,
                    durationMs = durationMs,
                    transcript = null,
                    createdMs = startedAt,
                )
            )
            row to tmp
        }

    suspend fun cancelRecording() = withContext(Dispatchers.IO) {
        val rec = recorder ?: return@withContext
        val tmp = recordingTmp
        recorder = null
        recordingTmp = null
        runCatching { rec.stop() }
        runCatching { rec.release() }
        tmp?.let { wipe(it) }
    }

    /**
     * Play a voice note, decrypting it to a temp file first.
     *
     * Playback is not a nice-to-have here. Transcription is on-device only and
     * a good number of phones cannot do it at all — on those, listening is the
     * *only* way to get a dream back out of the app. A recording that can be
     * made and never heard is worse than no recording.
     *
     * The plaintext copy is wiped by [stopPlayback], including from the
     * completion listener, so it lives exactly as long as the sound does.
     */
    suspend fun play(audio: DreamAudio, onComplete: () -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            stopPlayback()
            val plain = runCatching {
                vault.requireSession().decryptBlob(File(audio.filePath).readBytes())
            }.getOrNull() ?: return@withContext false
            val tmp = File(cacheDir, "play-${UUID.randomUUID()}.m4a")
            runCatching { tmp.writeBytes(plain) }.getOrElse { return@withContext false }
            val mp = MediaPlayer()
            try {
                mp.setDataSource(tmp.absolutePath)
                mp.prepare()
                mp.setOnCompletionListener {
                    stopPlayback()
                    onComplete()
                }
                mp.start()
                player = mp
                playingTmp = tmp
                true
            } catch (t: Throwable) {
                mp.release()
                wipe(tmp)
                false
            }
        }

    fun stopPlayback() {
        val mp = player
        val tmp = playingTmp
        player = null
        playingTmp = null
        runCatching { mp?.stop() }
        runCatching { mp?.release() }
        tmp?.let { wipe(it) }
    }

    /** Decrypt to a cache file for playback. Purged when the app backgrounds. */
    suspend fun decryptToCache(audio: DreamAudio): File = withContext(Dispatchers.IO) {
        val out = File(cacheDir, File(audio.filePath).nameWithoutExtension + ".m4a")
        if (!out.exists()) {
            val plain = vault.requireSession().decryptBlob(File(audio.filePath).readBytes())
            FileOutputStream(out).use { it.write(plain) }
        }
        out
    }

    suspend fun setTranscript(audioId: Long, transcript: String?) =
        withContext(Dispatchers.IO) {
            vault.requireSession().setDreamTranscript(audioId, transcript)
        }

    suspend fun deleteAudio(audio: DreamAudio) = withContext(Dispatchers.IO) {
        vault.requireSession().deleteDreamAudio(audio.id)
        wipe(File(audio.filePath))
    }

    /** Wipe decrypted copies. Called from the app's background purge. */
    fun purgeAllCache() {
        // Stop first: the purge would otherwise delete the file out from under
        // a playing MediaPlayer, and a dream would keep sounding from a phone
        // whose owner has already put the app away.
        stopPlayback()
        runCatching { cacheDir.listFiles()?.forEach { wipe(it) } }
    }

    /**
     * Delete ciphertext whose row is gone.
     *
     * A dream deleted while the vault was open takes its files with it, but a
     * cascade from anywhere else — a restored backup, a crash between the row
     * delete and the file delete — leaves the `.bin` behind with nothing
     * pointing at it. Those are dream recordings; they do not get to outlive
     * the entry indefinitely.
     */
    suspend fun cleanupOrphans() = withContext(Dispatchers.IO) {
        val known = runCatching { vault.requireSession().allDreamAudioPaths() }
            .getOrNull()?.map { File(it).name }?.toSet() ?: return@withContext
        audioDir.listFiles()?.forEach { f ->
            if (f.name !in known) wipe(f)
        }
    }

    /** Overwrite before unlinking, so the bytes are not merely dereferenced. */
    private fun wipe(f: File) {
        runCatching {
            if (f.exists()) {
                FileOutputStream(f).use { fos ->
                    fos.write(ByteArray(f.length().toInt().coerceAtMost(1 shl 20)))
                    fos.fd.sync()
                }
            }
        }
        runCatching { f.delete() }
    }

    companion object {
        /**
         * The night an instant belongs to, as local midnight.
         *
         * A dream recalled at 03:00 belongs to the night that started the
         * previous evening, not to the day that just began — so anything before
         * [NIGHT_ROLLOVER] counts back one day. Without it, waking at 02:00 to
         * scribble and finishing the entry at 09:00 would file one night under
         * two dates, and every timeline drawn from it would show two.
         */
        fun nightOf(atMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
            val at = Instant.ofEpochMilli(atMs).atZone(zone)
            val day = if (at.toLocalTime() < NIGHT_ROLLOVER) {
                at.toLocalDate().minusDays(1)
            } else {
                at.toLocalDate()
            }
            return day.atStartOfDay(zone).toInstant().toEpochMilli()
        }

        /** Local midnight of [date] — used when the user picks a night by hand. */
        fun nightOfDate(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Long =
            date.atStartOfDay(zone).toInstant().toEpochMilli()

        /**
         * Before this hour, an instant still belongs to the previous night.
         * Noon rather than a small-hours cutoff: an entry written at 11:00 is a
         * morning recall of the night before, and one written at 13:00 is
         * almost certainly a nap or a late catch-up on the same night.
         */
        private val NIGHT_ROLLOVER: LocalTime = LocalTime.NOON
    }
}
