package com.douxev.eggshell.data.dreams

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.ModelDownloadListener
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import androidx.annotation.RequiresApi
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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
 *    which since API 33 this class can both ask about ([languageSupport]) and
 *    perform ([downloadLanguage]) rather than sending the user hunting through
 *    system settings;
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

    /** What this phone can do about one particular language. */
    sealed interface LanguageSupport {
        /** Ready now. */
        data object Installed : LanguageSupport
        /** Not here yet, but the phone can fetch it — see [downloadLanguage]. */
        data object Downloadable : LanguageSupport
        /** The recogniser does not offer this language offline at all. */
        data object Unsupported : LanguageSupport
        /** Below API 33 there is no way to ask; let [transcribe] find out. */
        data object Unknown : LanguageSupport
    }

    /**
     * Which of [LanguageSupport] applies to [languageTag] on this phone.
     *
     * The synchronous [availability] check can only answer "is there a
     * recogniser at all". It cannot tell "there is one, but it has never been
     * given French" apart from "it has French, ready to go" — and those two
     * want opposite things from the UI: one is a button, the other is nothing.
     *
     * Main-thread only, like everything on SpeechRecognizer, which throws
     * outright if called from anywhere else.
     */
    suspend fun languageSupport(languageTag: String): LanguageSupport {
        if (availability() != null) return LanguageSupport.Unsupported
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return LanguageSupport.Unknown
        }
        return withContext(Dispatchers.Main) {
            withTimeoutOrNull(SUPPORT_TIMEOUT_MS) { querySupport(languageTag) }
                ?: LanguageSupport.Unknown
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun querySupport(languageTag: String): LanguageSupport =
        suspendCancellableCoroutine { cont ->
            val recognizer = runCatching {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            }.getOrNull() ?: run {
                cont.resume(LanguageSupport.Unsupported)
                return@suspendCancellableCoroutine
            }

            fun finish(r: LanguageSupport) {
                if (cont.isActive) cont.resume(r)
                runCatching { recognizer.destroy() }
            }

            runCatching {
                recognizer.checkRecognitionSupport(
                    supportIntent(languageTag),
                    ContextCompat.getMainExecutor(context),
                    object : RecognitionSupportCallback {
                        override fun onSupportResult(support: RecognitionSupport) {
                            finish(
                                when {
                                    // Already downloading counts as installed:
                                    // offering the button again would start a
                                    // second download of the same model.
                                    support.installedOnDeviceLanguages
                                        .plus(support.pendingOnDeviceLanguages)
                                        .any { it matches languageTag } ->
                                        LanguageSupport.Installed
                                    support.supportedOnDeviceLanguages
                                        .any { it matches languageTag } ->
                                        LanguageSupport.Downloadable
                                    // Not listed anywhere. Do not claim
                                    // Unsupported: some recognisers report an
                                    // empty catalogue and still work.
                                    else -> LanguageSupport.Unknown
                                }
                            )
                        }

                        override fun onError(error: Int) = finish(LanguageSupport.Unknown)
                    },
                )
            }.onFailure { finish(LanguageSupport.Unknown) }

            cont.invokeOnCancellation { runCatching { recognizer.destroy() } }
        }

    /**
     * Ask the system to fetch the offline model for [languageTag]. Returns true
     * once it is installed.
     *
     * **This does not weaken the promise the class is built around.** What
     * travels is a language model, from the system's recognition service to
     * this phone. No recording is involved and none is uploaded — the download
     * is what makes transcription possible *without* a network round trip per
     * dream. The UI has to say that plainly, because "downloads something" and
     * "sends my dream somewhere" are easy to read as the same sentence.
     *
     * API 34 reports completion. API 33 can only fire and forget, so there the
     * answer is false and the user re-checks — better than a spinner that
     * cannot end.
     */
    suspend fun downloadLanguage(languageTag: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return withContext(Dispatchers.Main) {
            withTimeoutOrNull(DOWNLOAD_TIMEOUT_MS) { runDownload(languageTag) } ?: false
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun runDownload(languageTag: String): Boolean =
        suspendCancellableCoroutine { cont ->
            val recognizer = runCatching {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            }.getOrNull() ?: run {
                cont.resume(false)
                return@suspendCancellableCoroutine
            }

            fun finish(ok: Boolean) {
                if (cont.isActive) cont.resume(ok)
                runCatching { recognizer.destroy() }
            }

            val intent = supportIntent(languageTag)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                runCatching {
                    recognizer.triggerModelDownload(
                        intent,
                        ContextCompat.getMainExecutor(context),
                        object : ModelDownloadListener {
                            override fun onProgress(completedPercent: Int) = Unit
                            override fun onSuccess() = finish(true)
                            // Scheduled for later — Wi-Fi, charging, whatever
                            // the system decides. Not a failure, but not done
                            // either, so say so rather than claim success.
                            override fun onScheduled() = finish(false)
                            override fun onError(error: Int) = finish(false)
                        },
                    )
                }.onFailure { finish(false) }
            } else {
                // API 33: no listener overload. Fire it and let the user
                // re-check; a spinner with no completion signal is worse.
                runCatching { recognizer.triggerModelDownload(intent) }
                finish(false)
            }

            cont.invokeOnCancellation { runCatching { recognizer.destroy() } }
        }

    private fun supportIntent(languageTag: String) =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }

    /**
     * Lenient tag match: a phone with "fr-FR" installed can transcribe a user
     * whose locale is "fr-CA". Requiring an exact match would report French as
     * missing on a French-Canadian phone and offer to download what is already
     * there.
     */
    private infix fun String.matches(tag: String): Boolean =
        equals(tag, ignoreCase = true) ||
            substringBefore('-').equals(tag.substringBefore('-'), ignoreCase = true)

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
        const val SUPPORT_TIMEOUT_MS = 5_000L
        // Models run to tens of megabytes and the system may hold the download
        // for Wi-Fi; this bounds the spinner, not the download itself.
        const val DOWNLOAD_TIMEOUT_MS = 300_000L

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
