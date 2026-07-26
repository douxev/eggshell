import CoreGraphics
import Foundation
import UIKit

// Draws a `ReportDocument` onto A4 pages, twice.
//
// **Why twice.** The banner and the footer both print « n / N », and N is not
// knowable until the document has been laid out. The renderer is deterministic —
// nothing in the layout depends on N, only the glyphs of one right-aligned
// string do — so the first pass runs with no graphics context at all and only
// counts pages, and the second draws with N in hand (D3).
//
// **Measure, then decide.** Every block computes its own height before the
// cursor moves, and a page break happens *before* a block that does not fit. A
// chart or a stat block is never split, and a table that splits carries its
// header onto the next page.
//
// Mirror of the Android `data/pdf/ReportPainter.kt`. This file draws and nothing
// else: what the document *says* is `DoctorReportBuilder`'s business.

struct DoctorReportRenderer {
    private let bannerLeft: String
    private let footerLeft: String
    private let s = ReportStyles()

    init(bannerLeft: String, footerLeft: String) {
        self.bannerLeft = bannerLeft
        self.footerLeft = footerLeft
    }

    /// Lays the document out with no canvas and returns the page count it needs.
    func measure(_ document: ReportDocument) -> Int {
        var sheet = ReportSheet(
            canvas: nil, totalPages: 0, styles: s, bannerLeft: bannerLeft, footerLeft: footerLeft)
        paint(document, into: &sheet)
        return sheet.pageCount
    }

    /// The whole document, as PDF bytes. Runs the measuring pass first.
    func pdfData(_ document: ReportDocument) -> Data {
        let total = measure(document)
        let bounds = CGRect(x: 0, y: 0, width: ReportGeo.pageW, height: ReportGeo.pageH)
        let renderer = UIGraphicsPDFRenderer(bounds: bounds, format: UIGraphicsPDFRendererFormat())
        return renderer.pdfData { context in
            var sheet = ReportSheet(
                canvas: context, totalPages: total, styles: s,
                bannerLeft: bannerLeft, footerLeft: footerLeft)
            paint(document, into: &sheet)
        }
    }

    private func paint(_ document: ReportDocument, into sheet: inout ReportSheet) {
        sheet.newPage()
        drawDocumentHead(&sheet, document)
        for (index, section) in document.sections.enumerated() {
            drawSection(&sheet, number: index + 1, section)
        }
        drawDisclaimer(&sheet, document)
    }

    // MARK: - Document furniture

    private func drawDocumentHead(_ sheet: inout ReportSheet, _ document: ReportDocument) {
        sheet.y += ReportGeo.px(26)
        sheet.textTop(document.title, x: ReportGeo.contentL, s.title)
        sheet.y += ReportGeo.px(6)
        sheet.textTop(document.subtitle, x: ReportGeo.contentL, s.subtitle)

        guard let identity = document.identity, !identity.isEmpty else { return }
        sheet.y += ReportGeo.px(22)
        let padV = ReportGeo.px(16)
        let padH = ReportGeo.px(18)
        let labelH = s.identityLabel.lineHeight
        let valueH = s.identityValue.lineHeight
        let boxH = padV * 2 + labelH + ReportGeo.px(5) + valueH
        sheet.ensure(boxH)
        sheet.roundRect(
            left: ReportGeo.contentL, top: sheet.y, right: ReportGeo.contentR,
            bottom: sheet.y + boxH, s.boxOutlineFaint)
        var x = ReportGeo.contentL + padH
        var fields: [(String, String)] = []
        if let name = identity.name, !name.isEmpty { fields.append(("PERSONNE SUIVIE", name)) }
        if let birth = identity.birthDate, !birth.isEmpty { fields.append(("NÉE LE", birth)) }
        for (label, value) in fields {
            sheet.textAt(label, x: x, top: sheet.y + padV, s.identityLabel)
            sheet.textAt(
                value, x: x, top: sheet.y + padV + labelH + ReportGeo.px(5), s.identityValue)
            x += max(s.identityLabel.width(label), s.identityValue.width(value)) + ReportGeo.px(44)
        }
        sheet.y += boxH
    }

    private func drawSection(_ sheet: inout ReportSheet, number: Int, _ section: ReportSection) {
        let titleH = s.sectionTitle.lineHeight
        // A section that opens a page breathes a little less above it.
        let top = sheet.y <= ReportGeo.bodyTop + 0.5 ? ReportGeo.sectionTopFirst : ReportGeo.sectionTop
        let firstChunk = section.blocks.first.map { minimumChunk(sheet, $0) } ?? 0
        // A section title is never the last thing on a page: it is reserved
        // together with enough of its first block to prove it has company.
        sheet.ensure(top + titleH + ReportGeo.sectionBottom + firstChunk)
        // Re-read after the reservation: the section may have just opened a page.
        sheet.y += sheet.y <= ReportGeo.bodyTop + 0.5 ? ReportGeo.sectionTopFirst : top
        let label = "\(number) — \(section.title)"
        sheet.textAt(label, x: ReportGeo.contentL, top: sheet.y, s.sectionTitle)
        let ruleX = ReportGeo.contentL + s.sectionTitle.width(label) + ReportGeo.px(12)
        sheet.rule(from: ruleX, at: sheet.y + titleH / 2, to: ReportGeo.contentR, s.ruleHair)
        sheet.y += titleH + ReportGeo.sectionBottom
        for (index, block) in section.blocks.enumerated() {
            drawBlock(&sheet, block)
            if index != section.blocks.count - 1 { sheet.y += ReportGeo.px(10) }
        }
    }

