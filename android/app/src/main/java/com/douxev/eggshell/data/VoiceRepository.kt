package com.douxev.eggshell.data

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.douxev.eggshell.data.voice.AudioDecoder
import com.douxev.eggshell.data.voice.PitchDetector
import uniffi.transition.NewVoiceClip
import uniffi.transition.VoiceClip

/**
 * Encrypted voice-clip storage + recorder/player wrapping MediaRecorder and
 * MediaPlayer.
 *
 * Storage:
 *   - Audio bytes are AES-GCM encrypted under an HKDF-derived sub-key (see
 *     [VaultRepository.encryptBlob]) and written to `voice/<uuid>.bin`.
 *   - Metadata (id, timestamps, duration, pitch_hz) lives in the encrypted
 *     SQLCipher vault (table `voice_clips`, migration 0007). Previously this
 *     was in plain SharedPreferences — a passive observer could read the
 *     pattern of voice activity + estimated F0 without the master key.
 *     [migrateLegacyMetadataIfNeeded] copies the old prefs into the vault
 *     on first unlock after upgrade and wipes the SharedPreferences.
 *
 * Recording flow:
 *   start() opens a MediaRecorder writing to a plaintext temp file in the
 *   cache dir. stop() reads the temp bytes, runs YIN pitch detection on
 *   them, encrypts via the Vault, writes the ciphertext to disk, persists
 *   the metadata row, then overwrites the plaintext temp with zeros before
 *   deletion (best-effort wipe — flash storage may have remapped pages, so
 *   this is defence-in-depth, not a guarantee).
 *
 * Playback flow:
 *   play(entry) decrypts to a temp plaintext file in cache, hands it to
 *   MediaPlayer. Callers should call stopPlayback() when done. We wipe the
 *   plaintext on stop, and purge stale entries (>10 min) on every play.
 */
