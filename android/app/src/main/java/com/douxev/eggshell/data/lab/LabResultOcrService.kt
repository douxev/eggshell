package com.douxev.eggshell.data.lab

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Coroutine-friendly wrapper around ML Kit on-device text recognition that
 * handles both image and PDF inputs:
 *   - image MIME URIs go straight through ML Kit's InputImage.fromFilePath.
 *   - application/pdf URIs are rasterised page-by-page with the platform's
 *     [PdfRenderer], each page bitmap is fed to ML Kit, and the per-page
 *     text is concatenated into a single result. Most French / European
 *     lab reports ship as PDFs, so this path is the common one.
 *
 * Everything runs on-device — the model is bundled in the APK
 * (com.google.mlkit:text-recognition, not the GMS variant). No network
 * call, no GMS dependency, no first-use download.
 */
@Singleton
class LabResultOcrService @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun recognize(uri: Uri): String {
        val mime = context.contentResolver.getType(uri)
        return when {
            mime == "application/pdf" -> recognizePdf(uri)
            mime != null && mime.startsWith("image/") -> recognizeImage(uri)
            // Fall back to inspecting the URI's extension if the resolver
            // doesn't know the MIME (rare, but happens with some file
            // providers).
            uri.path?.endsWith(".pdf", ignoreCase = true) == true -> recognizePdf(uri)
            else -> recognizeImage(uri)
        }
    }

    private suspend fun recognizeImage(uri: Uri): String = suspendCancellableCoroutine { cont ->
        val image = try {
            InputImage.fromFilePath(context, uri)
        } catch (t: Throwable) {
            cont.resumeWithException(t)
            return@suspendCancellableCoroutine
        }
        recognizer.process(image)
            .addOnSuccessListener { result -> if (cont.isActive) cont.resume(result.text) }
            .addOnFailureListener { e -> if (cont.isActive) cont.resumeWithException(e) }
    }

    /**
     * Walks every page of the PDF, renders it to a bitmap at ~2× the
     * document's native resolution (PDF is 72 DPI; OCR really wants
     * 150–200 DPI to read small fonts cleanly), runs ML Kit on it, and
     * concatenates the per-page text separated by blank lines so the
     * parser sees a single continuous transcript.
     *
     * Bitmaps are recycled as soon as they've been consumed so we don't
     * blow up memory on long reports.
     */
    private suspend fun recognizePdf(uri: Uri): String = withContext(Dispatchers.IO) {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalStateException("Cannot open PDF descriptor for $uri")
        val transcript = StringBuilder()
        try {
            PdfRenderer(pfd).use { renderer ->
                // Hard cap on the number of pages we'll process. An
                // adversarial / pathological PDF with hundreds of pages
                // would otherwise allocate hundreds of bitmaps and OOM.
                // Real lab reports cap at ~10 pages in practice.
                val pageCount = renderer.pageCount.coerceAtMost(MAX_PDF_PAGES)
                for (i in 0 until pageCount) {
                    renderer.openPage(i).use { page ->
                        // Cap the longest side at MAX_PIXELS_PER_SIDE.
                        // PDF coords are 72 DPI; OCR wants ~150-200 DPI,
                        // so we'd like ~2-3× scale. But on an A4 page
                        // that's 1190×1684 → 8 MB at ARGB_8888. With
                        // RGB_565 it halves to 4 MB and still gives
                        // ML Kit enough contrast for clean recognition.
                        val longest = maxOf(page.width, page.height)
                        val rawScale = (MAX_PIXELS_PER_SIDE.toFloat() / longest)
                            .coerceAtMost(3f)
                            .coerceAtLeast(1f)
                        val bitmap = Bitmap.createBitmap(
                            (page.width * rawScale).toInt(),
                            (page.height * rawScale).toInt(),
                            Bitmap.Config.RGB_565,
                        )
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val pageText = recognizeBitmap(bitmap)
                        bitmap.recycle()
                        if (pageText.isNotBlank()) {
                            if (transcript.isNotEmpty()) transcript.append("\n\n")
                            transcript.append(pageText)
                        }
                    }
                }
            }
        } finally {
            runCatching { pfd.close() }
        }
        transcript.toString()
    }

    companion object {
        // Defence against pathological PDFs. Real lab reports are typically
        // 1-5 pages; 50 is comfortably above the worst case while keeping
        // peak memory bounded.
        private const val MAX_PDF_PAGES = 50
        // Per-side pixel cap. Keeps a single rendered page under ~3 MB
        // (RGB_565 = 2 bytes/pixel; 1500×1500 = 4.5 MB).
        private const val MAX_PIXELS_PER_SIDE = 1500
    }

    private suspend fun recognizeBitmap(bitmap: Bitmap): String = suspendCancellableCoroutine { cont ->
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { result -> if (cont.isActive) cont.resume(result.text) }
            .addOnFailureListener { e -> if (cont.isActive) cont.resumeWithException(e) }
    }
}