    private func drawDisclaimer(_ sheet: inout ReportSheet, _ document: ReportDocument) {
        sheet.y += ReportGeo.px(24)
        let padV = ReportGeo.px(16)
        let padH = ReportGeo.px(18)
        let inner = ReportGeo.contentW - 2 * padH
        let lines = sheet.wrapWithLead(
            lead: document.disclaimerLead, body: document.disclaimerBody,
            leadStyle: s.noteLead, bodyStyle: s.note, maxWidth: inner)
        let lh = s.note.lineHeight
        let boxH = padV * 2 + CGFloat(lines.count) * lh
        sheet.ensure(boxH)
        sheet.roundRect(
            left: ReportGeo.contentL, top: sheet.y, right: ReportGeo.contentR,
            bottom: sheet.y + boxH, s.boxOutline)
        var ty = sheet.y + padV
        for (index, line) in lines.enumerated() {
            if index == 0 {
                sheet.textAt(
                    document.disclaimerLead, x: ReportGeo.contentL + padH, top: ty, s.noteLead)
                let offset = s.noteLead.width(document.disclaimerLead) + s.note.width(" ")
                sheet.textAt(line, x: ReportGeo.contentL + padH + offset, top: ty, s.note)
            } else {
                sheet.textAt(line, x: ReportGeo.contentL + padH, top: ty, s.note)
            }
            ty += lh
        }
        sheet.y += boxH
    }

    // MARK: - Blocks

    private func drawBlock(_ sheet: inout ReportSheet, _ block: ReportBlock) {
        switch block {
        case .paragraph(let text, let note):
            drawParagraph(&sheet, text: text, note: note)
        case .caption(let text):
            sheet.ensure(s.caption.lineHeight)
            sheet.textTop(text, x: ReportGeo.contentL, s.caption)
        case .table(let columns, let rows, let rowPad):
            drawTable(&sheet, columns: columns, rows: rows, rowPad: rowPad)
        case .datedList(let rows):
            drawDatedList(&sheet, rows)
        case .statChart(let spec):
            drawStatChart(&sheet, spec)
        case .headValues(let left, let right):
            drawHeadValues(&sheet, left: left, right: right)
        case .wideChart(let chart, let legend, let legendTail):
            drawWideChart(&sheet, chart: chart, legend: legend, legendTail: legendTail)
        case .chips(let items):
            drawChips(&sheet, items)
        case .checklist(let items):
            drawChecklist(&sheet, items)
        case .photos(let tiles):
            drawPhotos(&sheet, tiles)
        }
    }

    private func paragraphStyle(note: Bool) -> ReportTextStyle { note ? s.note : s.cellMuted }

    private func drawParagraph(_ sheet: inout ReportSheet, text: String, note: Bool) {
        let style = paragraphStyle(note: note)
        let lines = sheet.wrap(text, style, maxWidth: ReportGeo.contentW)
        for line in lines {
            sheet.ensure(style.lineHeight)
            sheet.textTop(line, x: ReportGeo.contentL, style)
        }
    }

    private func cellStyle(_ column: ReportColumn) -> ReportTextStyle {
        if column.strong { return s.cellStrong }
        if column.muted { return s.cellMuted }
        return s.cell
    }