@Singleton
class VoiceRepository @Inject constructor(
    private val vault: VaultRepository,
    @ApplicationContext private val context: Context,
) {
    private val voiceDir: File by lazy {
        File(context.filesDir, "voice").apply { mkdirs() }
    }
    private val cacheDir: File by lazy {
        File(context.cacheDir, "voice_tmp").apply { mkdirs() }
    }
    private val shareCacheDir: File by lazy {
        File(context.cacheDir, "voice_share").apply { mkdirs() }
    }
    private val legacyPrefs = VoiceClipPrefs(context)

    private var recorder: MediaRecorder? = null
    private var recordingTmp: File? = null
    private var recordingStartedAtMs: Long = 0L

    private var player: MediaPlayer? = null
    private var playingTmp: File? = null

    suspend fun list(): List<VoiceClip> = withContext(Dispatchers.IO) {
        vault.requireSession().listVoiceClips(offset = 0, limit = 1000)
    }

    /** Returns false if a recording is already in progress. */
    fun startRecording(): Boolean {
        if (recorder != null) return false
        val tmp = File(cacheDir, "rec-${UUID.randomUUID()}.m4a")
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        // VOICE_RECOGNITION enables the OEM's built-in noise suppression /
        // automatic gain control / echo cancellation when present (Pixel,
        // Samsung, etc. all ship a tuned voice-processing graph for it).
        rec.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
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
        return true
    }

    /** Stops recording, encrypts the captured audio, persists metadata. */
    suspend fun stopRecording(): VoiceClip? = withContext(Dispatchers.IO) {
        val rec = recorder ?: return@withContext null
        val tmp = recordingTmp ?: return@withContext null
        val startedAt = recordingStartedAtMs
        recorder = null
        recordingTmp = null
        try {
            runCatching { rec.stop() }
            runCatching { rec.release() }
        } catch (_: Throwable) { /* ignore — already torn down */ }

        val durationMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
        val bytes = runCatching { tmp.readBytes() }.getOrNull() ?: run {
            wipeFile(tmp); return@withContext null
        }
        if (bytes.isEmpty()) {
            wipeFile(tmp); return@withContext null
        }

        // Run YIN pitch detection on the plaintext m4a before we wipe it.
        // Failures here are non-fatal — we still save the clip, just without
        // an F0 estimate, and the UI shows "—".
        val pitchHz = runCatching {
            AudioDecoder.decodeToMonoFloats(tmp)?.let { decoded ->
                PitchDetector.estimateMedianHz(decoded.samples, decoded.sampleRate)
            }?.toInt()
        }.getOrNull()

        // Best-effort overwrite-then-delete of the plaintext temp.
        wipeFile(tmp)

        val session = vault.requireSession()
        val ciphertext = session.encryptBlob(bytes)
        val id = UUID.randomUUID().toString()
        val final = File(voiceDir, "$id.bin")
        FileOutputStream(final).use { fos ->
            fos.write(ciphertext)
            fos.fd.sync()
        }
        session.addVoiceClip(
            NewVoiceClip(
                id = id,
                atMs = startedAt,
                durationMs = durationMs,
                filePath = final.absolutePath,
                pitchHz = pitchHz,
            )
        )
    }

    /**
     * Cancel an in-flight recording. Used to be on the UI thread, which can
     * ANR if MediaRecorder.stop blocks on the audio HAL (Samsung Knox /
     * device-policy hooks have been seen to block for >500 ms here).
     */
    suspend fun cancelRecording() = withContext(Dispatchers.IO) {
        val rec = recorder ?: return@withContext
        val tmp = recordingTmp
        recorder = null
        recordingTmp = null
        runCatching { rec.stop() }
        runCatching { rec.release() }
        tmp?.let { wipeFile(it) }
    }

    /** Decrypts to a temp file and starts playback. Returns true on success. */
    suspend fun play(entry: VoiceClip, onComplete: () -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            stopPlayback()
            val ciphertext = File(entry.filePath).readBytes()
            val plain = vault.requireSession().decryptBlob(ciphertext)
            val tmp = File(cacheDir, "play-${UUID.randomUUID()}.m4a")
            tmp.writeBytes(plain)
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
                wipeFile(tmp)
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
        tmp?.let { wipeFile(it) }
    }

    suspend fun delete(entry: VoiceClip) = withContext(Dispatchers.IO) {
        runCatching { File(entry.filePath).delete() }
        vault.requireSession().deleteVoiceClip(entry.id)
    }

    /**
     * Decrypts the clip to the FileProvider-shared sub-cache and returns it
     * for an ACTION_SEND intent. We purge entries older than 10 minutes on
     * each call and wipe everything on lock via [purgeAllCache].
     */
    suspend fun decryptToCache(entry: VoiceClip): File = withContext(Dispatchers.IO) {
        purgeStaleShareCache()
        val ciphertext = File(entry.filePath).readBytes()
        val plain = vault.requireSession().decryptBlob(ciphertext)
        val out = File(shareCacheDir, "share-${entry.id}-${System.currentTimeMillis()}.m4a")
        out.writeBytes(plain)
        out.deleteOnExit()
        out
    }

    /**
     * Wipe every plaintext audio file in voice_tmp + voice_share. Called on
     * lock so decrypted clips don't linger.
     */
    fun purgeAllCache() {
        runCatching {
            cacheDir.listFiles()?.forEach { wipeFile(it) }
            shareCacheDir.listFiles()?.forEach { wipeFile(it) }
        }
    }

    /**
     * Compare on-disk voice ciphertext files against the DB's
     * `voice_clips.file_path` set; delete anything not tracked. Also clears
     * stale share-cache. Call at unlock.
     */
    suspend fun cleanupOrphans() = withContext(Dispatchers.IO) {
        val session = vault.requireSession()
        val tracked = HashSet<String>()
        var offset = 0L
        val pageSize = 500L
        while (true) {
            val page = session.listVoiceClips(offset, pageSize)
            if (page.isEmpty()) break
            page.forEach { tracked.add(File(it.filePath).name) }
            if (page.size < pageSize.toInt()) break
            offset += pageSize
        }
        voiceDir.listFiles()?.forEach { f ->
            if (f.name.endsWith(".bin") && f.name !in tracked) {
                f.delete()
            }
        }
        purgeStaleShareCache()
    }

    /**
     * One-shot copy of legacy VoiceClipPrefs entries into the vault DB. Run
     * once at unlock; flips a flag in the legacy prefs to mark migration
     * complete, then clears the prefs entirely.
     */
    suspend fun migrateLegacyMetadataIfNeeded() = withContext(Dispatchers.IO) {
        if (!legacyPrefs.hasAny()) return@withContext
        val session = vault.requireSession()
        legacyPrefs.all().forEach { e ->
            runCatching {
                session.addVoiceClip(
                    NewVoiceClip(
                        id = e.id,
                        atMs = e.atMs,
                        durationMs = e.durationMs,
                        filePath = e.filePath,
                        pitchHz = e.pitchHz,
                    )
                )
            }
        }
        legacyPrefs.wipe()
    }

    private fun purgeStaleShareCache() {
        val cutoff = System.currentTimeMillis() - STALE_CACHE_MS
        runCatching {
            shareCacheDir.listFiles()?.forEach { f ->
                if (f.lastModified() < cutoff) wipeFile(f)
            }
        }
    }

    /**
     * Overwrite the file with zeros then delete it. On modern flash storage
     * this is only a best-effort wipe (the FTL may have remapped pages), but
     * it does deny anyone reading the file through Android's normal APIs.
     */
    private fun wipeFile(f: File) {
        runCatching {
            if (f.exists()) {
                val len = f.length()
                if (len > 0) {
                    FileOutputStream(f, false).use { fos ->
                        val zeros = ByteArray(8 * 1024)
                        var remaining = len
                        while (remaining > 0) {
                            val n = minOf(zeros.size.toLong(), remaining).toInt()
                            fos.write(zeros, 0, n)
                            remaining -= n
                        }
                        fos.fd.sync()
                    }
                }
            }
        }
        runCatching { f.delete() }
    }

    companion object {
        private const val STALE_CACHE_MS = 10L * 60L * 1000L
    }
}
