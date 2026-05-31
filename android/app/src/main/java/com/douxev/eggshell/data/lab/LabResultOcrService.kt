package com.douxev.eggshell.data.lab

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import com.googlecode.tesseract.android.TessBaseAPI
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lab-result text extraction.
 *
 * Two-stage strategy, optimised for the common case (French/European labs
 * ship native PDFs with an embedded text layer):
 *
 *  1. **Direct text extraction via PDFBox-Android**. Most lab reports
 *     have selectable text, so we can pull it losslessly with no OCR pass
 *     at all — fast (~100 ms for a 3-page PDF), zero accuracy loss.
 *  2. **Tesseract OCR fallback** when stage 1 returns empty or whitespace,
 *     which happens for true scans (paper report photographed) or for
 *     image inputs (JPEG/PNG). Bundled language data: `fra` + `eng`
 *     (tessdata_fast variants for size — ~5 MB total).
 *
 * Both libraries are FOSS (BSD / Apache 2.0); no proprietary blobs. The
 * previous ML Kit dependency was removed for F-Droid official compliance.
 */
@Singleton
class LabResultOcrService @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    init {
        // PDFBox needs an initialised resource loader before any PDF API
        // is touched. Cheap to call repeatedly — guarded internally.
        PDFBoxResourceLoader.init(context.applicationContext)
    }

    suspend fun recognize(uri: Uri): String = withContext(Dispatchers.IO) {
        val mime = context.contentResolver.getType(uri)
        when {
            mime == "application/pdf" -> recognizePdf(uri)
            mime != null && mime.startsWith("image/") -> ocrImage(uri)
            uri.path?.endsWith(".pdf", ignoreCase = true) == true -> recognizePdf(uri)
            else -> ocrImage(uri)
        }
    }

    // -- PDF path -----------------------------------------------------------

    private suspend fun recognizePdf(uri: Uri): String {
        // Stage 1: pull the embedded text layer if it exists.
        val embedded = runCatching { extractEmbeddedText(uri) }.getOrNull()
        if (!embedded.isNullOrBlank() && hasEnoughSignal(embedded)) {
            return embedded
        }
        // Stage 2: scanned PDF (no text layer, or text layer too sparse).
        // Render each page to a bitmap and OCR it with Tesseract.
        return ocrPdfPages(uri)
    }

    /**
     * Open the PDF with PDFBox-Android and extract the text layer if any.
     * Returns null when the PDF has no embedded text (entirely scanned),
     * empty string when extraction returned only whitespace.
     */
    private fun extractEmbeddedText(uri: Uri): String? {
        return context.contentResolver.openInputStream(uri)?.use { input ->
            PDDocument.load(input).use { doc ->
                val stripper = PDFTextStripper().apply {
                    // Sort by position so multi-column lab reports flow in
                    // visual order rather than PDF stream order.
                    sortByPosition = true
                }
                stripper.getText(doc)
            }
        }
    }

    /**
     * Heuristic: a successful extraction has at least a few alphanumeric
     * runs of length 3+. A "scanned PDF with a couple of stray glyphs"
     * scores below this and we fall through to OCR.
     */
    private fun hasEnoughSignal(text: String): Boolean {
        val tokens = text.split(Regex("[\\s\\p{Punct}]+")).filter { it.length >= 3 }
        return tokens.size >= 12
    }

    /**
     * Stage 2 for PDFs: render each page (≤ [MAX_PDF_PAGES]) with the
     * platform PdfRenderer at a bounded resolution, then OCR each
     * bitmap. RGB_565 keeps the per-page allocation under ~3 MB even on
     * A4 reports.
     */
    private fun ocrPdfPages(uri: Uri): String {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalStateException("Cannot open PDF descriptor for $uri")
        val transcript = StringBuilder()
        try {
            PdfRenderer(pfd).use { renderer ->
                val pageCount = renderer.pageCount.coerceAtMost(MAX_PDF_PAGES)
                withTesseract { tess ->
                    for (i in 0 until pageCount) {
                        renderer.openPage(i).use { page ->
                            val longest = maxOf(page.width, page.height)
                            val scale = (MAX_PIXELS_PER_SIDE.toFloat() / longest)
                                .coerceAtMost(3f).coerceAtLeast(1f)
                            val bitmap = Bitmap.createBitmap(
                                (page.width * scale).toInt(),
                                (page.height * scale).toInt(),
                                Bitmap.Config.RGB_565,
                            )
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            tess.setImage(bitmap)
                            val pageText = tess.utF8Text.orEmpty()
                            bitmap.recycle()
                            if (pageText.isNotBlank()) {
                                if (transcript.isNotEmpty()) transcript.append("\n\n")
                                transcript.append(pageText)
                            }
                        }
                    }
                }
            }
        } finally {
            runCatching { pfd.close() }
        }
        return transcript.toString()
    }

    // -- Image path ---------------------------------------------------------

    private fun ocrImage(uri: Uri): String {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("Cannot open image at $uri")
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IllegalStateException("Cannot decode image at $uri")
        return try {
            withTesseract { tess ->
                tess.setImage(bitmap)
                tess.utF8Text.orEmpty()
            }
        } finally {
            bitmap.recycle()
        }
    }

    // -- Tesseract plumbing -------------------------------------------------

    /**
     * Open a Tesseract session bound to the bundled `fra+eng` data,
     * hand it to [block], and release it. Cheap enough to instantiate
     * per request that we don't pool it (~50 ms init), and pooling would
     * complicate locking around the underlying native handle.
     */
    private inline fun <R> withTesseract(block: (TessBaseAPI) -> R): R {
        val tessDir = ensureTessData()
        val tess = TessBaseAPI()
        try {
            // Tesseract's init() wants the PARENT dir of `tessdata/`,
            // not `tessdata/` itself. The language string is `+`-separated.
            check(tess.init(tessDir.parent, "fra+eng")) {
                "Tesseract init failed for ${tessDir.absolutePath}"
            }
            return block(tess)
        } finally {
            tess.recycle()
        }
    }

    /**
     * Tesseract reads its model files from the filesystem, so we copy the
     * bundled `tessdata/<lang>.traineddata` from the APK assets to the
     * app's private files dir on first use. Subsequent calls reuse the
     * copies.
     */
    private fun ensureTessData(): File {
        val dir = File(context.filesDir, "tessdata").apply { mkdirs() }
        for (lang in listOf("fra", "eng")) {
            val target = File(dir, "$lang.traineddata")
            if (target.exists()) continue
            context.assets.open("tessdata/$lang.traineddata").use { src ->
                target.outputStream().use { dst -> src.copyTo(dst) }
            }
        }
        return dir
    }

    companion object {
        private const val MAX_PDF_PAGES = 50
        private const val MAX_PIXELS_PER_SIDE = 1500
    }
}