    private func drawTable(
        _ sheet: inout ReportSheet,
        columns: [ReportColumn],
        rows: [[String]],
        rowPad: CGFloat
    ) {
        guard !columns.isEmpty else { return }
        let total = columns.reduce(CGFloat(0)) { $0 + $1.weight }
        guard total > 0 else { return }
        var xs: [CGFloat] = []
        var ws: [CGFloat] = []
        var x = ReportGeo.contentL
        for column in columns {
            let w = ReportGeo.contentW * column.weight / total
            ws.append(w)
            xs.append(x)
            x += w
        }
        let cellInset = ReportGeo.px(10)
        let headH = s.tableHead.lineHeight + ReportGeo.px(7)
        let lh = s.cell.lineHeight

        let wrapped: [[[String]]] = rows.map { row in
            row.enumerated().map { index, text in
                guard index < columns.count else { return [text] }
                let style = cellStyle(columns[index])
                let width = ws[index] - (columns[index].alignRight ? 0 : cellInset)
                return sheet.wrap(text, style, maxWidth: width)
            }
        }

        func header(_ sheet: inout ReportSheet) {
            for (index, column) in columns.enumerated() {
                if column.alignRight {
                    sheet.textRightAt(
                        column.title, right: xs[index] + ws[index], top: sheet.y, s.tableHead)
                } else {
                    sheet.textAt(column.title, x: xs[index], top: sheet.y, s.tableHead)
                }
            }
            sheet.y += headH
            sheet.rule(
                from: ReportGeo.contentL, at: sheet.y, to: ReportGeo.contentR, s.ruleTable)
            sheet.y += 1.2
        }

        let firstLines = wrapped.first?.map { $0.count }.max() ?? 1
        let firstRowH = CGFloat(max(1, firstLines)) * lh + 2 * rowPad
        sheet.ensure(headH + 1.2 + firstRowH + 1)
        header(&sheet)

        for cells in wrapped {
            let lines = max(1, cells.map { $0.count }.max() ?? 1)
            let rowH = CGFloat(lines) * lh + 2 * rowPad
            if !sheet.fits(rowH + 1) {
                sheet.newPage()
                header(&sheet)
            }
            for (index, text) in cells.enumerated() {
                guard index < columns.count else { continue }
                let style = cellStyle(columns[index])
                for (line, value) in text.enumerated() {
                    let ty = sheet.y + rowPad + CGFloat(line) * lh
                    if columns[index].alignRight {
                        sheet.textRightAt(value, right: xs[index] + ws[index], top: ty, style)
                    } else {
                        sheet.textAt(value, x: xs[index], top: ty, style)
                    }
                }
            }
            sheet.y += rowH
            sheet.rule(from: ReportGeo.contentL, at: sheet.y, to: ReportGeo.contentR, s.ruleHair)
            sheet.y += 1
        }
    }

    private func drawDatedList(
        _ sheet: inout ReportSheet,
        _ rows: [(date: String, text: String)]
    ) {
        let dateW = ReportGeo.px(96)
        let gap = ReportGeo.px(20)
        let bodyX = ReportGeo.contentL + dateW + gap
        let bodyW = ReportGeo.contentR - bodyX
        let pad = ReportGeo.px(9)
        let lh = s.cell.lineHeight
        for row in rows {
            let lines = sheet.wrap(row.text, s.cell, maxWidth: bodyW)
            let rowH = CGFloat(max(1, lines.count)) * lh + 2 * pad
            sheet.ensure(rowH + 1)
            sheet.textAt(row.date, x: ReportGeo.contentL, top: sheet.y + pad, s.cellMuted)
            for (index, line) in lines.enumerated() {
                sheet.textAt(line, x: bodyX, top: sheet.y + pad + CGFloat(index) * lh, s.cell)
            }
            sheet.y += rowH
            sheet.rule(from: ReportGeo.contentL, at: sheet.y, to: ReportGeo.contentR, s.ruleHair)
            sheet.y += 1
        }
    }

    private func statBlockHeight(_ stats: [ReportStat]) -> CGFloat {
        let lh = s.cell.lineHeight
        let pad = ReportGeo.px(6)
        return CGFloat(stats.count) * (lh + 2 * pad) + CGFloat(max(0, stats.count - 1)) * 1
    }

    private func drawStatBlock(_ sheet: inout ReportSheet, _ stats: [ReportStat], top: CGFloat) {
        let lh = s.cell.lineHeight
        let pad = ReportGeo.px(6)
        let right = ReportGeo.contentL + ReportGeo.statW
        var y = top
        for (index, stat) in stats.enumerated() {
            sheet.textAt(stat.label, x: ReportGeo.contentL, top: y + pad, s.cellMuted)
            sheet.textRightAt(stat.value, right: right, top: y + pad, s.cellStrong)
            y += lh + 2 * pad
            // No rule under the last row: the block ends, it is not cut off.
            if index != stats.count - 1 {
                sheet.rule(from: ReportGeo.contentL, at: y, to: right, s.ruleHair)
                y += 1
            }
        }
    }

    /// The note lives in the right column whenever a stat block holds the left.
    private func noteWidth(_ spec: ReportStatChart) -> CGFloat {
        spec.stats.isEmpty ? ReportGeo.contentW : ReportGeo.chartW
    }

    private func boundsHeight(_ chart: ReportChartSpec) -> CGFloat {
        chart.bounds == nil ? 0 : s.bound.lineHeight
    }

    private func chartBlockHeight(_ sheet: ReportSheet, _ spec: ReportStatChart) -> CGFloat {
        let captionH = spec.caption == nil ? 0 : s.caption.lineHeight + ReportGeo.px(4)
        var chartH: CGFloat = 0
        if let chart = spec.chart { chartH = chart.height + boundsHeight(chart) }
        else if spec.punctuality != nil { chartH = ReportGeo.punctH }
        var noteH: CGFloat = 0
        if let note = spec.note {
            let lines = sheet.wrap(note, s.note, maxWidth: noteWidth(spec))
            noteH = ReportGeo.px(2) + CGFloat(lines.count) * s.note.lineHeight
        }
        return max(statBlockHeight(spec.stats), captionH + chartH + noteH)
    }

