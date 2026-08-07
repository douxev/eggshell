import SwiftUI
import TransitionCore
import PhotosUI
import Vision
import PDFKit
import UniformTypeIdentifiers
import UIKit

// ===========================================================================
// PUSHED screen — « Importer une analyse » (§6.9). Mirrors
// ImportLabResultScreen.kt.
//
// Four steps, all of them on the device: Fichier → Lecture → Aperçu →
// Enregistré. Nothing is uploaded, and the picked file is never copied into the
// vault — only the values the user keeps are.
//
//   1. Fichier   : pick a PDF or a photo of the sheet.
//   2. Lecture   : rasterise (PDF → page images via PDFKit) and run Vision's
//                  VNRecognizeTextRequest (fr + en) on each image.
//   3. Aperçu    : LabResultParser.parse() → one switchable row per analyte,
//                  plus an editable draw date. A doubtful read quotes the raw
//                  OCR string in `error` and starts switched OFF.
//   4. Enregistré: addHormoneMeasurement for each kept row.
//
// Two failures are told apart on purpose (§6.9): a password-protected PDF is
// not a broken read — we ask for the key, use it once and forget it — whereas a
// document we cannot make sense of leads to manual entry.
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

    /// A parsed row in the preview list. The user can switch it off if the
    /// parser picked something wrong — and a doubtful read starts off.
    struct EditableEntry: Identifiable {
        let id = UUID()
        let hormone: String
        let value: Double
        let unit: String
        var selected: Bool
        /// What the document literally showed, quoted back on a doubtful read.
        let raw: String
        let doubtful: Bool
    }

    @Published var phase: Phase = .idle
    @Published var entries: [EditableEntry] = []
    @Published var date = Date()
    @Published var dateAutoDetected = false

    /// Laboratory read off the letterhead; nil when unrecognised.
    private(set) var labName: String?

    /// Bytes of an encrypted PDF held between the lock detection and the
    /// password retry, so we don't re-touch the security-scoped URL.
    private var pendingPDFData: Data?

    var selectedCount: Int { entries.filter(\.selected).count }

    /// Which of the four segments are filled. A locked PDF is still step 1:
    /// the file has not been read yet.
    var step: Int {
        switch phase {
        case .idle, .passwordRequired: return 1
        case .processing, .error:      return 2
        case .preview:                 return 3
        case .done:                    return 4
        }
    }

    // MARK: - Picking

    /// A file picked from Fichiers — a PDF from the lab, or a scan/photo of the
    /// paper sheet.
    func processFile(at url: URL) async {
        if url.pathExtension.lowercased() == "pdf" {
            await processPDF(at: url)
        } else {
            await processImageFile(at: url)
        }
    }

    /// OCR an image's raw bytes, parse, and move to the preview phase.
    func processImageData(_ data: Data) async {
        phase = .processing
        guard let image = UIImage(data: data), let cg = image.cgImage else {
            phase = .error("Cette image ne s’ouvre pas.")
            return
        }
        let text = await Self.recognizeText(from: [cg])
        finishProcessing(text)
    }

    private func processImageFile(at url: URL) async {
        phase = .processing
        let accessed = url.startAccessingSecurityScopedResource()
        defer { if accessed { url.stopAccessingSecurityScopedResource() } }
        guard let data = try? Data(contentsOf: url) else {
            phase = .error("Ce fichier n’a pas pu être ouvert.")
            return
        }
        await processImageData(data)
    }

    /// OCR a PDF (each page rasterised), parse, and move to the preview phase.
    /// Reads the bytes once inside the security scope; if the PDF is encrypted we
    /// stash them and prompt for a password instead of failing.
    func processPDF(at url: URL) async {
        phase = .processing
        let accessed = url.startAccessingSecurityScopedResource()
        defer { if accessed { url.stopAccessingSecurityScopedResource() } }
        guard let data = try? Data(contentsOf: url), let doc = PDFDocument(data: data) else {
            phase = .error("Ce PDF n’a pas pu être ouvert.")
            return
        }
        if doc.isLocked {
            pendingPDFData = data
            phase = .passwordRequired(wrongPassword: false)
            return
        }
        await rasterizeAndRecognize(doc)
    }

    /// Retry an encrypted PDF with the password the user just entered. The
    /// password is a parameter and nothing else: never held in state, never
    /// written to disk, gone as soon as this call returns.
    func submitPassword(_ password: String) async {
        guard !password.isEmpty,
              let data = pendingPDFData,
              let doc = PDFDocument(data: data) else { return }
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
            phase = .error("Aucune page de ce PDF n’a pu être lue.")
            return
        }
        let text = await Self.recognizeText(from: images)
        finishProcessing(text)
    }

    private func finishProcessing(_ text: String) {
        let parsed = LabResultParser.parse(text)
        entries = parsed.values.map {
            EditableEntry(
                hormone: $0.hormone,
                value: $0.value,
                unit: $0.unit,
                // A doubtful read is opt-in: we never save a guess the user
                // hasn't looked at.
                selected: !$0.doubtful,
                raw: $0.raw,
                doubtful: $0.doubtful)
        }
        labName = parsed.labName
        if let detectedMs = parsed.dateMs {
            date = Date(timeIntervalSince1970: Double(detectedMs) / 1000)
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
        let chosen = entries.filter(\.selected)
        guard !chosen.isEmpty else { return }
        phase = .processing
        let atMs = Int64(date.timeIntervalSince1970 * 1000)
        // Provenance (D3): an imported reading always names where it came from,
        // so the doctor report can tell it apart from a value typed in by hand.
        // No new column — this is the existing `lab_name`.
        let provenance = labName ?? "PDF importé"
        var saved = 0
        for entry in chosen {
            do {
                _ = try await session.addHormoneMeasurement(NewHormoneMeasurement(
                    atMs: atMs,
                    hormone: entry.hormone,
                    value: entry.value,
                    unit: entry.unit,
                    labName: provenance,
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
        labName = nil
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
    @EnvironmentObject private var router: Router
    @Environment(\.palette) private var palette
    @Environment(\.dismiss) private var dismiss
    @StateObject private var vm = ImportLabResultViewModel()

    @State private var pickerItem: PhotosPickerItem?
    @State private var showFileImporter = false
    @State private var showPhotoPicker = false
    /// Plain `@State` on purpose: the password must not survive this screen.
    @State private var pdfPassword = ""

    /// The one privacy promise of this screen, repeated word for word at every
    /// step so it never wavers.
    private static let privacy =
        "Le PDF a été lu sur l’appareil. Rien n’est envoyé, et le fichier d’origine n’est pas conservé."

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                OcrStepProgress(step: vm.step)
                stepCaption
                switch vm.phase {
                case .idle:                    fileStep
                case .processing:              readingStep
                case .passwordRequired(let wrong): lockedStep(wrongPassword: wrong)
                case .preview:
                    if vm.entries.isEmpty {
                        failedStep(
                            title: "Aucune valeur reconnue",
                            body: "Le document s’ouvre bien, mais je n’y ai rien reconnu. Essaie "
                                + "une autre page, ou saisis les valeurs toi-même.",
                            detail: nil)
                    } else {
                        previewStep
                    }
                case .done(let saved):         savedStep(saved: saved)
                case .error(let reason):
                    failedStep(
                        title: "Je n’arrive pas à lire ce document",
                        body: "Le fichier est peut-être trop flou, ou sa mise en page trop "
                            + "inhabituelle. Tu peux réessayer avec une photo plus nette, ou "
                            + "saisir les valeurs toi-même.",
                        detail: reason)
                }
                Color.clear.frame(height: Spacing.m)
            }
            .padding(.horizontal, Metrics.screenMargin)
            .padding(.top, Spacing.m)
        }
        .measuresScreen("Importer une analyse")
        .eggActionBar { actionBar }
        .photosPicker(isPresented: $showPhotoPicker, selection: $pickerItem, matching: .images)
        .fileImporter(isPresented: $showFileImporter, allowedContentTypes: [.pdf, .image]) { result in
            if case .success(let url) = result {
                Task { await vm.processFile(at: url) }
            }
        }
        .onChange(of: pickerItem) { _, newItem in
            guard let newItem else { return }
            Task {
                if let data = try? await newItem.loadTransferable(type: Data.self) {
                    await vm.processImageData(data)
                }
                pickerItem = nil
            }
        }
        // Clear the password field whenever we leave the password step, so a
        // second encrypted PDF in the same session doesn't start pre-filled.
        .onChange(of: vm.phase) { _, newPhase in
            if case .passwordRequired = newPhase {} else { pdfPassword = "" }
        }
        .sensoryFeedback(.success, trigger: vm.step == 4)
    }

    // MARK: - Chrome shared by the four steps

    /// « 3 / 4 · VÉRIFIE CE QU’ON A LU » on the left, « Hors ligne » on the
    /// right. The offline word is not decoration: it is the whole point.
    private var stepCaption: some View {
        HStack {
            MicroLabel(Self.caption(vm.step), color: palette.primary)
            Spacer(minLength: Spacing.s)
            MicroLabel("Hors ligne")
        }
    }

    private static func caption(_ step: Int) -> String {
        switch step {
        case 1:  return "1 / 4 · CHOISIS TON FICHIER"
        case 2:  return "2 / 4 · LECTURE EN COURS"
        case 3:  return "3 / 4 · VÉRIFIE CE QU’ON A LU"
        default: return "4 / 4 · C’EST ENREGISTRÉ"
        }
    }

    @ViewBuilder
    private var actionBar: some View {
        switch vm.phase {
        case .idle:
            ActionBarButton("Choisir un fichier", systemImage: "doc.text") {
                showFileImporter = true
            }
        case .processing:
            ActionBarButton("On lit ton document…", enabled: false) {}
        case .passwordRequired:
            ActionBarButton(
                "Déverrouiller",
                systemImage: "lock.open.fill",
                enabled: !pdfPassword.isEmpty
            ) {
                let password = pdfPassword
                Task { await vm.submitPassword(password) }
            }
        case .preview:
            if vm.entries.isEmpty {
                ActionBarButton("Choisir un autre fichier", systemImage: "doc.text") {
                    vm.reset()
                    showFileImporter = true
                }
            } else {
                let kept = vm.selectedCount
                ActionBarButton(
                    kept == 0
                        ? "Rien à enregistrer"
                        : (kept == 1 ? "Enregistrer 1 valeur" : "Enregistrer \(kept) valeurs"),
                    systemImage: "checkmark",
                    enabled: kept > 0
                ) {
                    guard let session = app.session else { return }
                    Task { await vm.save(session: session) }
                }
            }
        case .done:
            ActionBarButton("Voir mes mesures") { dismiss() }
        case .error:
            ActionBarButton("Choisir un autre fichier", systemImage: "doc.text") {
                vm.reset()
                showFileImporter = true
            }
        }
    }

    private func goManualEntry() {
        // Replace this screen rather than stack on top of it: the user asked to
        // type the values in, not to come back to a document we can't read.
        router.pop()
        router.push(.addHormone)
    }

    // MARK: - 1 / 4 — Fichier

    private var fileStep: some View {
        VStack(alignment: .leading, spacing: 14) {
            EggCard(variant: .primary, spacing: 6) {
                Text("Ton bilan reste ici").font(EggFont.titleS)
                Text("Choisis le PDF que ton labo t’a envoyé, ou une photo de la feuille. Tout "
                    + "est lu sur ton téléphone, sans connexion.")
                    .font(.eggBody)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Text("On reconnaît l’œstradiol, la testostérone, la progestérone, la LH, la FSH, la "
                + "prolactine, la SHBG, l’hémoglobine, l’hématocrite et la tension.")
                .font(EggFont.bodyS)
                .foregroundStyle(palette.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
            Button { showPhotoPicker = true } label: {
                Label("Prendre la photo dans ma galerie", systemImage: "photo")
                    .font(EggFont.label)
                    .foregroundStyle(palette.primary)
                    .frame(minHeight: Metrics.touchTarget)
            }
            .buttonStyle(.plain)
            OcrPrivacyInset(text: Self.privacy)
        }
    }

    // MARK: - 2 / 4 — Lecture

    private var readingStep: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("On lit ton document…").font(EggFont.titleS).foregroundStyle(palette.onSurface)
            Text("Ça se passe entièrement sur ton téléphone. Une photo demande parfois quelques "
                + "secondes de plus qu’un PDF.")
                .font(EggFont.bodyS)
                .foregroundStyle(palette.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
            // Skeletons shaped like the review step that follows — never a
            // spinner over the whole page (§5.3).
            SkeletonBlock(height: 64, cornerRadius: Radius.card)
            SkeletonBlock(height: 168, cornerRadius: Radius.card)
            SkeletonBlock(height: 72, cornerRadius: 18)
        }
    }

    // MARK: - 3 / 4 — Aperçu

    private var previewStep: some View {
        VStack(alignment: .leading, spacing: 14) {
            EggCard(variant: .low, paddingH: 18, paddingV: 14) {
                HStack(spacing: Spacing.m) {
                    Image(systemName: "calendar")
                        .font(.system(size: 18))
                        .foregroundStyle(palette.onSurfaceVariant)
                    DatePicker(
                        selection: Binding(
                            get: { vm.date },
                            set: { vm.date = $0; vm.dateChangedManually() }),
                        displayedComponents: [.date]
                    ) {
                        VStack(alignment: .leading, spacing: 1) {
                            Text("Date de prélèvement")
                                .font(EggFont.bodyS)
                                .foregroundStyle(palette.onSurfaceVariant)
                            Text(vm.dateAutoDetected ? "Lue sur le document" : "À toi de confirmer")
                                .font(EggFont.micro)
                                .foregroundStyle(palette.onSurfaceVariant)
                        }
                    }
                    .tint(palette.primary)
                }
            }

            // Naming the laboratory here is not decoration: it is the
            // provenance that will be written on every reading we keep, and
            // what tells this import from a value typed in by hand.
            if let lab = vm.labName {
                MicroLabel("LABORATOIRE · " + MeasureFormat.upper(lab))
            }

            SectionTitleView(
                vm.entries.count == 1 ? "1 valeur détectée" : "\(vm.entries.count) valeurs détectées",
                prominent: true)

            EggCard(variant: .low, paddingH: 18, paddingV: 6, spacing: 0) {
                ForEach(Array(vm.entries.enumerated()), id: \.element.id) { index, entry in
                    OcrAnalyteRow(entry: entry) { vm.toggle(entry.id) }
                    if index < vm.entries.count - 1 { CardRule() }
                }
            }

            Button {
                vm.reset()
                showFileImporter = true
            } label: {
                Text("Choisir un autre fichier")
                    .font(EggFont.label)
                    .foregroundStyle(palette.primary)
                    .frame(minHeight: Metrics.touchTarget)
            }
            .buttonStyle(.plain)

            OcrPrivacyInset(text: Self.privacy)
        }
    }

    // MARK: - 4 / 4 — Enregistré

    private func savedStep(saved: Int) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            EggCard(variant: .primary, spacing: 6) {
                Text("C’est enregistré").font(EggFont.titleS)
                Text(saved == 1
                    ? "1 valeur a rejoint tes mesures."
                    : "\(saved) valeurs ont rejoint tes mesures.")
                    .font(.eggBody)
            }
            OcrPrivacyInset(
                text: "Le fichier d’origine n’a pas été gardé, et rien n’a quitté ton téléphone.")
        }
    }

    // MARK: - The two distinct failures

    /// A locked PDF is not a broken read: we ask for the key, use it once, and
    /// forget it.
    private func lockedStep(wrongPassword: Bool) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            EggCard(variant: wrongPassword ? .error : .low, spacing: Spacing.m) {
                HStack(alignment: .top, spacing: Spacing.m) {
                    Image(systemName: "lock.fill").font(.system(size: 18))
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Ce PDF est protégé").font(EggFont.titleS)
                        Text("Beaucoup de labos verrouillent leurs PDF. Tape le mot de passe "
                            + "qu’ils t’ont donné : il sert une seule fois, et il n’est jamais "
                            + "enregistré.")
                            .font(EggFont.bodyS)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                SecureField("Mot de passe du PDF", text: $pdfPassword)
                    .textFieldStyle(.roundedBorder)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                if wrongPassword {
                    Text("Ce mot de passe n’a pas fonctionné. Réessaie.")
                        .font(EggFont.bodyS)
                }
                Button {
                    vm.reset()
                    showFileImporter = true
                } label: {
                    Text("Choisir un autre fichier")
                        .font(EggFont.label)
                        .foregroundStyle(palette.primary)
                        .frame(minHeight: Metrics.touchTarget)
                }
                .buttonStyle(.plain)
            }
            OcrPrivacyInset(text: Self.privacy)
        }
    }

    /// The other failure: the document opened but we got nothing usable out of
    /// it. Offer the sure thing — typing the values in.
    private func failedStep(title: String, body: String, detail: String?) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            EggCard(variant: .error, spacing: 6) {
                Text(title).font(EggFont.titleS)
                Text(body).font(.eggBody).fixedSize(horizontal: false, vertical: true)
                if let detail {
                    Text(detail).font(EggFont.bodyS)
                }
            }
            // The band offers another file; the sure thing lives here, because
            // a reading typed in by hand always works.
            Button { goManualEntry() } label: {
                Label("Saisir à la main", systemImage: "square.and.pencil")
                    .font(EggFont.label)
                    .foregroundStyle(palette.primary)
                    .frame(minHeight: Metrics.touchTarget)
            }
            .buttonStyle(.plain)
            OcrPrivacyInset(text: Self.privacy)
        }
    }
}

