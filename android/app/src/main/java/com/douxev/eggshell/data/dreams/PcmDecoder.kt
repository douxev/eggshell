package com.douxev.eggshell.data.dreams

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File

/**
 * Decodes a recorded voice note to raw PCM so a recogniser can actually read it.
 *
 * Voice notes are stored as AAC in an MPEG-4 container, which is the right call
 * for storage — a minute of dream costs a few hundred kilobytes instead of a
 * few megabytes, and every one of them is encrypted at rest.
 *
 * `RecognizerIntent.EXTRA_AUDIO_SOURCE` cannot read that. It takes an already
 * opened descriptor of **raw PCM**, described by the three companion extras;
 * there is no field in which to say "this is AAC in an MP4 box". Handing it the
 * m4a and declaring `ENCODING_PCM_16BIT` means the recogniser reads container
 * headers as sample values, so the transcript is of noise.
 *
 * So the file is decoded on the way to the recogniser, never on the way to
 * disk. The PCM is a temporary, plaintext copy and the caller wipes it — see
 * [OnDeviceTranscriber.transcribe], which does so in a `finally`.
 */
internal object PcmDecoder {

    /** What [decode] produced, described the way the recogniser needs it. */
    data class Pcm(val file: File, val sampleRate: Int, val channelCount: Int)

    private const val TIMEOUT_US = 10_000L

    /**
     * Decode [src] into [dst] as 16-bit little-endian PCM.
     *
     * Returns null rather than throwing: a voice note that cannot be decoded is
     * a transcript that does not happen, which the UI already handles. It is
     * never a reason to lose the recording.
     */
    fun decode(src: File, dst: File): Pcm? = runCatching {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(src.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(track)

            val inputFormat = extractor.getTrackFormat(track)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return null
            var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(inputFormat, null, null, 0)
                codec.start()

                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false

                dst.outputStream().buffered().use { out ->
                    while (!outputDone) {
                        if (!inputDone) {
                            val index = codec.dequeueInputBuffer(TIMEOUT_US)
                            if (index >= 0) {
                                val buffer = codec.getInputBuffer(index)
                                val size = buffer
                                    ?.let { it.clear(); extractor.readSampleData(it, 0) }
                                    ?: -1
                                if (size < 0) {
                                    codec.queueInputBuffer(
                                        index, 0, 0, 0,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                    )
                                    inputDone = true
                                } else {
                                    codec.queueInputBuffer(
                                        index, 0, size, extractor.sampleTime, 0,
                                    )
                                    extractor.advance()
                                }
                            }
                        }

                        when (val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                // The decoder is the authority on what it is
                                // emitting, not the container: it may resample
                                // or downmix. Reading the rate from the input
                                // format would mis-describe the bytes and the
                                // recogniser would hear the wrong pitch.
                                val actual = codec.outputFormat
                                sampleRate = actual.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                                channels = actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            }

                            in 0..Int.MAX_VALUE -> {
                                codec.getOutputBuffer(index)?.let { buffer ->
                                    if (info.size > 0) {
                                        val chunk = ByteArray(info.size)
                                        buffer.position(info.offset)
                                        buffer.get(chunk)
                                        out.write(chunk)
                                    }
                                }
                                codec.releaseOutputBuffer(index, false)
                                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                    outputDone = true
                                }
                            }

                            else -> Unit // TRY_AGAIN_LATER / deprecated buffer change
                        }
                    }
                }
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }

            Pcm(dst, sampleRate, channels).takeIf { dst.length() > 0 }
        } finally {
            extractor.release()
        }
    }.getOrNull()
}