    private func drawStatChart(_ sheet: inout ReportSheet, _ spec: ReportStatChart) {
        let height = chartBlockHeight(sheet, spec)
        sheet.ensure(height)
        let top = sheet.y
        let statH = statBlockHeight(spec.stats)
        let statTop = spec.centred ? top + (height - statH) / 2 : top
        if !spec.stats.isEmpty { drawStatBlock(&sheet, spec.stats, top: statTop) }

        let chartX = spec.stats.isEmpty
            ? ReportGeo.contentL
            : ReportGeo.contentL + ReportGeo.statW + ReportGeo.statGap
        var y = top
        if let caption = spec.caption {
            sheet.textAt(caption, x: chartX, top: y, s.caption)
            y += s.caption.lineHeight + ReportGeo.px(4)
        }
        if let chart = spec.chart {
            drawChart(&sheet, chart, x0: chartX, top: y)
            y += chart.height + boundsHeight(chart)
        }
        if let punctuality = spec.punctuality {
            drawPunctuality(&sheet, punctuality, x0: chartX, top: y)
            y += ReportGeo.punctH
        }
        if let note = spec.note {
            y += ReportGeo.px(2)
            for line in sheet.wrap(note, s.note, maxWidth: noteWidth(spec)) {
                sheet.textAt(line, x: chartX, top: y, s.note)
                y += s.note.lineHeight
            }
        }
        sheet.y = top + height
    }

    private func valueRowWidth(_ head: ReportHeadValue) -> CGFloat {
        var w = s.big.width(head.value) + ReportGeo.px(7) + s.bigUnit.width(head.unit)
        if let conversion = head.conversion {
            w += ReportGeo.px(7) + s.bigConv.width(conversion)
        }
        return w
    }

    private func headValueWidth(_ head: ReportHeadValue) -> CGFloat {
        max(valueRowWidth(head), s.caption.width(head.caption))
    }

    private func drawHeadValues(
        _ sheet: inout ReportSheet,
        left: ReportHeadValue,
        right: ReportHeadValue?
    ) {
        let height = s.caption.lineHeight + ReportGeo.px(3) + s.big.lineHeight
        sheet.ensure(height)
        let top = sheet.y
        drawHeadValue(&sheet, left, x: ReportGeo.contentL, top: top, alignRight: false)
        if let right {
            drawHeadValue(
                &sheet, right, x: ReportGeo.contentR - headValueWidth(right),
                top: top, alignRight: true)
        }
        sheet.y = top + height
    }

    private func drawHeadValue(
        _ sheet: inout ReportSheet,
        _ head: ReportHeadValue,
        x: CGFloat,
        top: CGFloat,
        alignRight: Bool
    ) {
        let width = headValueWidth(head)
        let captionX = alignRight ? x + width - s.caption.width(head.caption) : x
        sheet.textAt(head.caption, x: captionX, top: top, s.caption)
        // The three sizes sit on one baseline: the number leads, the unit and the
        // conversion follow it rather than float beside it.
        let valueTop = top + s.caption.lineHeight + ReportGeo.px(3)
        var cursor = alignRight ? x + width - valueRowWidth(head) : x
        sheet.textAt(head.value, x: cursor, top: valueTop, s.big)
        cursor += s.big.width(head.value) + ReportGeo.px(7)
        let unitDrop = s.big.font.ascender - s.bigUnit.font.ascender
        sheet.textAt(head.unit, x: cursor, top: valueTop + unitDrop, s.bigUnit)
        cursor += s.bigUnit.width(head.unit) + ReportGeo.px(7)
        if let conversion = head.conversion {
            let convDrop = s.big.font.ascender - s.bigConv.font.ascender
            sheet.textAt(conversion, x: cursor, top: valueTop + convDrop, s.bigConv)
        }
    }

    private func wideChartHeight(_ chart: ReportChartSpec, _ legend: [ReportLegendItem]) -> CGFloat {
        let legendH = legend.isEmpty ? 0 : ReportGeo.px(8) + s.note.lineHeight
        return ReportGeo.chartLead + chart.height + boundsHeight(chart) + legendH
    }

    private func drawWideChart(
        _ sheet: inout ReportSheet,
        chart: ReportChartSpec,
        legend: [ReportLegendItem],
        legendTail: String?
    ) {
        let height = wideChartHeight(chart, legend)
        sheet.ensure(height)
        let top = sheet.y
        drawChart(&sheet, chart, x0: ReportGeo.contentL, top: top + ReportGeo.chartLead)
        if !legend.isEmpty {
            var x = ReportGeo.contentL
            let y = top + ReportGeo.chartLead + chart.height + boundsHeight(chart) + ReportGeo.px(8)
            let centre = y + s.note.lineHeight / 2
            for item in legend {
                let stroke = item.secondary ? s.seriesSecondary : s.seriesMain
                sheet.swatch(x: x, centreY: centre, width: ReportGeo.px(18), stroke)
                sheet.textAt(
                    item.label, x: x + ReportGeo.px(18) + ReportGeo.px(7), top: y, s.note)
                x += ReportGeo.px(18) + ReportGeo.px(7) + s.note.width(item.label) + ReportGeo.px(22)
            }
            if let legendTail {
                sheet.textRightAt(legendTail, right: ReportGeo.contentR, top: y, s.note)
            }
        }
        sheet.y = top + height
    }

