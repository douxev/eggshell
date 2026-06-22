import SwiftUI
import TransitionCore
import PhotosUI
import Vision
import PDFKit
import UniformTypeIdentifiers
import UIKit

// ===========================================================================
// PUSHED screen — real OCR import pipeline. Mirrors ImportLabResultScreen.kt.
//
// Flow:
//   1. Idle: pick an image (PhotosPicker) or a PDF (.fileImporter).
//   2. Processing: rasterise (PDF → page images via PDFKit) and run Vision's
//      VNRecognizeTextRequest (languages fr + en) on each image.
//   3. Preview: LabResultParser.parse() the recognised text → a list of
//      detected measurements (hormone / value / unit) with checkboxes, plus
//      an editable draw date.
//   4. Save: addHormoneMeasurement for each checked row, then Done.
// ===========================================================================

@MainActor
final class ImportLabResultViewModel: ObservableObject {

    enum Phase: Equatable {
        case idle
        case processing
        /// The picked PDF is encrypted; we need a password to continue.
        case passwordRequired(wrongPassword: Bool)
        case preview
        case done(saved: Int)
        case error(String)
    }

    /// A parsed hormone row in the preview list; the user can untick it.
    struct EditableEntry: Identifiable {
        let id = UUID()
        let hormone: String
        let value: Double
        let unit: String
        var selected: Bool
    }

    @Published var phase: Phase = .idle
    @Published var entries: [EditableEntry] = []
    @Published var date: Date = Date()
    @Published var dateAutoDetected = false

    /// Bytes of an encrypted PDF held between the lock detection and the
    /// password retry, so we don't re-touch the security-scoped URL.
    private var pendingPDFData: Data?

    var selectedCount: Int { entries.filter { $0.selected }.count }

    /// OCR an image's raw bytes, parse, and move to the preview phase.
    func processImageData(_ data: Data) async {
        phase = .processing
        guard let image = UIImage(data: data), let cg = image.cgImage else {
            phase = .error("Image illisible.")
            return
        }
        let text = await Self.recognizeText(from: [cg])
        finishProcessing(text)
    }

    /// OCR a PDF (each page rasterised), parse, and move to the preview phase.
    /// Reads the bytes once inside the security scope; if the PDF is encrypted we
    /// stash them and prompt for a password instead of failing.
    func processPDF(at url: URL) async {
        phase = .processing
        let accessed = url.startAccessingSecurityScopedResource()
        defer { if accessed { url.stopAccessingSecurityScopedResource() } }
        guard let data = try? Data(contentsOf: url), let doc = PDFDocument(data: data) else {
            phase = .error("PDF illisible.")
            return
        }
        if doc.isLocked {
            pendingPDFData = data
            phase = .passwordRequired(wrongPassword: false)
            return
        }
        await rasterizeAndRecognize(doc)
    }

    /// Retry an encrypted PDF with the password the user just entered.
    func submitPassword(_ password: String) async {
        guard !password.isEmpty, let data = pendingPDFData, let doc = PDFDocument(data: data) else { return }
        phase = .processing
        if doc.unlock(withPassword: password) {
            pendingPDFData = nil
            await rasterizeAndRecognize(doc)
        } else {
            phase = .passwordRequired(wrongPassword: true)
        }
    }

    private func rasterizeAndRecognize(_ doc: PDFDocument) async {
        var images: [CGImage] = []
        let pageCount = min(doc.pageCount, 50)
        for i in 0..<pageCount {
            guard let page = doc.page(at: i) else { continue }
            if let cg = Self.render(page: page) { images.append(cg) }
        }
        if images.isEmpty {
            phase = .error("Aucune page exploitable dans le PDF.")
            return
        }
        let text = await Self.recognizeText(from: images)
        finishProcessing(text)
    }

    private func finishProcessing(_ text: String) {
        let parsed = LabResultParser.parse(text)
        entries = parsed.map { EditableEntry(hormone: $0.hormone, value: $0.value, unit: $0.unit, selected: true) }
        if let detectedMs = parsed.first(where: { $0.atMs != nil })?.atMs {
            date = Date(timeIntervalSince1970: Double(detectedMs) / 1000.0)
            dateAutoDetected = true
        } else {
            date = Date()
            dateAutoDetected = false
        }
        phase = .preview
    }

    func toggle(_ id: UUID) {
        guard let idx = entries.firstIndex(where: { $0.id == id }) else { return }
        entries[idx].selected.toggle()
    }

    /// Once the user edits the date, drop the "auto-detected" badge.
    func dateChangedManually() { dateAutoDetected = false }

    func save(session: VaultService) async {
        let chosen = entries.filter { $0.selected }
        guard !chosen.isEmpty else { return }
        phase = .processing
        let atMs = Int64(date.timeIntervalSince1970 * 1000)
        var saved = 0
        for e in chosen {
            do {
                _ = try await session.addHormoneMeasurement(NewHormoneMeasurement(
                    atMs: atMs,
                    hormone: e.hormone,
                    value: e.value,
                    unit: e.unit,
                    labName: nil,
                    notes: nil))
                saved += 1
            } catch {
                // Keep going; a single failed row shouldn't abort the batch.
            }
        }
        phase = .done(saved: saved)
    }

