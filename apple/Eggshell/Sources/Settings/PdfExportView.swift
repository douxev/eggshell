import SwiftUI
import TransitionCore
import UIKit

// ===========================================================================
// PUSHED SCREEN — "Export PDF". Parity with the Android PdfExportScreen +
// PdfReportExporter. Pushed via Route.pdfExport, so NO TabScaffold: a plain
// ScrollView/VStack with .navigationTitle.
//
//   • Period selector (30 / 90 / 180 days / all) via ChoiceChip.
//   • Per-section inclusion toggles (Médicaments, Doses, Hormones, Journal,
//     Saignements).
//   • Generates an A4 multi-page PDF with UIGraphicsPDFRenderer: a cover page
//     (title, date, period) then one section per checked category (simple
//     tables). The file is written to AppPaths.cacheDir and offered via
//     ShareLink.
// ===========================================================================

// MARK: - Period

private enum PdfPeriod: Int, CaseIterable, Identifiable {
    case days30, days90, days180, all
    var id: Int { rawValue }

    var label: String {
        switch self {
        case .days30:  return "30 jours"
        case .days90:  return "90 jours"
        case .days180: return "180 jours"
        case .all:     return "Tout"
        }
    }

    /// Cutoff timestamp (ms). `.all` keeps everything.
    func cutoffMs(now: Int64) -> Int64 {
        switch self {
        case .days30:  return now - 30  * 86_400_000
        case .days90:  return now - 90  * 86_400_000
        case .days180: return now - 180 * 86_400_000
        case .all:     return 0
        }
    }
}

// MARK: - Inclusion options

private struct PdfOptions {
    var medications = true
    var doses = true
    var hormones = true
    var journal = true
    var bleeding = true

    var anySelected: Bool { medications || doses || hormones || journal || bleeding }
}

// MARK: - ViewModel

@MainActor
final class PdfExportViewModel: ObservableObject {
    @Published var generating = false
    @Published var error: String?

    /// Loads the requested data and renders the PDF, returning the on-disk URL.
    fileprivate func generate(
        _ session: VaultService,
        options: PdfOptions,
        period: PdfPeriod,
        unitFor: @escaping (String) -> String?
    ) async -> URL? {
        generating = true
        error = nil
        defer { generating = false }

        do {
            let now = Time.nowMs()
            let cutoff = period.cutoffMs(now: now)

            // --- Gather data -------------------------------------------------
            var meds: [Medication] = []
            var doses: [DoseEvent] = []
            var hormoneSeries: [(hormone: String, points: [HormonePoint])] = []
            var journal: [JournalEntry] = []
            var bleeding: [BleedingEntry] = []

            // Medication lookup is useful for dose rows too.
            if options.medications || options.doses {
                meds = try await session.listMedications()
            }
            let medsById = Dictionary(uniqueKeysWithValues: meds.map { ($0.id, $0) })

            if options.doses {
                doses = try await session
                    .listDoseEventsBetween(fromMs: cutoff, toMs: now)
                    .sorted { $0.takenAtMs > $1.takenAtMs }
            }

            if options.hormones {
                let distinct = try await session.distinctHormones()
                    .filter { $0 != HormoneCatalog.weight }
                for hormone in distinct {
                    let raw = try await session
                        .listHormoneMeasurements(hormone: hormone)
                        .filter { $0.atMs >= cutoff }
                        .sorted { $0.atMs < $1.atMs }
                    guard !raw.isEmpty else { continue }
                    let target = unitFor(hormone)
                    let points = raw.map { m -> HormonePoint in
                        let display: Double
                        let unit: String
                        if let t = target, t != m.unit,
                           let conv = convertHormoneValue(value: m.value, fromUnit: m.unit, toUnit: t, hormone: hormone) {
                            display = conv
                            unit = t
                        } else {
                            display = m.value
                            unit = m.unit
                        }
                        return HormonePoint(at: m.atMs, value: display, unit: unit, rawValue: m.value, rawUnit: m.unit)
                    }
                    hormoneSeries.append((hormone, points))
                }
            }

            if options.journal {
                journal = try await session.listJournalEntries()
                    .filter { $0.atMs >= cutoff }
                    .sorted { $0.atMs > $1.atMs }
            }

            if options.bleeding {
                bleeding = try await session.listBleedingEntries()
                    .filter { $0.atMs >= cutoff }
                    .sorted { $0.atMs > $1.atMs }
            }

            // --- Render off the main thread ----------------------------------
            let data = await Task.detached(priority: .userInitiated) {
                PdfReportRenderer.render(
                    period: period,
                    options: options,
                    meds: meds,
                    medsById: medsById,
                    doses: doses,
                    hormoneSeries: hormoneSeries,
                    journal: journal,
                    bleeding: bleeding)
            }.value

            let url = AppPaths.cacheDir
                .appendingPathComponent("bilan-transition-\(now).pdf")
            try data.write(to: url, options: .atomic)
            return url
        } catch {
            self.error = describe(error)
            return nil
        }
    }
}