    private func chipLines(
        _ sheet: ReportSheet,
        _ items: [(label: String, count: String)]
    ) -> [[(label: String, count: String)]] {
        let gap = ReportGeo.px(26)
        var out: [[(label: String, count: String)]] = []
        var line: [(label: String, count: String)] = []
        var width: CGFloat = 0
        for item in items {
            let w = s.cell.width(item.label + " ") + s.cellStrong.width(item.count)
            if !line.isEmpty && width + gap + w > ReportGeo.contentW {
                out.append(line)
                line = []
                width = 0
            }
            if !line.isEmpty { width += gap }
            line.append(item)
            width += w
        }
        if !line.isEmpty { out.append(line) }
        return out
    }

    private func drawChips(
        _ sheet: inout ReportSheet,
        _ items: [(label: String, count: String)]
    ) {
        let lines = chipLines(sheet, items)
        let lh = s.cell.lineHeight
        let gap = ReportGeo.px(26)
        sheet.ensure(CGFloat(max(1, lines.count)) * (lh + ReportGeo.px(4)))
        for line in lines {
            var x = ReportGeo.contentL
            for item in line {
                sheet.textAt(item.label, x: x, top: sheet.y, s.cell)
                x += s.cell.width(item.label + " ")
                sheet.textAt(item.count, x: x, top: sheet.y, s.cellStrong)
                x += s.cellStrong.width(item.count) + gap
            }
            sheet.y += lh + ReportGeo.px(4)
        }
    }

    private func drawChecklist(_ sheet: inout ReportSheet, _ items: [String]) {
        let box = ReportGeo.px(15)
        let gap = ReportGeo.px(12)
        let pad = ReportGeo.px(8)
        let lh = s.cell.lineHeight
        let textX = ReportGeo.contentL + box + gap
        let textW = ReportGeo.contentR - textX
        for item in items {
            let lines = sheet.wrap(item, s.cell, maxWidth: textW)
            let h = CGFloat(max(1, lines.count)) * lh + 2 * pad
            sheet.ensure(h)
            sheet.square(
                left: ReportGeo.contentL, top: sheet.y + pad + ReportGeo.px(3),
                size: box, s.checkbox)
            for (index, line) in lines.enumerated() {
                sheet.textAt(line, x: textX, top: sheet.y + pad + CGFloat(index) * lh, s.cell)
            }
            sheet.y += h
        }
    }

    private func drawPhotos(_ sheet: inout ReportSheet, _ tiles: [ReportPhotoTile]) {
        let gap = ReportGeo.px(14)
        let cell = (ReportGeo.contentW - 3 * gap) / 4
        let captionH = s.note.lineHeight + ReportGeo.px(3)
        var index = 0
        while index < tiles.count {
            let row = Array(tiles[index..<min(index + 4, tiles.count)])
            sheet.ensure(cell + captionH)
            for (column, tile) in row.enumerated() {
                let x = ReportGeo.contentL + CGFloat(column) * (cell + gap)
                sheet.photo(tile, x: x, top: sheet.y, size: cell)
                sheet.textAt(
                    tile.date, x: x, top: sheet.y + cell + ReportGeo.px(3), s.note)
            }
            sheet.y += cell + captionH + gap
            index += 4
        }
    }

    /// The smallest piece of a block that has to fit for the block to be worth
    /// starting on this page — a whole chart, or a table's header plus one row.
    private func minimumChunk(_ sheet: ReportSheet, _ block: ReportBlock) -> CGFloat {
        switch block {
        case .paragraph(_, let note):
            return paragraphStyle(note: note).lineHeight
        case .caption:
            return s.caption.lineHeight
        case .table(_, _, let rowPad):
            return s.tableHead.lineHeight + ReportGeo.px(7) + 1.2 + s.cell.lineHeight + 2 * rowPad
        case .datedList:
            return s.cell.lineHeight + 2 * ReportGeo.px(9)
        case .statChart(let spec):
            return chartBlockHeight(sheet, spec)
        case .headValues:
            return s.caption.lineHeight + ReportGeo.px(3) + s.big.lineHeight
        case .wideChart(let chart, let legend, _):
            return wideChartHeight(chart, legend)
        case .chips:
            return s.cell.lineHeight + ReportGeo.px(4)
        case .checklist:
            return s.cell.lineHeight + 2 * ReportGeo.px(8)
        case .photos:
            return (ReportGeo.contentW - 3 * ReportGeo.px(14)) / 4
        }
    }

    // MARK: - Charts