    func reset() {
        entries = []
        dateAutoDetected = false
        pendingPDFData = nil
        phase = .idle
    }

    // MARK: - Vision OCR (nonisolated background work)

    private nonisolated static func recognizeText(from images: [CGImage]) async -> String {
        await withCheckedContinuation { continuation in
            DispatchQueue.global(qos: .userInitiated).async {
                var transcript: [String] = []
                for cg in images {
                    let request = VNRecognizeTextRequest()
                    request.recognitionLevel = .accurate
                    request.usesLanguageCorrection = true
                    request.recognitionLanguages = ["fr-FR", "en-US"]
                    let handler = VNImageRequestHandler(cgImage: cg, options: [:])
                    do {
                        try handler.perform([request])
                        let observations = request.results ?? []
                        let lines = observations.compactMap { $0.topCandidates(1).first?.string }
                        if !lines.isEmpty { transcript.append(lines.joined(separator: "\n")) }
                    } catch {
                        // Skip a page that Vision can't process.
                    }
                }
                continuation.resume(returning: transcript.joined(separator: "\n\n"))
            }
        }
    }

    private nonisolated static func render(page: PDFPage) -> CGImage? {
        let bounds = page.bounds(for: .mediaBox)
        guard bounds.width > 0, bounds.height > 0 else { return nil }
        // Cap the long side so a multi-page A4 report stays well under memory.
        let maxSide: CGFloat = 1500
        let longest = max(bounds.width, bounds.height)
        let scale = min(3.0, max(1.0, maxSide / longest))
        let pixelW = Int(bounds.width * scale)
        let pixelH = Int(bounds.height * scale)
        guard pixelW > 0, pixelH > 0 else { return nil }

        let colorSpace = CGColorSpaceCreateDeviceRGB()
        guard let ctx = CGContext(
            data: nil,
            width: pixelW,
            height: pixelH,
            bitsPerComponent: 8,
            bytesPerRow: 0,
            space: colorSpace,
            bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
        ) else { return nil }

        ctx.setFillColor(red: 1, green: 1, blue: 1, alpha: 1)
        ctx.fill(CGRect(x: 0, y: 0, width: pixelW, height: pixelH))
        ctx.scaleBy(x: scale, y: scale)
        ctx.translateBy(x: -bounds.origin.x, y: -bounds.origin.y)
        page.draw(with: .mediaBox, to: ctx)
        return ctx.makeImage()
    }
}

struct ImportLabResultView: View {
    @EnvironmentObject private var app: AppState
    @Environment(\.palette) private var palette
    @Environment(\.dismiss) private var dismiss
    @StateObject private var vm = ImportLabResultViewModel()