// A single converted hormone reading, ready to print.
private struct HormonePoint {
    let at: Int64
    let value: Double
    let unit: String
    let rawValue: Double
    let rawUnit: String
}

// MARK: - View

struct PdfExportView: View {
    @EnvironmentObject private var app: AppState
    @EnvironmentObject private var hormoneUnits: HormoneUnitStore
    @Environment(\.palette) private var palette
    @StateObject private var vm = PdfExportViewModel()

    @State private var period: PdfPeriod = .days90
    @State private var options = PdfOptions()
    @State private var generatedURL: URL?

    var body: some View {
        ScrollView {
            VStack(spacing: Spacing.l) {
                introCard
                periodSection
                includeSection
                generateSection
                if let e = vm.error { ErrorBanner(message: e) }
            }
            .padding(Spacing.l)
        }
        .navigationTitle("Export PDF")
    }

    // Intro
    private var introCard: some View {
        SectionCard {
            HStack(alignment: .top, spacing: Spacing.m) {
                Image(systemName: "doc.richtext")
                    .font(.title2)
                    .foregroundStyle(palette.tertiary)
                    .frame(width: 44, height: 44)
                    .background(palette.tertiaryContainer, in: RoundedRectangle(cornerRadius: Corner.medium))
                VStack(alignment: .leading, spacing: 2) {
                    Text("Bilan partageable")
                        .font(.eggHeadline).foregroundStyle(palette.onSurface)
                    Text("Génère un récapitulatif PDF de ton suivi. Tout est créé localement : aucune donnée ne quitte l'appareil.")
                        .font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.6))
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
    }

    // Period
    private var periodSection: some View {
        SectionCard {
            Text("Période").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
                .frame(maxWidth: .infinity, alignment: .leading)
            HStack(spacing: Spacing.s) {
                ForEach(PdfPeriod.allCases) { p in
                    ChoiceChip(label: p.label, selected: period == p) { period = p }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    // Inclusion toggles
    private var includeSection: some View {
        SectionCard {
            Text("Sections à inclure").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
                .frame(maxWidth: .infinity, alignment: .leading)
            includeRow("Traitements", "Doses et posologies", "pills", $options.medications)
            includeRow("Doses", "Prises enregistrées sur la période", "syringe", $options.doses)
            includeRow("Hormones", "Taux mesurés en laboratoire", "chart.line.uptrend.xyaxis", $options.hormones)
            includeRow("Journal", "Ressentis et effets", "square.and.pencil", $options.journal)
            includeRow("Menstruations", "Suivi des règles", "drop", $options.bleeding)
        }
    }

    private func includeRow(_ title: String, _ sub: String, _ icon: String, _ value: Binding<Bool>) -> some View {
        Toggle(isOn: value) {
            HStack(spacing: Spacing.m) {
                Image(systemName: icon)
                    .foregroundStyle(value.wrappedValue ? palette.primary : palette.onSurface.opacity(0.5))
                    .frame(width: 30)
                VStack(alignment: .leading, spacing: 1) {
                    Text(title).font(.eggCallout).foregroundStyle(palette.onSurface)
                    Text(sub).font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.6))
                }
            }
        }
        .tint(palette.primary)
    }

    // Generate + share
    private var generateSection: some View {
        SectionCard {
            Button {
                guard let session = app.session else { return }
                let opts = options
                let p = period
                generatedURL = nil
                Task {
                    let url = await vm.generate(
                        session,
                        options: opts,
                        period: p,
                        unitFor: { hormoneUnits.effectiveUnit(for: $0) })
                    generatedURL = url
                }
            } label: {
                if vm.generating {
                    HStack(spacing: Spacing.s) {
                        ProgressView().tint(palette.onPrimary)
                        Text("Génération…")
                    }
                    .frame(maxWidth: .infinity)
                } else {
                    Label("Générer le PDF", systemImage: "doc.badge.plus")
                        .frame(maxWidth: .infinity)
                }
            }
            .glassProminentButton().tint(palette.primary)
            .disabled(vm.generating || app.session == nil || !options.anySelected)

            if let url = generatedURL {
                ShareLink(item: url) {
                    Label("Partager le PDF", systemImage: "square.and.arrow.up")
                        .frame(maxWidth: .infinity)
                }
                .glassButton().tint(palette.primary)
            }
        }
    }
}

// MARK: - Renderer (pure, no SwiftUI / no actor isolation)

private enum PdfReportRenderer {
    // A4 @ 72 dpi.
    static let pageW: CGFloat = 595
    static let pageH: CGFloat = 842
    static let marginX: CGFloat = 42
    static let marginTop: CGFloat = 56
    static let marginBottom: CGFloat = 56

    // Lavender-leaning palette mirroring the app theme (rendering only).
    static let primary = UIColor(red: 0x6A / 255, green: 0x4F / 255, blue: 0xA3 / 255, alpha: 1)
    static let onSurface = UIColor(red: 0x1D / 255, green: 0x1B / 255, blue: 0x20 / 255, alpha: 1)
    static let onSurfaceVariant = UIColor(red: 0x49 / 255, green: 0x45 / 255, blue: 0x4F / 255, alpha: 1)
    static let ruleFaint = UIColor(red: 0xEB / 255, green: 0xE0 / 255, blue: 0xEB / 255, alpha: 1)

    static func render(
        period: PdfPeriod,
        options: PdfOptions,
        meds: [Medication],
        medsById: [Int64: Medication],
        doses: [DoseEvent],
        hormoneSeries: [(hormone: String, points: [HormonePoint])],
        journal: [JournalEntry],
        bleeding: [BleedingEntry]
    ) -> Data {
        let format = UIGraphicsPDFRendererFormat()
        let bounds = CGRect(x: 0, y: 0, width: pageW, height: pageH)
        let renderer = UIGraphicsPDFRenderer(bounds: bounds, format: format)

        return renderer.pdfData { ctx in
            var cursor = Cursor(ctx: ctx)
            cursor.beginPage(accent: false)
            drawCover(&cursor, period: period)

            if options.medications {
                cursor.section("Traitements", count: meds.count)
                if meds.isEmpty {
                    cursor.muted("Aucun traitement enregistré.")
                } else {
                    for m in meds { medRow(&cursor, m) }
                }
            }

            if options.doses {
                cursor.section("Doses", count: doses.count)
                if doses.isEmpty {
                    cursor.muted("Aucune prise sur la période.")
                } else {
                    for d in doses.prefix(120) { doseRow(&cursor, d, medsById: medsById) }
                }
            }

            if options.hormones {
                cursor.section("Hormones", count: hormoneSeries.count)
                if hormoneSeries.isEmpty {
                    cursor.muted("Aucune mesure hormonale.")
                } else {
                    for s in hormoneSeries { hormoneBlock(&cursor, hormone: s.hormone, points: s.points) }
                }
            }

            if options.journal {
                cursor.section("Journal", count: journal.count)
                if journal.isEmpty {
                    cursor.muted("Aucune entrée de journal sur la période.")
                } else {
                    for e in journal.prefix(60) { journalRow(&cursor, e) }
                    if journal.count > 60 {
                        // Never truncate silently in a medical document.
                        cursor.muted("… et \(journal.count - 60) autres entrées sur la période (60 plus récentes affichées).")
                    }
                }
            }

            if options.bleeding {
                cursor.section("Menstruations", count: bleeding.count)
                if bleeding.isEmpty {
                    cursor.muted("Aucune entrée enregistrée sur la période.")
                } else {
                    for b in bleeding.prefix(120) { bleedingRow(&cursor, b) }
                }
            }

            cursor.footer()
        }
    }

    // --- Sections ---------------------------------------------------------

    private static func drawCover(_ c: inout Cursor, period: PdfPeriod) {
        c.text("Bilan", font: serif(28, .bold), color: onSurface)
        c.advance(6)
        let now = Date()
        c.text("Édition du \(longDate.string(from: now))", font: sans(11), color: onSurfaceVariant)
        c.advance(2)
        c.text("Période : \(period.label)", font: sans(11), color: onSurfaceVariant)
        c.advance(12)
        c.rule()
        c.advance(8)
    }

    private static func medRow(_ c: inout Cursor, _ m: Medication) {
        c.ensure(34)
        c.text(m.name, font: sans(11, .bold), color: onSurface)
        var detail = MedCatalog.kindLabel(m.kind) + " · " + MedCatalog.routeLabel(m.route)
        if let dose = m.defaultDose {
            detail += " · " + trim(dose) + " " + (m.defaultDoseUnit ?? "")
        }
        c.advance(13)
        c.text(detail, font: sans(9.5), color: onSurfaceVariant)
        c.advance(10)
        c.divider()
    }

    private static func doseRow(_ c: inout Cursor, _ d: DoseEvent, medsById: [Int64: Medication]) {
        c.ensure(20)
        let name = medsById[d.medicationId]?.name ?? "Traitement"
        var right = ""
        if let dose = d.dose { right = trim(dose) + " " + (d.doseUnit ?? "") }
        if let site = d.injectionSite, !site.isEmpty {
            right += (right.isEmpty ? "" : " · ") + MedCatalog.injectionSiteLabel(site)
        }
        c.row(left: dateTime.string(from: date(d.takenAtMs)) + " — " + name, right: right)
        c.divider()
    }

    private static func hormoneBlock(_ c: inout Cursor, hormone: String, points: [HormonePoint]) {
        c.ensure(40)
        let title = HormoneCatalog.kindLabel(hormone)
        var headerRight = ""
        if let last = points.last { headerRight = trim(last.value) + " " + last.unit }
        c.row(left: title, right: headerRight, bold: true)
        c.advance(4)
        for p in points.suffix(8).reversed() {
            c.ensure(16)
            var value = trim(p.value) + " " + p.unit
            if p.unit != p.rawUnit {
                value += " (" + trim(p.rawValue) + " " + p.rawUnit + ")"
            }
            c.row(left: dateOnly.string(from: date(p.at)), right: value, small: true)
        }
        c.advance(4)
        c.divider()
    }

    private static func journalRow(_ c: inout Cursor, _ e: JournalEntry) {
        let gauges: [String] = [
            e.mood.map { "Humeur \($0)" },
            e.dysphoria.map { "Dysphorie \($0)" },
            e.euphoria.map { "Euphorie \($0)" },
            e.libido.map { "Libido \($0)" },
            e.energy.map { "Énergie \($0)" },
        ].compactMap { $0 }

        c.ensure(28)
        c.text(dateTime.string(from: date(e.atMs)), font: sans(10.5, .bold), color: onSurface)
        c.advance(12)
        if !gauges.isEmpty {
            c.text(gauges.joined(separator: " · "), font: sans(9.5), color: onSurfaceVariant)
            c.advance(11)
        }
        if let free = e.freeText, !free.isEmpty {
            c.wrapped(free, font: sans(10), color: onSurface, indent: 8)
        }
        if let side = e.sideEffects, !side.isEmpty {
            c.wrapped("Effets : " + side, font: sans(9.5), color: onSurfaceVariant, indent: 8)
        }
        c.advance(4)
        c.divider()
    }

    private static func bleedingRow(_ c: inout Cursor, _ b: BleedingEntry) {
        c.ensure(20)
        var label = "Règles"
        if let spotting = b.isSpotting { label = spotting ? "Léger (spotting)" : "Règles" }
        let detail = (b.freeText?.isEmpty == false) ? (b.freeText ?? "") : ""
        c.row(left: dateTime.string(from: date(b.atMs)) + " — " + label, right: detail)
        c.divider()
    }

    // --- Formatting helpers ----------------------------------------------

    private static func date(_ ms: Int64) -> Date { Date(timeIntervalSince1970: Double(ms) / 1000) }

    private static let longDate: DateFormatter = {
        let f = DateFormatter(); f.locale = Locale(identifier: "fr_FR"); f.dateFormat = "d MMMM yyyy"; return f
    }()
    private static let dateOnly: DateFormatter = {
        let f = DateFormatter(); f.locale = Locale(identifier: "fr_FR"); f.dateFormat = "d MMM yy"; return f
    }()
    private static let dateTime: DateFormatter = {
        let f = DateFormatter(); f.locale = Locale(identifier: "fr_FR"); f.dateFormat = "d MMM yy · HH:mm"; return f
    }()

    private static func sans(_ size: CGFloat, _ weight: UIFont.Weight = .regular) -> UIFont {
        UIFont.systemFont(ofSize: size, weight: weight)
    }
    private static func serif(_ size: CGFloat, _ weight: UIFont.Weight) -> UIFont {
        let base = UIFont.systemFont(ofSize: size, weight: weight)
        if let desc = base.fontDescriptor.withDesign(.serif) {
            return UIFont(descriptor: desc, size: size)
        }
        return base
    }

    static func trim(_ v: Double) -> String {
        let rounded = (v * 100).rounded() / 100
        if rounded == rounded.rounded() { return String(Int(rounded)) }
        return String(format: "%g", rounded)
    }

    // A mutable drawing cursor that handles pagination + layout.
    fileprivate struct Cursor {
        let ctx: UIGraphicsPDFRendererContext
        var y: CGFloat = PdfReportRenderer.marginTop
        var pageNumber = 0

        mutating func beginPage(accent: Bool = true) {
            ctx.beginPage()
            pageNumber += 1
            y = PdfReportRenderer.marginTop
            // Accent bar at the top.
            let bar = CGRect(x: 0, y: 0, width: PdfReportRenderer.pageW, height: 6)
            PdfReportRenderer.primary.setFill()
            ctx.cgContext.fill(bar)
            // Page number, right-aligned.
            let label = "page \(pageNumber)"
            let attrs: [NSAttributedString.Key: Any] = [
                .font: PdfReportRenderer.sans(8),
                .foregroundColor: PdfReportRenderer.onSurfaceVariant,
            ]
            let size = (label as NSString).size(withAttributes: attrs)
            (label as NSString).draw(
                at: CGPoint(x: PdfReportRenderer.pageW - PdfReportRenderer.marginX - size.width, y: 24),
                withAttributes: attrs)
            y = PdfReportRenderer.marginTop + 6
        }

        mutating func ensure(_ needed: CGFloat) {
            if y + needed > PdfReportRenderer.pageH - PdfReportRenderer.marginBottom {
                beginPage()
            }
        }

        mutating func advance(_ dy: CGFloat) { y += dy }

        mutating func text(_ s: String, font: UIFont, color: UIColor) {
            ensure(font.lineHeight + 2)
            let attrs: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: color]
            (s as NSString).draw(at: CGPoint(x: PdfReportRenderer.marginX, y: y), withAttributes: attrs)
            y += font.lineHeight
        }

        mutating func muted(_ s: String) {
            text(s, font: PdfReportRenderer.sans(10), color: PdfReportRenderer.onSurfaceVariant)
            advance(4)
        }

        mutating func section(_ title: String, count: Int) {
            ensure(48)
            advance(16)
            let circleR: CGFloat = 11
            let cx = PdfReportRenderer.marginX + circleR
            let cy = y + circleR
            PdfReportRenderer.primary.setFill()
            ctx.cgContext.fillEllipse(in: CGRect(x: PdfReportRenderer.marginX, y: y, width: circleR * 2, height: circleR * 2))
            // Count inside the circle.
            let countStr = String(count)
            let cAttrs: [NSAttributedString.Key: Any] = [
                .font: PdfReportRenderer.sans(10, .bold),
                .foregroundColor: UIColor.white,
            ]
            let cSize = (countStr as NSString).size(withAttributes: cAttrs)
            (countStr as NSString).draw(
                at: CGPoint(x: cx - cSize.width / 2, y: cy - cSize.height / 2),
                withAttributes: cAttrs)
            // Title.
            let tAttrs: [NSAttributedString.Key: Any] = [
                .font: PdfReportRenderer.sans(15, .bold),
                .foregroundColor: PdfReportRenderer.primary,
            ]
            (title as NSString).draw(
                at: CGPoint(x: PdfReportRenderer.marginX + circleR * 2 + 10, y: y + 2),
                withAttributes: tAttrs)
            y += circleR * 2 + 6
            rule()
            advance(8)
        }

        mutating func row(left: String, right: String, bold: Bool = false, small: Bool = false) {
            let size: CGFloat = small ? 9.5 : 10.5
            let leftFont = PdfReportRenderer.sans(size, bold ? .bold : .regular)
            let rightFont = PdfReportRenderer.sans(size, bold ? .bold : .regular)
            ensure(leftFont.lineHeight + 4)
            let leftColor = small ? PdfReportRenderer.onSurfaceVariant : PdfReportRenderer.onSurface
            (left as NSString).draw(
                at: CGPoint(x: PdfReportRenderer.marginX, y: y),
                withAttributes: [.font: leftFont, .foregroundColor: leftColor])
            if !right.isEmpty {
                let rAttrs: [NSAttributedString.Key: Any] = [
                    .font: rightFont, .foregroundColor: PdfReportRenderer.onSurface,
                ]
                let rSize = (right as NSString).size(withAttributes: rAttrs)
                (right as NSString).draw(
                    at: CGPoint(x: PdfReportRenderer.pageW - PdfReportRenderer.marginX - rSize.width, y: y),
                    withAttributes: rAttrs)
            }
            y += leftFont.lineHeight + 2
        }

        mutating func wrapped(_ s: String, font: UIFont, color: UIColor, indent: CGFloat) {
            let maxWidth = PdfReportRenderer.pageW - 2 * PdfReportRenderer.marginX - indent
            let attrs: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: color]
            var line = ""
            for word in s.split(separator: " ", omittingEmptySubsequences: true) {
                let candidate = line.isEmpty ? String(word) : line + " " + word
                if (candidate as NSString).size(withAttributes: attrs).width <= maxWidth {
                    line = candidate
                } else {
                    flushLine(line, attrs: attrs, indent: indent, lineHeight: font.lineHeight)
                    line = String(word)
                }
            }
            if !line.isEmpty { flushLine(line, attrs: attrs, indent: indent, lineHeight: font.lineHeight) }
        }

        private mutating func flushLine(_ line: String, attrs: [NSAttributedString.Key: Any], indent: CGFloat, lineHeight: CGFloat) {
            ensure(lineHeight + 2)
            (line as NSString).draw(
                at: CGPoint(x: PdfReportRenderer.marginX + indent, y: y),
                withAttributes: attrs)
            y += lineHeight
        }

        mutating func rule() {
            let cg = ctx.cgContext
            cg.setStrokeColor(PdfReportRenderer.ruleFaint.cgColor)
            cg.setLineWidth(1)
            cg.move(to: CGPoint(x: PdfReportRenderer.marginX, y: y))
            cg.addLine(to: CGPoint(x: PdfReportRenderer.pageW - PdfReportRenderer.marginX, y: y))
            cg.strokePath()
        }

        mutating func divider() {
            y += 4
            rule()
            y += 6
        }

        mutating func footer() {
            let cg = ctx.cgContext
            let attrs: [NSAttributedString.Key: Any] = [
                .font: PdfReportRenderer.sans(8.5),
                .foregroundColor: PdfReportRenderer.onSurfaceVariant,
            ]
            let text = "Généré localement par Eggshell · aucune donnée n'a quitté l'appareil."
            (text as NSString).draw(
                at: CGPoint(x: PdfReportRenderer.marginX, y: PdfReportRenderer.pageH - PdfReportRenderer.marginBottom + 24),
                withAttributes: attrs)
        }
    }
}