    private func drawChart(
        _ sheet: inout ReportSheet,
        _ spec: ReportChartSpec,
        x0: CGFloat,
        top: CGFloat
    ) {
        let plotLeft = x0 + spec.gutter
        let plotRight = x0 + spec.width
        let seriesLeft = plotLeft + spec.inset
        let seriesRight = plotRight - spec.inset
        let baselineY = top + spec.baseline
        let plotTopY = top + spec.plotTop

        let axisStyle = s.axis(ReportInk.onSurfaceVariant)
        for (index, gridline) in spec.gridlines.enumerated() {
            let gy = top + gridline
            sheet.rule(from: plotLeft, at: gy, to: plotRight, s.ruleGrid)
            if index < spec.yTickLabels.count {
                let label = spec.yTickLabels[index]
                sheet.textRightAt(
                    label, right: plotLeft - ReportGeo.px(5),
                    top: gy - axisStyle.lineHeight / 2, axisStyle)
            }
        }
        sheet.rule(from: plotLeft, at: baselineY, to: plotRight, s.ruleBaseline)
        if spec.gutter > 0 {
            sheet.vLine(x: plotLeft, from: plotTopY, to: baselineY, s.ruleAxis)
        }

        let span = Double(max(1, spec.toMs - spec.fromMs))
        let range = spec.yMax - spec.yMin > 0 ? spec.yMax - spec.yMin : 1
        func xFor(_ atMs: Int64) -> CGFloat {
            let t = min(1, max(0, Double(atMs - spec.fromMs) / span))
            return seriesLeft + (seriesRight - seriesLeft) * CGFloat(t)
        }
        func yFor(_ value: Double) -> CGFloat {
            let f = min(1, max(0, (value - spec.yMin) / range))
            return baselineY - (baselineY - plotTopY) * CGFloat(f)
        }

        for marker in spec.markers {
            let mx = xFor(marker.atMs)
            sheet.vLine(x: mx, from: plotTopY, to: baselineY, s.markerLine)
            let w = s.annotation.width(marker.label)
            let lx = mx + 3 + w > plotRight ? mx - 3 - w : mx + 3
            sheet.textAt(marker.label, x: lx, top: plotTopY + ReportGeo.px(8), s.annotation)
        }

        for series in spec.series {
            guard !series.points.isEmpty else { continue }
            let path = CGMutablePath()
            for (index, point) in series.points.enumerated() {
                let cx = xFor(point.atMs)
                let cy = yFor(point.value)
                if index == 0 { path.move(to: CGPoint(x: cx, y: cy)) }
                else { path.addLine(to: CGPoint(x: cx, y: cy)) }
            }
            sheet.path(path, series.dashed ? s.seriesSecondary : s.seriesMain)
            // §5.1: a main curve ends on a filled, slightly larger point whether
            // or not it dots every sample along the way.
            if series.dots || series.terminalDot {
                let fill = series.secondary ? ReportInk.tertiary : ReportInk.primary
                for (index, point) in series.points.enumerated() {
                    let last = index == series.points.count - 1
                    guard series.dots || last else { continue }
                    let r: CGFloat = last ? 2.7 : 2.1
                    sheet.circle(x: xFor(point.atMs), y: yFor(point.value), radius: r, fill)
                }
            }
        }

        if let bounds = spec.bounds {
            let by = top + spec.height
            sheet.textAt(bounds.left, x: plotLeft, top: by, s.bound)
            sheet.textRightAt(bounds.right, right: plotRight, top: by, s.bound)
        }
    }

    private func drawPunctuality(
        _ sheet: inout ReportSheet,
        _ spec: ReportPunctualitySpec,
        x0: CGFloat,
        top: CGFloat
    ) {
        let plotLeft = x0 + ReportGeo.punctGutter
        let plotRight = x0 + spec.width
        let seriesLeft = plotLeft + 3.1
        let seriesRight = plotRight - 3.1

        for anchor in [ReportGeo.punctMid, ReportGeo.punctMax] {
            sheet.rule(from: plotLeft, at: top + anchor, to: plotRight, s.ruleGrid)
        }
        sheet.rule(from: plotLeft, at: top + ReportGeo.punctZero, to: plotRight, s.zeroLine)
        sheet.rule(
            from: plotLeft, at: top + ReportGeo.punctSeparator, to: plotRight, s.missedSeparator)
        sheet.vLine(
            x: plotLeft, from: top + ReportGeo.punctTop,
            to: top + ReportGeo.punctBottom, s.ruleAxis)

        // Each gradation carries the colour of its own band (§5.2), and the word
        // is always there too — no reading depends on the colour alone.
        var labels: [(CGFloat, String, UIColor)] = []
        labels.append((ReportGeo.punctZero, spec.tickLabels.first ?? "", ReportInk.tertiary))
        if spec.tickLabels.count > 1 {
            labels.append((ReportGeo.punctMid, spec.tickLabels[1], ReportInk.secondary))
        }
        if spec.tickLabels.count > 2 {
            labels.append((ReportGeo.punctMax, spec.tickLabels[2], ReportInk.secondary))
        }
        labels.append((ReportGeo.punctMissed, spec.missedLabel, ReportInk.error))
        for (anchor, label, colour) in labels where !label.isEmpty {
            let style = s.axis(colour)
            sheet.textRightAt(
                label, right: plotLeft - ReportGeo.px(5),
                top: top + anchor - style.lineHeight / 2, style)
        }

        let span = Double(max(1, spec.toMs - spec.fromMs))
        let maxDelay = max(1, spec.axis.maxDelayMin)
        for point in spec.points {
            let t = min(1, max(0, Double(point.atMs - spec.fromMs) / span))
            let cx = seriesLeft + (seriesRight - seriesLeft) * CGFloat(t)
            let cy: CGFloat
            if let delta = point.deltaMin {
                // Early doses sit above the zero line, clamped so a single
                // hour-early intake cannot flatten the whole scale.
                let clamped = min(maxDelay, max(-ReportGeo.earlyClampMin, delta))
                cy = top + ReportGeo.punctZero
                    + (CGFloat(clamped) / CGFloat(maxDelay))
                    * (ReportGeo.punctMax - ReportGeo.punctZero)
            } else {
                cy = top + ReportGeo.punctMissed
            }
            let colour: UIColor
            switch Punctuality.timing(point.deltaMin) {
            case .missed: colour = ReportInk.error
            case .late:   colour = ReportInk.secondary
            case .onTime: colour = ReportInk.tertiary
            }
            sheet.circle(x: cx, y: cy, radius: 2.9, colour)
        }
    }
}