    @State private var pickerItem: PhotosPickerItem?
    @State private var showPDFImporter = false
    @State private var pdfPassword = ""

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Spacing.l) {
                switch vm.phase {
                case .idle:                    idleStep
                case .processing:              processingStep
                case .passwordRequired(let w): passwordStep(wrongPassword: w)
                case .preview:                 previewStep
                case .done(let saved):         doneStep(saved: saved)
                case .error(let reason):       errorStep(reason)
                }
            }
            .padding(Spacing.l)
        }
        .navigationTitle("Importer")
        .onChange(of: pickerItem) { _, newItem in
            guard let newItem else { return }
            Task {
                if let data = try? await newItem.loadTransferable(type: Data.self) {
                    await vm.processImageData(data)
                }
                pickerItem = nil
            }
        }
        .fileImporter(isPresented: $showPDFImporter, allowedContentTypes: [.pdf]) { result in
            switch result {
            case .success(let url):
                Task { await vm.processPDF(at: url) }
            case .failure:
                break
            }
        }
        // Clear the password field whenever we leave the password step, so a
        // second encrypted PDF in the same session doesn't start pre-filled with
        // the previous one's password.
        .onChange(of: vm.phase) { _, newPhase in
            if case .passwordRequired = newPhase {} else { pdfPassword = "" }
        }
    }

    // MARK: - Idle

    private var idleStep: some View {
        VStack(alignment: .leading, spacing: Spacing.l) {
            SectionCard {
                HStack(spacing: Spacing.m) {
                    Image(systemName: "doc.text.viewfinder")
                        .font(.system(size: 30))
                        .foregroundStyle(palette.primary)
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Importer un résultat de labo").font(.eggHeadline).foregroundStyle(palette.onSurface)
                        Text("Photographie ou choisis un PDF de ton bilan : les valeurs détectées seront proposées avant d'être enregistrées.")
                            .font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.6))
                    }
                }
            }

            Text("Formats pris en charge : image (JPEG/PNG) et PDF.")
                .font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.6))

            PhotosPicker(selection: $pickerItem, matching: .images) {
                Label("Choisir une image", systemImage: "photo").font(.eggHeadline).frame(maxWidth: .infinity)
            }
            .glassProminentButton().tint(palette.primary)

            Button {
                showPDFImporter = true
            } label: {
                Label("Choisir un PDF", systemImage: "doc").font(.eggHeadline).frame(maxWidth: .infinity)
            }
            .glassButton().tint(palette.secondary)
        }
    }

    // MARK: - Processing

    private var processingStep: some View {
        VStack(spacing: Spacing.m) {
            ProgressView().tint(palette.primary)
            Text("Analyse du document…").font(.eggCallout).foregroundStyle(palette.onSurface.opacity(0.6))
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, Spacing.xxl)
    }

    // MARK: - Password (encrypted PDF)

    private func passwordStep(wrongPassword: Bool) -> some View {
        VStack(alignment: .leading, spacing: Spacing.l) {
            SectionCard {
                Text("PDF protégé par mot de passe").font(.eggHeadline).foregroundStyle(palette.onSurface)
                Text("Beaucoup de labos verrouillent leurs PDF. Saisis le mot de passe fourni par le laboratoire pour le déverrouiller. Il n'est jamais enregistré.")
                    .font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.6))
                SecureField("Mot de passe du PDF", text: $pdfPassword)
                    .textFieldStyle(.roundedBorder)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                if wrongPassword {
                    Text("Mot de passe incorrect. Réessaie.")
                        .font(.eggCaption).foregroundStyle(palette.error)
                }
            }
            Button {
                let pw = pdfPassword
                Task { await vm.submitPassword(pw) }
            } label: {
                Label("Déverrouiller", systemImage: "lock.open").font(.eggHeadline).frame(maxWidth: .infinity)
            }
            .glassProminentButton().tint(palette.primary)
            .disabled(pdfPassword.isEmpty)

            Button("Annuler") { pdfPassword = ""; vm.reset() }
                .glassButton().tint(palette.secondary)
        }
    }

    // MARK: - Preview

    @ViewBuilder
    private var previewStep: some View {
        if vm.entries.isEmpty {
            EmptyStateCard(
                text: "Aucune mesure reconnue. Tu peux réessayer avec une image plus nette ou saisir la valeur à la main.",
                systemImage: "questionmark.circle")
            Button("Réessayer") { vm.reset() }
                .glassProminentButton().tint(palette.primary)
        } else {
            Text("Vérifie les mesures").font(.eggHeadline).foregroundStyle(palette.onSurface)
            Text("Décoche celles que le scanner a mal détectées.")
                .font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.6))

            SectionCard {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Date du prélèvement").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
                        Text(vm.dateAutoDetected ? "Détectée automatiquement" : "Saisie manuelle")
                            .font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.5))
                    }
                    Spacer()
                    DatePicker("", selection: Binding(
                        get: { vm.date },
                        set: { vm.date = $0; vm.dateChangedManually() }
                    ), displayedComponents: [.date])
                    .labelsHidden()
                    .tint(palette.primary)
                }
            }

            ForEach(vm.entries) { entry in
                Button {
                    vm.toggle(entry.id)
                } label: {
                    HStack(spacing: Spacing.m) {
                        Image(systemName: entry.selected ? "checkmark.circle.fill" : "circle")
                            .font(.title3)
                            .foregroundStyle(entry.selected ? palette.primary : palette.onSurface.opacity(0.3))
                        Text(HormoneCatalog.kindLabel(entry.hormone))
                            .font(.eggCallout).foregroundStyle(palette.onSurface)
                        Spacer()
                        Text("\(formatValue(entry.value)) \(entry.unit)")
                            .font(.eggHeadline).foregroundStyle(palette.primary)
                    }
                    .padding(Spacing.l)
                    .frame(maxWidth: .infinity)
                    .glassCard(cornerRadius: Corner.large)
                }
                .buttonStyle(.plain)
            }

            Button {
                if let s = app.session { Task { await vm.save(session: s) } }
            } label: {
                Text(vm.selectedCount == 1 ? "Enregistrer 1 mesure" : "Enregistrer \(vm.selectedCount) mesures")
                    .font(.eggHeadline).frame(maxWidth: .infinity)
            }
            .glassProminentButton().tint(palette.primary)
            .disabled(vm.selectedCount == 0)

            Button("Recommencer") { vm.reset() }
                .glassButton().tint(palette.secondary)
        }
    }

    // MARK: - Done

    private func doneStep(saved: Int) -> some View {
        VStack(spacing: Spacing.l) {
            SectionCard {
                Text("Importation terminée").font(.eggHeadline).foregroundStyle(palette.onSurface)
                Text(saved == 1 ? "1 mesure ajoutée." : "\(saved) mesures ajoutées.")
                    .font(.eggCallout).foregroundStyle(palette.onSurface.opacity(0.7))
            }
            Button("Fermer") { dismiss() }
                .glassProminentButton().tint(palette.primary)
        }
    }

    // MARK: - Error

    private func errorStep(_ reason: String) -> some View {
        VStack(spacing: Spacing.l) {
            EmptyStateCard(text: reason, systemImage: "exclamationmark.triangle")
            Button("Réessayer") { vm.reset() }
                .glassProminentButton().tint(palette.primary)
        }
    }

    private func formatValue(_ v: Double) -> String {
        if v == v.rounded() { return String(format: "%.0f", v) }
        return String(format: "%.2f", v)
    }
}