// ===========================================================================
// Pieces of the import flow
// ===========================================================================

/// Four segments, filled up to the step we are on.
struct OcrStepProgress: View {
    @Environment(\.palette) private var palette
    let step: Int

    var body: some View {
        HStack(spacing: 8) {
            ForEach(0..<4, id: \.self) { index in
                Capsule()
                    .fill(index < step ? palette.primary : palette.surfaceContainerHighest)
                    .frame(height: 4)
            }
        }
        .accessibilityElement()
        .accessibilityLabel("Étape \(step) sur 4")
    }
}

/// The `encrypted` inset that closes every step: what was read, and what was
/// not kept.
struct OcrPrivacyInset: View {
    @Environment(\.palette) private var palette
    let text: String

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "lock.fill")
                .font(.system(size: 15))
                .foregroundStyle(palette.primary)
            Text(text)
                .font(EggFont.bodyS)
                .foregroundStyle(palette.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, Metrics.screenMargin)
        .padding(.vertical, 14)
        .background(
            palette.surfaceContainer,
            in: RoundedRectangle(cornerRadius: 18, style: .continuous))
    }
}

/// One detected analyte: its name, what was read, and the switch that decides
/// whether it is kept. A doubtful read quotes the raw OCR string in `error` and
/// arrives switched off — the word « incertaine » is there too, so the warning
/// never rests on colour alone.
struct OcrAnalyteRow: View {
    @Environment(\.palette) private var palette
    let entry: ImportLabResultViewModel.EditableEntry
    let onToggle: () -> Void

    var body: some View {
        let name = HormoneCatalog.kindLabel(entry.hormone)
        let reading = entry.doubtful
            ? "Lecture incertaine · « \(entry.raw) »"
            : "\(MeasureFormat.plain(entry.value)) \(entry.unit)"
        Toggle(isOn: Binding(get: { entry.selected }, set: { _ in onToggle() })) {
            VStack(alignment: .leading, spacing: 2) {
                Text(name)
                    .font(EggFont.titleS)
                    .foregroundStyle(palette.onSurface)
                Text(reading)
                    .font(EggFont.bodyS)
                    .foregroundStyle(entry.doubtful ? palette.error : palette.onSurfaceVariant)
            }
        }
        .tint(palette.primary)
        .padding(.vertical, 12)
        .frame(minHeight: Metrics.touchTarget)
        .accessibilityLabel("Garder \(name) · \(reading)")
    }
}