/// One A4 sheet being filled, top to bottom.
///
/// When `canvas` is nil nothing is drawn and only the cursor moves: that is the
/// measuring pass. Every primitive is therefore safe with no context.
struct ReportSheet {
    private let canvas: UIGraphicsPDFRendererContext?
    private let totalPages: Int
    private let s: ReportStyles
    private let bannerLeft: String
    private let footerLeft: String

    var y: CGFloat = ReportGeo.bodyTop
    private(set) var pageCount = 0

    init(
        canvas: UIGraphicsPDFRendererContext?,
        totalPages: Int,
        styles: ReportStyles,
        bannerLeft: String,
        footerLeft: String
    ) {
        self.canvas = canvas
        self.totalPages = totalPages
        self.s = styles
        self.bannerLeft = bannerLeft
        self.footerLeft = footerLeft
    }

    private var cg: CGContext? { canvas?.cgContext }

    mutating func newPage() {
        pageCount += 1
        y = ReportGeo.bodyTop
        guard let canvas, let cg else { return }
        canvas.beginPage()
        cg.setFillColor(ReportInk.page.cgColor)
        cg.fill(CGRect(x: 0, y: 0, width: ReportGeo.pageW, height: ReportGeo.pageH))
        let counter = "\(pageCount) / \(totalPages)"
        textAt(bannerLeft, x: ReportGeo.contentL, top: ReportGeo.bannerTop, s.bannerLeft)
        textRightAt(counter, right: ReportGeo.contentR, top: ReportGeo.bannerTop, s.bannerRight)
        rule(
            from: ReportGeo.contentL, at: ReportGeo.bannerRule,
            to: ReportGeo.contentR, s.ruleStrong)
        rule(
            from: ReportGeo.contentL, at: ReportGeo.footerRule,
            to: ReportGeo.contentR, s.ruleHair)
        textAt(footerLeft, x: ReportGeo.contentL, top: ReportGeo.footerTop, s.footer)
        textRightAt(counter, right: ReportGeo.contentR, top: ReportGeo.footerTop, s.footer)
    }

    func fits(_ height: CGFloat) -> Bool { y + height <= ReportGeo.bodyBottom }

    mutating func ensure(_ height: CGFloat) {
        if !fits(height) { newPage() }
    }

    // MARK: - Text
    // `NSString.draw(at:)` takes the top-left of the line box, so nothing here
    // has to do baseline arithmetic.

    func textAt(_ text: String, x: CGFloat, top: CGFloat, _ style: ReportTextStyle) {
        guard cg != nil, !text.isEmpty else { return }
        (text as NSString).draw(at: CGPoint(x: x, y: top), withAttributes: style.attributes)
    }

    func textRightAt(_ text: String, right: CGFloat, top: CGFloat, _ style: ReportTextStyle) {
        textAt(text, x: right - style.width(text), top: top, style)
    }

    /// Draws at the cursor and advances it by one line.
    mutating func textTop(_ text: String, x: CGFloat, _ style: ReportTextStyle) {
        textAt(text, x: x, top: y, style)
        y += style.lineHeight
    }

    // MARK: - Strokes and fills

    private func apply(_ stroke: ReportStroke, _ context: CGContext) {
        context.setStrokeColor(stroke.color.cgColor)
        context.setLineWidth(stroke.width)
        if let dash = stroke.dash { context.setLineDash(phase: 0, lengths: dash) }
        else { context.setLineDash(phase: 0, lengths: []) }
        context.setLineCap(stroke.rounded ? .round : .butt)
        context.setLineJoin(stroke.rounded ? .round : .miter)
    }

