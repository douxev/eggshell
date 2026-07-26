package com.douxev.eggshell.data.pdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import kotlin.math.max
import kotlin.math.min
import com.douxev.eggshell.punctuality.DoseTiming
import com.douxev.eggshell.punctuality.timingOf

/**
 * Draws a [ReportModel] onto an A4 page, twice.
 *
 * **Why twice.** The banner and the footer both print « n / N », and N is not
 * knowable until the document has been laid out. The renderer is deterministic
 * — nothing in the layout depends on N, only the glyphs of one right-aligned
 * string do — so the first pass runs with no `PdfDocument` at all and only
 * counts pages, and the second draws with N in hand (D3).
 *
 * **Measure, then decide.** Every block computes its own height before the
 * cursor moves, and a page break happens *before* a block that does not fit.
 * The previous exporter reserved a guessed height and let long blocks bleed
 * past the bottom margin; a chart or a stat block is never split here, and a
 * table that splits carries its header onto the next page.
 */
internal class ReportPainter(
    private val bannerLeft: String,
    private val footerLeft: String,
) {
    private val paints = ReportPaints()

    /** Lays the document out and returns the number of pages it needed. */
    fun measure(model: ReportModel): Int = paint(model, doc = null, totalPages = 0)

    fun paint(model: ReportModel, doc: PdfDocument?, totalPages: Int): Int {
        val sheet = Sheet(doc, totalPages, paints, bannerLeft, footerLeft)
        sheet.newPage()
        drawDocumentHead(sheet, model)
        model.sections.forEachIndexed { index, section ->
            drawSection(sheet, index + 1, section)
        }
        drawDisclaimer(sheet, model)
        sheet.finish()
        return sheet.pageCount
    }

    // -----------------------------------------------------------------------
    // Document furniture
    // -----------------------------------------------------------------------

    private fun drawDocumentHead(sheet: Sheet, model: ReportModel) {
        sheet.y += px(26f)
        sheet.textTop(model.title, Geo.CONTENT_L, paints.title)
        sheet.y += px(6f)
        sheet.textTop(model.subtitle, Geo.CONTENT_L, paints.subtitle)

        val identity = model.identity ?: return
        if (identity.isEmpty) return
        sheet.y += px(22f)
        val padV = px(16f)
        val padH = px(18f)
        val labelH = paints.identityLabel.lh()
        val valueH = paints.identityValue.lh()
        val boxH = padV * 2 + labelH + px(5f) + valueH
        sheet.ensure(boxH)
        sheet.roundRect(Geo.CONTENT_L, sheet.y, Geo.CONTENT_R, sheet.y + boxH, paints.boxOutlineFaint)
        val fields = listOfNotNull(
            identity.name?.takeIf { it.isNotBlank() }?.let { IDENTITY_NAME to it },
            identity.birthDate?.takeIf { it.isNotBlank() }?.let { IDENTITY_BIRTH to it },
        )
        // The fields are laid out tight, as before — but a name is free text of
        // any length, and the natural widths plus their gap used to be allowed
        // to run past CONTENT_R. When they no longer fit, every field gives up
        // the same share, and what still overflows its own slot is clipped: the
        // box has a fixed height, so wrapping would push a second line out
        // through its bottom edge instead.
        val gap = px(44f)
        val inner = Geo.CONTENT_W - 2 * padH
        val natural = fields.map { (label, value) ->
            max(paints.identityLabel.measureText(label), paints.identityValue.measureText(value))
        }
        val gaps = gap * max(0, fields.size - 1)
        val total = natural.sum()
        val scale = if (total + gaps > inner && total > 0f) {
            (inner - gaps).coerceAtLeast(0f) / total
        } else {
            1f
        }
        var x = Geo.CONTENT_L + padH
        fields.forEachIndexed { index, (label, value) ->
            val slot = natural[index] * scale
            sheet.textAt(clip(label, paints.identityLabel, slot), x, sheet.y + padV, paints.identityLabel)
            sheet.textAt(
                clip(value, paints.identityValue, slot),
                x,
                sheet.y + padV + labelH + px(5f),
                paints.identityValue,
            )
            x += slot + gap
        }
        sheet.y += boxH
    }

    /** Cuts [text] down to [maxWidth], with an ellipsis when anything was cut. */
    private fun clip(text: String, paint: Paint, maxWidth: Float): String {
        if (maxWidth <= 0f || paint.measureText(text) <= maxWidth) return text
        val room = maxWidth - paint.measureText(ELLIPSIS)
        if (room <= 0f) return ELLIPSIS
        val kept = paint.breakText(text, true, room, null)
        return text.substring(0, kept).trimEnd() + ELLIPSIS
    }

    private fun drawSection(sheet: Sheet, number: Int, section: Section) {
        val titleH = paints.sectionTitle.lh()
        // A section that opens a page breathes a little less above it.
        val top = if (sheet.y <= Geo.BODY_TOP + 0.5f) Geo.SECTION_TOP_FIRST else Geo.SECTION_TOP
        val first = section.blocks.firstOrNull()
        // A section title is never the last thing on a page: it is reserved
        // together with enough of its first block to prove it has company.
        sheet.ensure(top + titleH + Geo.SECTION_BOTTOM + (first?.let { minimumChunk(sheet, it) } ?: 0f))
        // Re-read after the reservation: the section may have just opened a page.
        sheet.y += if (sheet.y <= Geo.BODY_TOP + 0.5f) Geo.SECTION_TOP_FIRST else top
        val label = "$number — ${section.title}"
        sheet.textTop(label, Geo.CONTENT_L, paints.sectionTitle, advance = false)
        val ruleX = Geo.CONTENT_L + paints.sectionTitle.measureText(label) + px(12f)
        sheet.rule(ruleX, sheet.y + titleH / 2f, Geo.CONTENT_R, paints.ruleHair)
        sheet.y += titleH + Geo.SECTION_BOTTOM
        section.blocks.forEachIndexed { index, block ->
            drawBlock(sheet, block)
            if (index != section.blocks.lastIndex) sheet.y += px(10f)
        }
    }

    private fun drawDisclaimer(sheet: Sheet, model: ReportModel) {
        sheet.y += px(24f)
        val padV = px(16f)
        val padH = px(18f)
        val inner = Geo.CONTENT_W - 2 * padH
        val lines = sheet.wrapWithLead(model.disclaimerLead, model.disclaimerBody, paints.noteLead, paints.note, inner)
        val lh = paints.note.lh()
        val boxH = padV * 2 + lines.size * lh
        sheet.ensure(boxH)
        sheet.roundRect(Geo.CONTENT_L, sheet.y, Geo.CONTENT_R, sheet.y + boxH, paints.boxOutline)
        var ty = sheet.y + padV
        lines.forEachIndexed { index, line ->
            if (index == 0) {
                sheet.textAt(model.disclaimerLead, Geo.CONTENT_L + padH, ty, paints.noteLead)
                val offset = paints.noteLead.measureText(model.disclaimerLead) + paints.note.measureText(" ")
                sheet.textAt(line, Geo.CONTENT_L + padH + offset, ty, paints.note)
            } else {
                sheet.textAt(line, Geo.CONTENT_L + padH, ty, paints.note)
            }
            ty += lh
        }
        sheet.y += boxH
    }

    // -----------------------------------------------------------------------
    // Blocks
    // -----------------------------------------------------------------------

    private fun drawBlock(sheet: Sheet, block: Block) {
        when (block) {
            is Block.Paragraph -> drawParagraph(sheet, block)
            is Block.Caption -> {
                sheet.ensure(paints.caption.lh())
                sheet.textTop(block.text, Geo.CONTENT_L, paints.caption)
            }
            is Block.Table -> drawTable(sheet, block)
            is Block.DatedList -> drawDatedList(sheet, block)
            is Block.StatChart -> drawStatChart(sheet, block)
            is Block.HeadValues -> drawHeadValues(sheet, block)
            is Block.WideChart -> drawWideChart(sheet, block)
            is Block.Chips -> drawChips(sheet, block)
            is Block.Checklist -> drawChecklist(sheet, block)
            is Block.Photos -> drawPhotos(sheet, block)
        }
    }

    private fun paragraphPaint(block: Block.Paragraph) = if (block.note) paints.note else paints.cellMuted

    private fun drawParagraph(sheet: Sheet, block: Block.Paragraph) {
        val paint = paragraphPaint(block)
        val lines = sheet.wrap(block.text, paint, Geo.CONTENT_W)
        val lh = paint.lh()
        lines.forEach { line ->
            sheet.ensure(lh)
            sheet.textTop(line, Geo.CONTENT_L, paint)
        }
    }

    private fun drawTable(sheet: Sheet, block: Block.Table) {
        val cols = block.columns
        if (cols.isEmpty()) return
        val total = cols.fold(0f) { acc, c -> acc + c.weight }
        val xs = FloatArray(cols.size)
        val ws = FloatArray(cols.size)
        var x = Geo.CONTENT_L
        cols.forEachIndexed { i, c ->
            ws[i] = Geo.CONTENT_W * c.weight / total
            xs[i] = x
            x += ws[i]
        }
        val cellInset = px(10f)
        val headH = paints.tableHead.lh() + px(7f)
        val lh = paints.cell.lh()

        val wrapped = block.rows.map { row ->
            row.mapIndexed { i, cell ->
                val paint = cellPaint(cols[i])
                val width = ws[i] - if (cols[i].alignRight) 0f else cellInset
                sheet.wrap(cell, paint, width)
            }
        }

        fun header() {
            cols.forEachIndexed { i, c ->
                if (c.alignRight) {
                    sheet.textRightAt(c.title, xs[i] + ws[i], sheet.y, paints.tableHead)
                } else {
                    sheet.textAt(c.title, xs[i], sheet.y, paints.tableHead)
                }
            }
            sheet.y += headH
            sheet.rule(Geo.CONTENT_L, sheet.y, Geo.CONTENT_R, paints.ruleTable)
            sheet.y += 1.2f
        }

        val firstRowH = (wrapped.firstOrNull()?.maxOfOrNull { it.size } ?: 1) * lh + 2 * block.rowPadPt
        sheet.ensure(headH + 1.2f + firstRowH + 1f)
        header()

        wrapped.forEach { cells ->
            val lines = max(1, cells.maxOfOrNull { it.size } ?: 1)
            val rowH = lines * lh + 2 * block.rowPadPt
            if (!sheet.fits(rowH + 1f)) {
                sheet.newPage()
                header()
            }
            cells.forEachIndexed { i, text ->
                val paint = cellPaint(cols[i])
                text.forEachIndexed { line, s ->
                    val ty = sheet.y + block.rowPadPt + line * lh
                    if (cols[i].alignRight) {
                        sheet.textRightAt(s, xs[i] + ws[i], ty, paint)
                    } else {
                        sheet.textAt(s, xs[i], ty, paint)
                    }
                }
            }
            sheet.y += rowH
            sheet.rule(Geo.CONTENT_L, sheet.y, Geo.CONTENT_R, paints.ruleHair)
            sheet.y += 1f
        }
    }

    private fun cellPaint(column: Column): Paint = when {
        column.strong -> paints.cellStrong
        column.muted -> paints.cellMuted
        else -> paints.cell
    }

    private fun drawDatedList(sheet: Sheet, block: Block.DatedList) {
        val dateW = px(96f)
        val gap = px(20f)
        val bodyX = Geo.CONTENT_L + dateW + gap
        val bodyW = Geo.CONTENT_R - bodyX
        val pad = px(9f)
        val lh = paints.cell.lh()
        block.rows.forEach { (date, text) ->
            val lines = sheet.wrap(text, paints.cell, bodyW)
            val rowH = max(1, lines.size) * lh + 2 * pad
            sheet.ensure(rowH + 1f)
            sheet.textAt(date, Geo.CONTENT_L, sheet.y + pad, paints.cellMuted)
            lines.forEachIndexed { i, line ->
                sheet.textAt(line, bodyX, sheet.y + pad + i * lh, paints.cell)
            }
            sheet.y += rowH
            sheet.rule(Geo.CONTENT_L, sheet.y, Geo.CONTENT_R, paints.ruleHair)
            sheet.y += 1f
        }
    }

    private fun statBlockHeight(stats: List<Stat>): Float {
        val lh = paints.cell.lh()
        val pad = px(6f)
        return stats.size * (lh + 2 * pad) + max(0, stats.size - 1) * 1f
    }

    private fun drawStatBlock(sheet: Sheet, stats: List<Stat>, top: Float) {
        val lh = paints.cell.lh()
        val pad = px(6f)
        val right = Geo.CONTENT_L + Geo.STAT_W
        var y = top
        stats.forEachIndexed { index, stat ->
            sheet.textAt(stat.label, Geo.CONTENT_L, y + pad, paints.cellMuted)
            sheet.textRightAt(stat.value, right, y + pad, paints.cellStrong)
            y += lh + 2 * pad
            // No rule under the last row: the block ends, it is not cut off.
            if (index != stats.lastIndex) {
                sheet.rule(Geo.CONTENT_L, y, right, paints.ruleHair)
                y += 1f
            }
        }
    }

    /** The note lives in the right column whenever a stat block holds the left. */
    private fun noteWidth(block: Block.StatChart): Float =
        if (block.stats.isEmpty()) Geo.CONTENT_W else Geo.CHART_W

    private fun chartBlockHeight(sheet: Sheet, block: Block.StatChart): Float {
        val captionH = block.caption?.let { paints.caption.lh() + px(4f) } ?: 0f
        val chartH = block.chart?.let { it.heightPt + boundsHeight(it) }
            ?: block.punctuality?.let { PUNCT_H }
            ?: 0f
        val noteH = block.note?.let { note ->
            px(2f) + sheet.wrap(note, paints.note, noteWidth(block)).size * paints.note.lh()
        } ?: 0f
        val rightH = captionH + chartH + noteH
        return max(statBlockHeight(block.stats), rightH)
    }

    private fun drawStatChart(sheet: Sheet, block: Block.StatChart) {
        val height = chartBlockHeight(sheet, block)
        sheet.ensure(height)
        val top = sheet.y
        val statH = statBlockHeight(block.stats)
        val statTop = if (block.centred) top + (height - statH) / 2f else top
        if (block.stats.isNotEmpty()) drawStatBlock(sheet, block.stats, statTop)

        val chartX = if (block.stats.isEmpty()) Geo.CONTENT_L else Geo.CONTENT_L + Geo.STAT_W + Geo.STAT_GAP
        var y = top
        block.caption?.let {
            sheet.textAt(it, chartX, y, paints.caption)
            y += paints.caption.lh() + px(4f)
        }
        block.chart?.let {
            drawChart(sheet, it, chartX, y)
            y += it.heightPt + boundsHeight(it)
        }
        block.punctuality?.let {
            drawPunctuality(sheet, it, chartX, y)
            y += PUNCT_H
        }
        block.note?.let { note ->
            y += px(2f)
            sheet.wrap(note, paints.note, noteWidth(block)).forEach { line ->
                sheet.textAt(line, chartX, y, paints.note)
                y += paints.note.lh()
            }
        }
        sheet.y = top + height
    }

    private fun valueRowWidth(head: HeadValue): Float {
        var w = paints.big.measureText(head.value) + px(7f) + paints.bigUnit.measureText(head.unit)
        head.conversion?.let { w += px(7f) + paints.bigConv.measureText(it) }
        return w
    }

    private fun headValueWidth(head: HeadValue): Float =
        max(valueRowWidth(head), paints.caption.measureText(head.caption))

    private fun drawHeadValues(sheet: Sheet, block: Block.HeadValues) {
        val height = paints.caption.lh() + px(3f) + paints.big.lh()
        sheet.ensure(height)
        val top = sheet.y
        drawHeadValue(sheet, block.left, Geo.CONTENT_L, top, alignRight = false)
        block.right?.let {
            drawHeadValue(sheet, it, Geo.CONTENT_R - headValueWidth(it), top, alignRight = true)
        }
        sheet.y = top + height
    }

    private fun drawHeadValue(sheet: Sheet, head: HeadValue, x: Float, top: Float, alignRight: Boolean) {
        val width = headValueWidth(head)
        val captionX = if (alignRight) x + width - paints.caption.measureText(head.caption) else x
        sheet.textAt(head.caption, captionX, top, paints.caption)
        // The three sizes sit on one baseline: the number leads, the unit and
        // the conversion follow it rather than float beside it.
        val baseline = top + paints.caption.lh() + px(3f) - paints.big.ascent()
        var cursor = if (alignRight) x + width - valueRowWidth(head) else x
        sheet.baselineText(head.value, cursor, baseline, paints.big)
        cursor += paints.big.measureText(head.value) + px(7f)
        sheet.baselineText(head.unit, cursor, baseline, paints.bigUnit)
        cursor += paints.bigUnit.measureText(head.unit) + px(7f)
        head.conversion?.let { sheet.baselineText(it, cursor, baseline, paints.bigConv) }
    }

    private fun boundsHeight(spec: ChartSpec): Float =
        if (spec.bounds == null) 0f else paints.bound.lh()

    private fun wideChartHeight(spec: ChartSpec, legend: List<LegendItem>): Float {
        val legendH = if (legend.isEmpty()) 0f else px(8f) + paints.note.lh()
        return CHART_LEAD + spec.heightPt + boundsHeight(spec) + legendH
    }

    private fun drawWideChart(sheet: Sheet, block: Block.WideChart) {
        val height = wideChartHeight(block.chart, block.legend)
        sheet.ensure(height)
        val top = sheet.y
        drawChart(sheet, block.chart, Geo.CONTENT_L, top + CHART_LEAD)
        if (block.legend.isNotEmpty()) {
            var x = Geo.CONTENT_L
            val y = top + CHART_LEAD + block.chart.heightPt + boundsHeight(block.chart) + px(8f)
            val centre = y + paints.note.lh() / 2f
            block.legend.forEach { item ->
                val paint = if (item.secondary) paints.seriesSecondary else paints.seriesMain
                sheet.swatch(x, centre, px(18f), paint)
                sheet.textAt(item.label, x + px(18f) + px(7f), y, paints.note)
                x += px(18f) + px(7f) + paints.note.measureText(item.label) + px(22f)
            }
            block.legendTail?.let { sheet.textRightAt(it, Geo.CONTENT_R, y, paints.note) }
        }
        sheet.y = top + height
    }

    private fun chipsHeight(sheet: Sheet, block: Block.Chips): Float {
        val lh = paints.cell.lh()
        return max(1, chipLines(sheet, block).size) * (lh + px(4f))
    }

    private fun chipLines(sheet: Sheet, block: Block.Chips): List<List<Pair<String, String>>> {
        val gap = px(26f)
        val out = ArrayList<List<Pair<String, String>>>()
        var line = ArrayList<Pair<String, String>>()
        var width = 0f
        block.items.forEach { item ->
            val w = paints.cell.measureText(item.first + " ") + paints.cellStrong.measureText(item.second)
            if (line.isNotEmpty() && width + gap + w > Geo.CONTENT_W) {
                out.add(line)
                line = ArrayList()
                width = 0f
            }
            if (line.isNotEmpty()) width += gap
            line.add(item)
            width += w
        }
        if (line.isNotEmpty()) out.add(line)
        return out
    }

    private fun drawChips(sheet: Sheet, block: Block.Chips) {
        val lines = chipLines(sheet, block)
        val lh = paints.cell.lh()
        val gap = px(26f)
        sheet.ensure(chipsHeight(sheet, block))
        lines.forEach { line ->
            var x = Geo.CONTENT_L
            line.forEach { (label, count) ->
                sheet.textAt(label, x, sheet.y, paints.cell)
                x += paints.cell.measureText("$label ")
                sheet.textAt(count, x, sheet.y, paints.cellStrong)
                x += paints.cellStrong.measureText(count) + gap
            }
            sheet.y += lh + px(4f)
        }
    }

    private fun drawChecklist(sheet: Sheet, block: Block.Checklist) {
        val box = px(15f)
        val gap = px(12f)
        val pad = px(8f)
        val lh = paints.cell.lh()
        val textX = Geo.CONTENT_L + box + gap
        val textW = Geo.CONTENT_R - textX
        block.items.forEach { item ->
            val lines = sheet.wrap(item, paints.cell, textW)
            val h = max(1, lines.size) * lh + 2 * pad
            sheet.ensure(h)
            sheet.square(Geo.CONTENT_L, sheet.y + pad + px(3f), box, paints.checkbox)
            lines.forEachIndexed { i, line ->
                sheet.textAt(line, textX, sheet.y + pad + i * lh, paints.cell)
            }
            sheet.y += h
        }
    }

    private fun drawPhotos(sheet: Sheet, block: Block.Photos) {
        val gap = px(14f)
        val cell = (Geo.CONTENT_W - 3 * gap) / 4f
        val captionH = paints.note.lh() + px(3f)
        block.tiles.chunked(4).forEach { row ->
            sheet.ensure(cell + captionH)
            row.forEachIndexed { i, tile ->
                val x = Geo.CONTENT_L + i * (cell + gap)
                sheet.photo(tile, x, sheet.y, cell)
                sheet.textAt(tile.date, x, sheet.y + cell + px(3f), paints.note)
            }
            sheet.y += cell + captionH + gap
        }
    }

    /**
     * The smallest piece of a block that has to fit for the block to be worth
     * starting on this page — a whole chart, or a table's header plus one row.
     */
    private fun minimumChunk(sheet: Sheet, block: Block): Float = when (block) {
        is Block.Paragraph -> paragraphPaint(block).lh()
        is Block.Caption -> paints.caption.lh()
        is Block.Table -> paints.tableHead.lh() + px(7f) + 1.2f + paints.cell.lh() + 2 * block.rowPadPt
        is Block.DatedList -> paints.cell.lh() + 2 * px(9f)
        is Block.StatChart -> chartBlockHeight(sheet, block)
        is Block.HeadValues -> paints.caption.lh() + px(3f) + paints.big.lh()
        is Block.WideChart -> wideChartHeight(block.chart, block.legend)
        is Block.Chips -> paints.cell.lh() + px(4f)
        is Block.Checklist -> paints.cell.lh() + 2 * px(8f)
        is Block.Photos -> (Geo.CONTENT_W - 3 * px(14f)) / 4f
    }

    // -----------------------------------------------------------------------
    // Charts
    // -----------------------------------------------------------------------

    private fun drawChart(sheet: Sheet, spec: ChartSpec, x0: Float, top: Float) {
        val plotLeft = x0 + spec.gutterPt
        val plotRight = x0 + spec.widthPt
        val seriesLeft = plotLeft + spec.insetPt
        val seriesRight = plotRight - spec.insetPt
        val baselineY = top + spec.baselinePt
        val plotTopY = top + spec.plotTopPt

        val axisPaint = paints.axis(Ink.onSurfaceVariant)
        spec.gridlinesPt.forEachIndexed { index, g ->
            val gy = top + g
            sheet.rule(plotLeft, gy, plotRight, paints.ruleGrid)
            spec.yTickLabels.getOrNull(index)?.let { label ->
                sheet.textRightAt(label, plotLeft - px(5f), gy - axisPaint.lh() / 2f, axisPaint)
            }
        }
        sheet.rule(plotLeft, baselineY, plotRight, paints.ruleBaseline)
        if (spec.gutterPt > 0f) sheet.vLine(plotLeft, plotTopY, baselineY, paints.ruleAxis)

        val span = (spec.toMs - spec.fromMs).coerceAtLeast(1L).toDouble()
        val range = (spec.yMax - spec.yMin).takeIf { it > 0.0 } ?: 1.0
        fun xFor(atMs: Long): Float {
            val t = ((atMs - spec.fromMs).toDouble() / span).coerceIn(0.0, 1.0)
            return seriesLeft + (seriesRight - seriesLeft) * t.toFloat()
        }
        fun yFor(v: Double): Float {
            val f = ((v - spec.yMin) / range).coerceIn(0.0, 1.0)
            return baselineY - (baselineY - plotTopY) * f.toFloat()
        }

        spec.markers.forEach { marker ->
            val mx = xFor(marker.atMs)
            sheet.vLine(mx, plotTopY, baselineY, paints.markerLine)
            val w = paints.annotation.measureText(marker.label)
            val lx = if (mx + 3f + w > plotRight) mx - 3f - w else mx + 3f
            sheet.textAt(marker.label, lx, plotTopY + px(8f), paints.annotation)
        }

        spec.series.forEach { series ->
            if (series.points.isEmpty()) return@forEach
            val path = Path()
            series.points.forEachIndexed { i, p ->
                val cx = xFor(p.atMs)
                val cy = yFor(p.value)
                if (i == 0) path.moveTo(cx, cy) else path.lineTo(cx, cy)
            }
            sheet.path(path, if (series.dashed) paints.seriesSecondary else paints.seriesMain)
            // §5.1: a main curve ends on a filled, slightly larger point whether
            // or not it dots every sample along the way.
            if (series.dots || series.terminalDot) {
                val fill = paints.dot(if (series.secondary) Ink.tertiary else Ink.primary)
                series.points.forEachIndexed { i, p ->
                    val last = i == series.points.lastIndex
                    if (series.dots || last) {
                        sheet.circle(xFor(p.atMs), yFor(p.value), if (last) 2.7f else 2.1f, fill)
                    }
                }
            }
        }

        spec.bounds?.let { (left, right) ->
            val by = top + spec.heightPt
            sheet.textAt(left, plotLeft, by, paints.bound)
            sheet.textRightAt(right, plotRight, by, paints.bound)
        }
    }

    private fun drawPunctuality(sheet: Sheet, spec: PunctualitySpec, x0: Float, top: Float) {
        val plotLeft = x0 + PUNCT_GUTTER
        val plotRight = x0 + spec.widthPt
        val seriesLeft = plotLeft + 3.1f
        val seriesRight = plotRight - 3.1f

        listOf(P_MID, P_MAX).forEach { sheet.rule(plotLeft, top + it, plotRight, paints.ruleGrid) }
        sheet.rule(plotLeft, top + P_ZERO, plotRight, paints.zeroLine)
        sheet.rule(plotLeft, top + P_SEP, plotRight, paints.missedSeparator)
        sheet.vLine(plotLeft, top + P_TOP, top + P_BOTTOM, paints.ruleAxis)

        val labels = listOf(
            Triple(P_ZERO, spec.tickLabels.getOrNull(0).orEmpty(), Ink.tertiary),
            Triple(P_MID, spec.tickLabels.getOrNull(1).orEmpty(), Ink.secondary),
            Triple(P_MAX, spec.tickLabels.getOrNull(2).orEmpty(), Ink.secondary),
            Triple(P_MISS, spec.missedLabel, Ink.error),
        )
        labels.forEach { (anchor, label, colour) ->
            if (label.isEmpty()) return@forEach
            val paint = paints.axis(colour)
            sheet.textRightAt(label, plotLeft - px(5f), top + anchor - paint.lh() / 2f, paint)
        }

        val span = (spec.toMs - spec.fromMs).coerceAtLeast(1L).toDouble()
        val maxDelay = spec.axis.maxDelayMin.coerceAtLeast(1)
        spec.points.forEach { point ->
            val t = ((point.atMs - spec.fromMs).toDouble() / span).coerceIn(0.0, 1.0)
            val cx = seriesLeft + (seriesRight - seriesLeft) * t.toFloat()
            val delta = point.deltaMin
            val cy = if (delta == null) {
                top + P_MISS
            } else {
                // Early doses sit above the zero line, clamped so a single
                // hour-early intake cannot flatten the whole scale.
                val clamped = delta.coerceIn(-EARLY_CLAMP_MIN, maxDelay)
                top + P_ZERO + (clamped.toFloat() / maxDelay) * (P_MAX - P_ZERO)
            }
            val colour = when (timingOf(delta)) {
                DoseTiming.Missed -> Ink.error
                DoseTiming.Late -> Ink.secondary
                DoseTiming.OnTime -> Ink.tertiary
            }
            sheet.circle(cx, cy, 2.9f, paints.dot(colour))
        }
    }

    private companion object {
        /** Anchors of the punctuality slot, already in points (§B4.1). */
        const val PUNCT_H = 83.2f
        const val PUNCT_GUTTER = 50.8f
        const val P_TOP = 10.4f
        const val P_ZERO = 15.6f
        const val P_MID = 36.4f
        const val P_MAX = 57.2f
        const val P_SEP = 65.0f
        const val P_MISS = 72.8f
        const val P_BOTTOM = 78.0f
        const val EARLY_CLAMP_MIN = 14

        /** Breathing room above a full-width chart, inside its own block. */
        val CHART_LEAD = px(6f)

        const val IDENTITY_NAME = "PERSONNE SUIVIE"
        const val IDENTITY_BIRTH = "NÉE LE"
        const val ELLIPSIS = "…"
    }
}

