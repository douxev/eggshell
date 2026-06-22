package com.douxev.eggshell.data

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.douxev.eggshell.reminders.LabReminderManager
import com.douxev.eggshell.reminders.LabReminderPrefs
import uniffi.transition.HormoneMeasurement
import uniffi.transition.JournalEntry
import uniffi.transition.Medication

/**
 * Generates a styled PDF report from the vault's data.
 *
 * Layout language: lavender accent bar at the top with the user's name +
 * "Bilan THS", section headers in primary color with a thin underline,
 * info chips (KIND/ROUTE/UNIT) as pill backgrounds, and tables for hormone
 * series with the latest value highlighted. Sparkline-style line charts for
 * hormones are drawn natively on the PDF Canvas.
 *
 * Section inclusion is driven by the [Options] passed in, so the user can
 * opt into / out of each block from the export screen.
 */
@Singleton
class PdfReportExporter @Inject constructor(
    private val medications: MedicationRepository,
    private val journals: JournalRepository,
    private val hormones: HormonesRepository,
    private val units: HormoneUnitPrefs,
    private val labs: LabReminderManager,
    @ApplicationContext private val context: Context,
) {
    /** What to include in the report. */
    data class Options(
        val medications: Boolean = true,
        val hormones: Boolean = true,
        val journal: Boolean = true,
        val labReminders: Boolean = true,
        val periodMonths: Int = 3,
    )

    suspend fun generate(options: Options = Options()): File = withContext(Dispatchers.IO) {
        val doc = PdfDocument()
        val ctx = RenderContext(doc, context)
        ctx.newPage(firstPage = true)
        ctx.drawCoverHeader()

        val now = System.currentTimeMillis()
        val cutoff = now - options.periodMonths * 30L * 86_400_000L

        if (options.medications) {
            val meds = runCatching { medications.list() }.getOrDefault(emptyList())
            ctx.section("Médications & traitements", meds.size)
            if (meds.isEmpty()) {
                ctx.bodyMuted("Aucune médication enregistrée pour la période.")
            } else {
                meds.forEach { m -> ctx.medRow(m) }
            }
        }

        if (options.hormones) {
            val distinct = runCatching { hormones.distinct() }.getOrDefault(emptyList())
            ctx.section("Taux hormonaux", distinct.size)
            if (distinct.isEmpty()) {
                ctx.bodyMuted("Aucune mesure hormonale.")
            } else {
                distinct.forEach { hormone ->
                    val raw = runCatching { hormones.listForHormone(hormone, 0, 50) }
                        .getOrDefault(emptyList())
                        .filter { it.atMs >= cutoff }
                        .sortedBy { it.atMs }
                    if (raw.isNotEmpty()) {
                        val target = units.getEffective(hormone)
                        val series = raw.map { m ->
                            val converted = if (target != null && target != m.unit) {
                                hormones.convert(m.value, m.unit, target, hormone) ?: m.value
                            } else m.value
                            HormonePoint(m, converted, target ?: m.unit)
                        }
                        ctx.hormoneBlock(hormone, series)
                    }
                }
            }
        }

        if (options.journal) {
            val entries = runCatching { journals.list(0, 200) }.getOrDefault(emptyList())
                .filter { it.atMs >= cutoff }
                .sortedByDescending { it.atMs }
            ctx.section("Journal & ressentis", entries.size)
            if (entries.isEmpty()) {
                ctx.bodyMuted("Aucune entrée pour la période.")
            } else {
                entries.take(40).forEach { e -> ctx.journalRow(e) }
            }
        }

        if (options.labReminders) {
            val list = labs.list().filter { it.category == LabReminderPrefs.CATEGORY_LAB }
            ctx.section("Rappels d'analyses", list.size)
            if (list.isEmpty()) {
                ctx.bodyMuted("Aucun rappel d'analyse configuré.")
            } else {
                list.forEach { e -> ctx.labReminderRow(e) }
            }
        }

        ctx.drawFooter()
        ctx.finish()
        // Write into the cache subdir that the FileProvider actually exposes
        // (see res/xml/file_provider_paths.xml — only pdf_export/ is shared,
        // not the cache root). Writing to the root made getUriForFile throw and
        // crashed the share.
        val dir = File(context.cacheDir, "pdf_export").apply { mkdirs() }
        val out = File(dir, "transition-report-${System.currentTimeMillis()}.pdf")
        out.outputStream().use { doc.writeTo(it) }
        doc.close()
        // Clean up older reports AFTER writing the fresh one, and never the file
        // we just produced — so we don't yank a previously-shared PDF that an
        // external app may still be reading, while still not leaving stale
        // decrypted health data piling up in cache.
        dir.listFiles()?.forEach { if (it != out) it.delete() }
        out
    }

    // ------------------------------------------------------------------
    // Rendering helpers
    // ------------------------------------------------------------------

    private data class HormonePoint(val raw: HormoneMeasurement, val displayValue: Double, val displayUnit: String)

    private class RenderContext(val doc: PdfDocument, val context: Context) {
        // A4 @ 72 dpi: 595 x 842 pt.
        private val pageW = 595
        private val pageH = 842
        private val marginX = 42f
        private val marginTop = 64f
        private val marginBottom = 56f
        private var pageNumber = 0
        private var page: PdfDocument.Page? = null
        private var canvas: android.graphics.Canvas? = null
        private var y = marginTop

        // Lavender palette mirroring the M3 theme.
        private val primary = Color.parseColor("#6A4FA3")
        private val primaryContainer = Color.parseColor("#EADDFF")
        private val onPrimaryContainer = Color.parseColor("#21005D")
        private val surfaceContainer = Color.parseColor("#F4EDF4")
        private val surfaceContainerHigh = Color.parseColor("#EBE0EB")
        private val outline = Color.parseColor("#7B7689")
        private val onSurface = Color.parseColor("#1D1B20")
        private val onSurfaceVariant = Color.parseColor("#49454F")

        private val pTitle = Paint().apply {
            isAntiAlias = true; textSize = 22f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            color = onSurface
        }
        private val pH1 = Paint().apply {
            isAntiAlias = true; textSize = 28f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            color = onSurface
        }
        private val pH2 = Paint().apply {
            isAntiAlias = true; textSize = 15f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            color = primary
        }
        private val pBody = Paint().apply {
            isAntiAlias = true; textSize = 10.5f
            color = onSurface
        }
        private val pBodyBold = Paint().apply {
            isAntiAlias = true; textSize = 10.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            color = onSurface
        }
        private val pMuted = Paint().apply {
            isAntiAlias = true; textSize = 10f
            color = onSurfaceVariant
        }
        private val pLabel = Paint().apply {
            isAntiAlias = true; textSize = 8.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.08f
            color = onSurfaceVariant
        }
        private val pAccent = Paint().apply {
            isAntiAlias = true; color = primary
        }
        private val pRuleFaint = Paint().apply {
            isAntiAlias = true; color = surfaceContainerHigh
            strokeWidth = 1f
        }

        private val dateFmt = SimpleDateFormat("d MMM yy", Locale.getDefault())
        private val dateTimeFmt = SimpleDateFormat("d MMM yy · HH:mm", Locale.getDefault())

        fun newPage(firstPage: Boolean = false) {
            page?.let { doc.finishPage(it) }
            pageNumber += 1
            val info = PdfDocument.PageInfo.Builder(pageW, pageH, pageNumber).create()
            val p = doc.startPage(info)
            page = p
            canvas = p.canvas
            y = if (firstPage) 130f else marginTop

            // Header accent bar
            val c = canvas!!
            c.drawRect(0f, 0f, pageW.toFloat(), 8f, pAccent)
            // Page number
            c.drawText(
                "page $pageNumber",
                pageW - marginX - 60f,
                marginTop - 28f,
                pMuted,
            )
        }

        fun drawCoverHeader() {
            val c = canvas ?: return
            val now = Date()
            c.drawText("Bilan", marginX, 72f, pH1)
            c.drawText(
                "Édition du " + SimpleDateFormat("d MMMM yyyy", Locale.getDefault()).format(now),
                marginX,
                94f,
                pMuted,
            )
            val rulePaint = Paint().apply {
                color = Color.parseColor("#D6CDD8"); strokeWidth = 0.8f; isAntiAlias = true
            }
            c.drawLine(marginX, 110f, pageW - marginX, 110f, rulePaint)
        }

        fun section(title: String, count: Int) {
            ensureSpace(60f)
            y += 18f
            val c = canvas!!
            // Number circle
            val circleR = 14f
            c.drawCircle(marginX + circleR, y + 8f, circleR, pAccent)
            val pCount = Paint().apply {
                color = Color.WHITE; textSize = 12f; isAntiAlias = true
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            c.drawText(count.toString(), marginX + circleR, y + 12f, pCount)
            c.drawText(title, marginX + 2 * circleR + 10f, y + 13f, pH2)
            // Underline
            c.drawLine(marginX, y + 28f, pageW - marginX, y + 28f, pRuleFaint)
            y += 40f
        }

        fun medRow(m: Medication) {
            ensureSpace(46f)
            val c = canvas!!
            // Pill block
            val routeLabel = m.route.replace("_", " ")
            val kindLabel = m.kind.replace("_", " ")
            c.drawText(m.name, marginX, y, pBodyBold)
            val dose = m.defaultDose?.let { d ->
                val u = m.defaultDoseUnit.orEmpty()
                "${trim(d)} $u"
            }.orEmpty()
            c.drawText("$dose · $routeLabel", marginX + 200f, y, pMuted)
            y += 14f
            drawPill(c, marginX, y - 2f, kindLabel.uppercase(), bg = surfaceContainerHigh, fg = onSurfaceVariant)
            y += 22f
        }

        fun hormoneBlock(hormone: String, series: List<HormonePoint>) {
            val needed = 130f + 14f * series.size.coerceAtMost(6)
            ensureSpace(needed)
            val c = canvas!!
            // Title
            c.drawText(hormone.replaceFirstChar { it.titlecase(Locale.getDefault()) }, marginX, y, pBodyBold)
            val latest = series.last()
            val latestText = "${trim(latest.displayValue)} ${latest.displayUnit}"
            val tw = pBodyBold.measureText(latestText)
            c.drawText(latestText, pageW - marginX - tw, y, pBodyBold)
            y += 6f

            // Sparkline
            if (series.size >= 2) {
                val chartH = 56f
                drawSparkline(c, marginX, y, pageW - 2 * marginX, chartH, series.map { it.displayValue })
                y += chartH + 4f
            }

            // Last few entries (table)
            val rows = series.takeLast(5).reversed()
            rows.forEach { p ->
                c.drawText(dateFmt.format(Date(p.raw.atMs)), marginX, y + 12f, pMuted)
                val v = "${trim(p.displayValue)} ${p.displayUnit}"
                val vw = pBody.measureText(v)
                c.drawText(v, pageW - marginX - vw, y + 12f, pBody)
                if (p.displayUnit != p.raw.unit) {
                    val orig = "(${trim(p.raw.value)} ${p.raw.unit})"
                    val ow = pMuted.measureText(orig)
                    c.drawText(orig, pageW - marginX - vw - ow - 8f, y + 12f, pMuted)
                }
                y += 14f
                c.drawLine(marginX, y, pageW - marginX, y, pRuleFaint)
            }
            y += 18f
        }

        fun journalRow(e: JournalEntry) {
            val gauges = listOfNotNull(
                e.mood?.let { "Humeur $it" },
                e.dysphoria?.let { "Dysphorie $it" },
                e.euphoria?.let { "Euphorie $it" },
                e.libido?.let { "Libido $it" },
                e.energy?.let { "Énergie $it" },
            ).joinToString(" · ")
            val freeLines = wrap(e.freeText.orEmpty(), pBody, pageW - 2 * marginX - 12f)
            ensureSpace(36f + 12f * freeLines.size)
            val c = canvas!!
            c.drawText(dateTimeFmt.format(Date(e.atMs)), marginX, y, pBodyBold)
            y += 13f
            c.drawText(gauges, marginX, y, pMuted)
            y += 12f
            freeLines.forEach { line ->
                c.drawText(line, marginX + 12f, y, pBody)
                y += 12f
            }
            e.sideEffects?.takeIf { it.isNotBlank() }?.let {
                c.drawText("Effets : $it", marginX + 12f, y, pMuted)
                y += 12f
            }
            y += 6f
            c.drawLine(marginX, y, pageW - marginX, y, pRuleFaint)
            y += 8f
        }

        fun labReminderRow(e: LabReminderPrefs.Entry) {
            ensureSpace(22f)
            val c = canvas!!
            val schedule = when (e.kind) {
                "interval" -> "tous les ${e.intervalDays} j"
                "daily" -> String.format(Locale.getDefault(), "tous les jours à %02d:%02d", e.dailyHour ?: 0, e.dailyMinute ?: 0)
                else -> ""
            }
            c.drawText(e.label, marginX, y, pBodyBold)
            val sw = pMuted.measureText(schedule)
            c.drawText(schedule, pageW - marginX - sw, y, pMuted)
            y += 18f
        }

        fun bodyMuted(text: String) {
            ensureSpace(18f)
            canvas?.drawText(text, marginX, y, pMuted)
            y += 14f
        }

        fun drawFooter() {
            val c = canvas ?: return
            val footerY = pageH - marginBottom + 28f
            val text = "Généré localement par Transition · aucune donnée n'a quitté l'appareil."
            c.drawText(text, marginX, footerY, pMuted)
        }

        fun finish() {
            page?.let { doc.finishPage(it) }
            page = null
            canvas = null
        }

        // -- helpers -----------------------------------------------------

        private fun ensureSpace(needed: Float) {
            if (y + needed > pageH - marginBottom) {
                newPage()
            }
        }

        private fun drawPill(
            c: android.graphics.Canvas,
            x: Float,
            y: Float,
            text: String,
            bg: Int,
            fg: Int,
        ) {
            val padX = 7f
            val padY = 3.5f
            val tw = pLabel.measureText(text)
            val rect = RectF(x, y - 9f, x + tw + 2 * padX, y + padY + 1f)
            val bgPaint = Paint().apply { color = bg; isAntiAlias = true }
            c.drawRoundRect(rect, 12f, 12f, bgPaint)
            val fgPaint = Paint(pLabel).apply { color = fg }
            c.drawText(text, x + padX, y, fgPaint)
        }

        private fun drawSparkline(
            c: android.graphics.Canvas,
            x: Float,
            y: Float,
            w: Float,
            h: Float,
            values: List<Double>,
        ) {
            if (values.size < 2) return
            val min = values.min()
            val max = values.max()
            val range = (max - min).takeIf { it > 0 } ?: 1.0
            val pad = 4f
            val pts = values.mapIndexed { i, v ->
                val px = x + (w * i.toFloat()) / (values.size - 1)
                val py = y + pad + (h - 2 * pad) * (1f - ((v - min) / range).toFloat())
                px to py
            }

            // baseline
            val rulePaint = Paint().apply {
                color = surfaceContainerHigh; strokeWidth = 1f; isAntiAlias = true
            }
            c.drawLine(x, y + h, x + w, y + h, rulePaint)

            // line
            val linePaint = Paint().apply {
                color = primary; strokeWidth = 2.4f; isAntiAlias = true
                style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            val areaPath = Path()
            val linePath = Path()
            pts.forEachIndexed { i, (px, py) ->
                if (i == 0) {
                    linePath.moveTo(px, py)
                    areaPath.moveTo(px, y + h); areaPath.lineTo(px, py)
                } else {
                    linePath.lineTo(px, py)
                    areaPath.lineTo(px, py)
                }
            }
            areaPath.lineTo(pts.last().first, y + h)
            areaPath.close()

            val areaPaint = Paint().apply {
                color = primary; alpha = 38; isAntiAlias = true
                style = Paint.Style.FILL
            }
            c.drawPath(areaPath, areaPaint)
            c.drawPath(linePath, linePaint)

            val dotPaint = Paint().apply { color = primary; isAntiAlias = true }
            c.drawCircle(pts.last().first, pts.last().second, 3.2f, dotPaint)
        }

        private fun trim(v: Double): String {
            val rounded = ((v * 100).toLong()) / 100.0
            val s = rounded.toString()
            return if (s.endsWith(".0")) s.dropLast(2) else s
        }

        private fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
            if (text.isBlank()) return emptyList()
            val words = text.split(' ')
            val lines = mutableListOf<String>()
            var line = StringBuilder()
            words.forEach { w ->
                val candidate = if (line.isEmpty()) w else "$line $w"
                if (paint.measureText(candidate) <= maxWidth) {
                    line = StringBuilder(candidate)
                } else {
                    if (line.isNotEmpty()) lines.add(line.toString())
                    line = StringBuilder(w)
                }
            }
            if (line.isNotEmpty()) lines.add(line.toString())
            return lines
        }
    }

    private fun trim(v: Double): String {
        val rounded = ((v * 100).toLong()) / 100.0
        val s = rounded.toString()
        return if (s.endsWith(".0")) s.dropLast(2) else s
    }
}