    func rule(from x1: CGFloat, at atY: CGFloat, to x2: CGFloat, _ stroke: ReportStroke) {
        guard let cg else { return }
        apply(stroke, cg)
        cg.beginPath()
        cg.move(to: CGPoint(x: x1, y: atY))
        cg.addLine(to: CGPoint(x: x2, y: atY))
        cg.strokePath()
    }

    func vLine(x: CGFloat, from y1: CGFloat, to y2: CGFloat, _ stroke: ReportStroke) {
        guard let cg else { return }
        apply(stroke, cg)
        cg.beginPath()
        cg.move(to: CGPoint(x: x, y: y1))
        cg.addLine(to: CGPoint(x: x, y: y2))
        cg.strokePath()
    }

    func path(_ path: CGPath, _ stroke: ReportStroke) {
        guard let cg else { return }
        apply(stroke, cg)
        cg.beginPath()
        cg.addPath(path)
        cg.strokePath()
    }

    func circle(x: CGFloat, y: CGFloat, radius: CGFloat, _ colour: UIColor) {
        guard let cg else { return }
        cg.setFillColor(colour.cgColor)
        cg.fillEllipse(
            in: CGRect(x: x - radius, y: y - radius, width: radius * 2, height: radius * 2))
    }

    func roundRect(
        left: CGFloat, top: CGFloat, right: CGFloat, bottom: CGFloat, _ stroke: ReportStroke
    ) {
        guard let cg else { return }
        apply(stroke, cg)
        let rect = CGRect(x: left, y: top, width: right - left, height: bottom - top)
        cg.beginPath()
        cg.addPath(UIBezierPath(roundedRect: rect, cornerRadius: ReportGeo.px(4)).cgPath)
        cg.strokePath()
    }

    /// The unticked box of §7.6.3 — square, unfilled, the doctor's to fill in.
    func square(left: CGFloat, top: CGFloat, size: CGFloat, _ stroke: ReportStroke) {
        guard let cg else { return }
        apply(stroke, cg)
        cg.beginPath()
        cg.addRect(CGRect(x: left, y: top, width: size, height: size))
        cg.strokePath()
    }

    func swatch(x: CGFloat, centreY: CGFloat, width: CGFloat, _ stroke: ReportStroke) {
        rule(from: x, at: centreY, to: x + width, stroke)
    }

    func photo(_ tile: ReportPhotoTile, x: CGFloat, top: CGFloat, size: CGFloat) {
        guard cg != nil else { return }
        // 4× the slot: enough for print, small enough that a page of thumbnails
        // does not hold a dozen full-resolution photos in memory at once.
        guard let image = reportThumbnail(tile.bytes, maxPixel: size * 4) else { return }
        let w = image.size.width
        let h = image.size.height
        guard w > 0, h > 0 else { return }
        // Fit inside the cell, centred: a report is not a contact sheet, and
        // cropping someone's body to fill a square is not ours to do.
        let scale = min(size / w, size / h)
        let dw = w * scale
        let dh = h * scale
        image.draw(
            in: CGRect(x: x + (size - dw) / 2, y: top + (size - dh) / 2, width: dw, height: dh))
    }

    // MARK: - Wrapping

    /// Greedy wrap that also breaks a word too long for the line, so nothing can
    /// run past the right margin.
    func wrap(_ text: String, _ style: ReportTextStyle, maxWidth: CGFloat) -> [String] {
        guard !text.isEmpty, maxWidth > 0 else { return [] }
        var out: [String] = []
        for paragraph in text.components(separatedBy: "\n") {
            var line = ""
            for word in paragraph.split(separator: " ", omittingEmptySubsequences: true) {
                let candidate = line.isEmpty ? String(word) : line + " " + word
                if style.width(candidate) <= maxWidth {
                    line = candidate
                    continue
                }
                if !line.isEmpty {
                    out.append(line)
                    line = ""
                }
                var rest = String(word)
                while style.width(rest) > maxWidth && rest.count > 1 {
                    var cut = rest.count
                    while cut > 1 && style.width(String(rest.prefix(cut))) > maxWidth { cut -= 1 }
                    out.append(String(rest.prefix(cut)))
                    rest = String(rest.dropFirst(cut))
                }
                line = rest
            }
            if !line.isEmpty { out.append(line) }
        }
        return out
    }

    /// Wraps `body` knowing that `lead` is printed inline before it, in a heavier
    /// face. The returned first line is the remainder of that line.
    func wrapWithLead(
        lead: String,
        body: String,
        leadStyle: ReportTextStyle,
        bodyStyle: ReportTextStyle,
        maxWidth: CGFloat
    ) -> [String] {
        let offset = leadStyle.width(lead) + bodyStyle.width(" ")
        var width = maxWidth - offset
        var out: [String] = []
        var line = ""
        for word in body.split(separator: " ", omittingEmptySubsequences: true) {
            let candidate = line.isEmpty ? String(word) : line + " " + word
            if bodyStyle.width(candidate) <= width {
                line = candidate
            } else {
                out.append(line)
                line = String(word)
                width = maxWidth
            }
        }
        out.append(line)
        return out
    }
}