internal fun Paint.lh(): Float = descent() - ascent()

/**
 * One A4 sheet being filled, top to bottom.
 *
 * When [doc] is null nothing is drawn and only the cursor moves: that is the
 * measuring pass. Every primitive therefore has to be safe with no canvas.
 */
internal class Sheet(
    private val doc: PdfDocument?,
    private val totalPages: Int,
    private val paints: ReportPaints,
    private val bannerLeft: String,
    private val footerLeft: String,
) {
    var y: Float = Geo.BODY_TOP
    var pageCount: Int = 0
        private set

    private var page: PdfDocument.Page? = null
    private var canvas: Canvas? = null

    fun newPage() {
        page?.let { doc?.finishPage(it) }
        pageCount += 1
        val started = doc?.startPage(
            PdfDocument.PageInfo.Builder(Geo.PAGE_W.toInt(), Geo.PAGE_H.toInt(), pageCount).create()
        )
        page = started
        canvas = started?.canvas
        y = Geo.BODY_TOP
        canvas?.let { c ->
            c.drawColor(Ink.page)
            val counter = "$pageCount / $totalPages"
            drawBaseline(bannerLeft, Geo.CONTENT_L, Geo.BANNER_BASELINE, paints.bannerLeft)
            drawBaseline(
                counter,
                Geo.CONTENT_R - paints.bannerRight.measureText(counter),
                Geo.BANNER_BASELINE,
                paints.bannerRight,
            )
            c.drawLine(Geo.CONTENT_L, Geo.BANNER_RULE, Geo.CONTENT_R, Geo.BANNER_RULE, paints.ruleStrong)
            c.drawLine(Geo.CONTENT_L, Geo.FOOTER_RULE, Geo.CONTENT_R, Geo.FOOTER_RULE, paints.ruleHair)
            drawBaseline(footerLeft, Geo.CONTENT_L, Geo.FOOTER_BASELINE, paints.footer)
            drawBaseline(
                counter,
                Geo.CONTENT_R - paints.footer.measureText(counter),
                Geo.FOOTER_BASELINE,
                paints.footer,
            )
        }
    }

    fun finish() {
        page?.let { doc?.finishPage(it) }
        page = null
        canvas = null
    }

    fun fits(height: Float): Boolean = y + height <= Geo.BODY_BOTTOM

    fun ensure(height: Float) {
        if (!fits(height)) newPage()
    }

    // -- text ---------------------------------------------------------------

    private fun drawBaseline(text: String, x: Float, baseline: Float, paint: Paint) {
        canvas?.drawText(text, x, baseline, paint)
    }

    fun baselineText(text: String, x: Float, baseline: Float, paint: Paint) =
        drawBaseline(text, x, baseline, paint)

    /** Draws with [top] as the top of the line box, and leaves the cursor alone. */
    fun textAt(text: String, x: Float, top: Float, paint: Paint) {
        drawBaseline(text, x, top - paint.ascent(), paint)
    }

    fun textRightAt(text: String, right: Float, top: Float, paint: Paint) {
        textAt(text, right - paint.measureText(text), top, paint)
    }

    /** Draws at the cursor and advances it by one line. */
    fun textTop(text: String, x: Float, paint: Paint, advance: Boolean = true) {
        textAt(text, x, y, paint)
        if (advance) y += paint.lh()
    }

    // -- strokes ------------------------------------------------------------

    fun rule(x1: Float, atY: Float, x2: Float, paint: Paint) {
        canvas?.drawLine(x1, atY, x2, atY, paint)
    }

    fun vLine(x: Float, y1: Float, y2: Float, paint: Paint) {
        canvas?.drawLine(x, y1, x, y2, paint)
    }

    fun path(path: Path, paint: Paint) {
        canvas?.drawPath(path, paint)
    }

    fun circle(cx: Float, cy: Float, r: Float, paint: Paint) {
        canvas?.drawCircle(cx, cy, r, paint)
    }

    fun roundRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
        canvas?.drawRoundRect(RectF(left, top, right, bottom), px(4f), px(4f), paint)
    }

    /** The unticked box of §8 — square, unfilled, the doctor's to fill in. */
    fun square(left: Float, top: Float, size: Float, paint: Paint) {
        canvas?.drawRect(left, top, left + size, top + size, paint)
    }

    fun swatch(x: Float, centreY: Float, width: Float, paint: Paint) {
        canvas?.drawLine(x, centreY, x + width, centreY, paint)
    }

    fun photo(tile: PhotoTile, x: Float, top: Float, size: Float) {
        val c = canvas ?: return
        val bitmap = decode(tile.bytes, size) ?: return
        // Fit inside the cell, centred: a report is not a contact sheet, and
        // cropping someone's body to fill a square is not ours to do.
        val scale = min(size / bitmap.width, size / bitmap.height)
        val w = bitmap.width * scale
        val h = bitmap.height * scale
        val dst = RectF(x + (size - w) / 2f, top + (size - h) / 2f, x + (size + w) / 2f, top + (size + h) / 2f)
        c.drawBitmap(bitmap, Rect(0, 0, bitmap.width, bitmap.height), dst, null)
        bitmap.recycle()
    }

    private fun decode(bytes: ByteArray, target: Float): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        // 4× the slot: enough for print, small enough that a page of thumbnails
        // does not hold a dozen full-resolution photos in memory at once.
        val wanted = (target * 4f).toInt().coerceAtLeast(1)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= wanted && bounds.outHeight / (sample * 2) >= wanted) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) }.getOrNull()
    }

    // -- wrapping -----------------------------------------------------------

    /**
     * Greedy wrap that also breaks a word too long for the line. Both previous
     * wrappers let such a word run past the right margin.
     */
    fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
        if (text.isEmpty() || maxWidth <= 0f) return emptyList()
        val out = ArrayList<String>()
        text.split('\n').forEach { paragraph ->
            var line = StringBuilder()
            paragraph.split(' ').filter { it.isNotEmpty() }.forEach { word ->
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (paint.measureText(candidate) <= maxWidth) {
                    line = StringBuilder(candidate)
                    return@forEach
                }
                if (line.isNotEmpty()) {
                    out.add(line.toString())
                    line = StringBuilder()
                }
                var rest = word
                while (paint.measureText(rest) > maxWidth && rest.length > 1) {
                    var cut = rest.length
                    while (cut > 1 && paint.measureText(rest.substring(0, cut)) > maxWidth) cut--
                    out.add(rest.substring(0, cut))
                    rest = rest.substring(cut)
                }
                line = StringBuilder(rest)
            }
            if (line.isNotEmpty()) out.add(line.toString())
        }
        return out
    }

    /**
     * Wraps [body] knowing that [lead] is printed inline before it, in a
     * heavier face. The returned first line is the remainder of that line.
     */
    fun wrapWithLead(
        lead: String,
        body: String,
        leadPaint: Paint,
        bodyPaint: Paint,
        maxWidth: Float,
    ): List<String> {
        val offset = leadPaint.measureText(lead) + bodyPaint.measureText(" ")
        val firstWidth = maxWidth - offset
        val words = body.split(' ').filter { it.isNotEmpty() }
        val out = ArrayList<String>()
        var line = StringBuilder()
        var width = firstWidth
        words.forEach { word ->
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (bodyPaint.measureText(candidate) <= width) {
                line = StringBuilder(candidate)
            } else {
                out.add(line.toString())
                line = StringBuilder(word)
                width = maxWidth
            }
        }
        out.add(line.toString())
        return out
    }
}
