package com.douxev.eggshell.data.dreams

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Speech-to-text for dream voice notes, **on the device and nowhere else**.
 *
 * `SpeechRecognizer.createSpeechRecognizer` hands the audio to whichever
 * recognition service the system nominates, which on a stock phone is Google's
 * and is a network call. For this app that is not a trade-off, it is a
 * contradiction: the vault is encrypted, the reminders are deliberately
 * generic, the widgets refuse to render vault data at all — and a dream is the
 * most revealing thing any of it holds. Sending one to a third party to have it
 * typed out would undo all of that in a single tap.
 *
 * So this uses [SpeechRecognizer.createOnDeviceSpeechRecognizer] only, which
 * Android guarantees never leaves the device. That has a real cost and the UI
 * has to state it rather than hide it:
 *
 *  - it needs **API 31+**;
 *  - it needs the language model for the chosen locale to be **downloaded**,
 *    which is a system setting we can prompt for but not perform;
 *  - it is generally less accurate than the server model.
 *
 * When it cannot run, the answer is [Result.Unavailable] and the encrypted
 * audio simply keeps no transcript. Falling back to the network would be the
 * one behaviour this class exists to prevent.
 */
@Singleton
class OnDeviceTranscriber @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    sealed interface Result {
        data class Text(val transcript: String) : Result
        /** No on-device recogniser here — [reason] is shown to the user. */
        data class Unavailable(val reason: Reason) : Result
        data object NoSpeech : Result
        data class Failed(val code: Int) : Result
    }

    enum class Reason {
        /** createOnDeviceSpeechRecognizer arrived in API 31. */
        ApiTooOld,
        /** The device reports no on-device recognition service at all. */
        NoRecognizer,
        /** The recogniser is there but has no model for this language yet. */
        LanguageNotDownloaded,
    }

    /** Cheap enough to call while composing a row. */
    fun availability(): Reason? = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> Reason.ApiTooOld
        !SpeechRecognizer.isOnDeviceRecognitionAvailable(context) -> Reason.NoRecognizer
        else -> null
    }

    /**
     * Transcribe [audio], a **plaintext** m4a the caller is responsible for
     * wiping afterwards.
     *
     * The recogniser cannot read our ciphertext, so a decrypted copy has to
     * exist for the length of this call. That is why the caller passes the file
     * it already had in hand from recording rather than us decrypting a second
     * one: fewer plaintext copies, and a shorter window.
     */
    suspend fun transcribe(audio: File, languageTag: String): Result {
        availability()?.let { return Result.Unavailable(it) }
        // Repeated inline even though availability() just checked it: lint
        // cannot see a version guard through a helper that returns a reason,
        // and the API-31 call below is exactly the kind it should be strict
        // about. Costs one comparison.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return Result.Unavailable(Reason.ApiTooOld)
        }

        // Guarded so the whole call can be awaited with a deadline: a recogniser
        // that never calls back would otherwise leave the UI spinning and the
        // plaintext copy on disk.
        return withTimeoutOrNull(TIMEOUT_MS) { runRecognizer(audio, languageTag) }
            ?: Result.Failed(SpeechRecognizer.ERROR_SERVER_DISCONNECTED)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private suspend fun runRecognizer(audio: File, languageTag: String): Result =
        suspendCancellableCoroutine { cont ->
            val recognizer = runCatching {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            }.getOrNull() ?: run {
                cont.resume(Result.Unavailable(Reason.NoRecognizer))
                return@suspendCancellableCoroutine
            }

            fun finish(r: Result) {
                if (cont.isActive) cont.resume(r)
                runCatching { recognizer.destroy() }
            }

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()
                    finish(if (text.isEmpty()) Result.NoSpeech else Result.Text(text))
                }

                override fun onError(error: Int) {
                    finish(
                        when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH,
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> Result.NoSpeech
                            // The one error worth naming: the model for this
                            // language has not been downloaded, which the user
                            // can fix in system settings.
                            ERROR_LANGUAGE_UNAVAILABLE, ERROR_LANGUAGE_NOT_SUPPORTED ->
                                Result.Unavailable(Reason.LanguageNotDownloaded)
                            else -> Result.Failed(error)
                        }
                    )
                }

                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                // Belt and braces. createOnDeviceSpeechRecognizer already
                // guarantees this, but the flag costs nothing and makes the
                // intent state the requirement out loud for anyone reading it.
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(EXTRA_AUDIO_SOURCE, Uri.fromFile(audio))
                putExtra(EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                putExtra(EXTRA_AUDIO_SOURCE_ENCODING, ENCODING_PCM_16BIT)
                putExtra(EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 44_100)
            }

            cont.invokeOnCancellation { runCatching { recognizer.destroy() } }
            runCatching { recognizer.startListening(intent) }
                .onFailure { finish(Result.Failed(SpeechRecognizer.ERROR_CLIENT)) }
        }

    private companion object {
        const val TIMEOUT_MS = 90_000L

        // Constants that exist on the platform but are not in the compile SDK's
        // public surface for every level we build against; spelled out rather
        // than referenced so the build does not depend on which one that is.
        const val EXTRA_AUDIO_SOURCE = "android.speech.extra.AUDIO_SOURCE"
        const val EXTRA_AUDIO_SOURCE_CHANNEL_COUNT =
            "android.speech.extra.AUDIO_SOURCE_CHANNEL_COUNT"
        const val EXTRA_AUDIO_SOURCE_ENCODING = "android.speech.extra.AUDIO_SOURCE_ENCODING"
        const val EXTRA_AUDIO_SOURCE_SAMPLING_RATE =
            "android.speech.extra.AUDIO_SOURCE_SAMPLING_RATE"
        const val ENCODING_PCM_16BIT = 2

        const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        const val ERROR_LANGUAGE_UNAVAILABLE = 13
    }
}
