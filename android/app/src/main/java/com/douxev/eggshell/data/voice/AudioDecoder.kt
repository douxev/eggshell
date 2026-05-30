package com.douxev.eggshell.data.voice

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Lightweight M4A → mono float-PCM decoder built on MediaExtractor +
 * MediaCodec. We only need the bare-minimum decode path: find the audio
 * track, pump compressed AAC frames in, drain decoded PCM out, downmix to
 * mono, normalise to ±1.0.
 *
 * The output is a single FloatArray suitable for [PitchDetector]. We don't
 * stream because clip lengths are short (seconds) and the buffer comfortably
 * fits in memory — a 30 s clip at 44.1 kHz mono is ~5 MB, well within
 * normal app limits.
 */
object AudioDecoder {

    data class Decoded(val samples: FloatArray, val sampleRate: Int)

    /** Decodes an audio file to mono floats. Returns null on any failure. */
    fun decodeToMonoFloats(file: File): Decoded? = runCatching { decode(file) }.getOrNull()

    private fun decode(file: File): Decoded? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
        } catch (t: Throwable) {
            extractor.release()
            return null
        }

        val audioTrackIndex = (0 until extractor.trackCount).firstOrNull { i ->
            val fmt = extractor.getTrackFormat(i)
            fmt.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: run {
            extractor.release(); return null
        }

        extractor.selectTrack(audioTrackIndex)
        val inputFormat = extractor.getTrackFormat(audioTrackIndex)
        val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: run {
            extractor.release(); return null
        }
        val sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channelCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(inputFormat, null, null, 0)
        codec.start()

        val bufferInfo = MediaCodec.BufferInfo()
        val pcmShorts = ArrayList<Short>(sampleRate * 4) // pre-grow ~ 4 s of audio
        var sawInputEos = false
        var sawOutputEos = false
        val timeoutUs = 10_000L

        try {
            while (!sawOutputEos) {
                if (!sawInputEos) {
                    val inIdx = codec.dequeueInputBuffer(timeoutUs)
                    if (inIdx >= 0) {
                        val inBuf: ByteBuffer = codec.getInputBuffer(inIdx) ?: continue
                        val size = extractor.readSampleData(inBuf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inIdx, 0, 0, 0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            sawInputEos = true
                        } else {
                            val pts = extractor.sampleTime
                            codec.queueInputBuffer(inIdx, 0, size, pts, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
                if (outIdx >= 0) {
                    val outBuf: ByteBuffer = codec.getOutputBuffer(outIdx) ?: continue
                    if (bufferInfo.size > 0) {
                        outBuf.position(bufferInfo.offset)
                        outBuf.limit(bufferInfo.offset + bufferInfo.size)
                        outBuf.order(ByteOrder.LITTLE_ENDIAN)
                        val sb = outBuf.asShortBuffer()
                        while (sb.hasRemaining()) pcmShorts.add(sb.get())
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        sawOutputEos = true
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { extractor.release() }
        }

        if (pcmShorts.isEmpty()) return null

        // Downmix to mono + convert to floats in [-1.0, 1.0].
        val mono = if (channelCount <= 1) {
            FloatArray(pcmShorts.size) { i -> pcmShorts[i].toFloat() / 32768f }
        } else {
            val perFrame = channelCount
            val frames = pcmShorts.size / perFrame
            FloatArray(frames) { i ->
                var sum = 0f
                for (c in 0 until perFrame) {
                    sum += pcmShorts[i * perFrame + c].toFloat()
                }
                (sum / perFrame) / 32768f
            }
        }
        return Decoded(samples = mono, sampleRate = sampleRate)
    }
}
