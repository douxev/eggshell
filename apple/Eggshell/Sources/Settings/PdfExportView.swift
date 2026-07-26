import SwiftUI
import TransitionCore
import UIKit

// « Rapport médecin » — the configuration of the doctor's export (§6.12).
//
// Reached from Rendez-vous (« Préparer ma consultation »), which is its only
// entry point: the export left Réglages in the refonte (§2.4).
//
// Order is imposed: **period first, content second.** The period changes what
// each module can offer, so choosing it afterwards would mean reading volumes
// that belong to another window.
//
// This file is the *screen*. The document is assembled by `DoctorReportBuilder`
// and painted by `DoctorReportRenderer` — 663 lines mixing the two is what made
// the previous version unmaintainable.

@MainActor
final class PdfExportViewModel: ObservableObject {
    @Published var period: ReportPeriod = ReportPrefs.period
    @Published var shortcut: ReportShortcut = ReportPrefs.shortcut
    @Published var modules: ReportModules = ReportPrefs.modules
    /// Zero means « never typed »; the screen then follows the shortcut.
    @Published var customFromMs: Int64 = ReportPrefs.customFromMs
    @Published var customToMs: Int64 = ReportPrefs.customToMs

    @Published private(set) var volumes = ReportVolumes()
    @Published private(set) var lastVisitMs: Int64?
    @Published private(set) var treatmentStartMs: Int64?
    @Published private(set) var loadingVolumes = true
    @Published var generating = false
    @Published var error: String?
    @Published var generated: URL?

    /// True when the custom bounds were typed rather than derived from a chip.
    var manualDates: Bool { customFromMs > 0 && customToMs > 0 }

    var range: ReportRange {
        ReportPeriodResolver.range(
            period: period,
            shortcut: shortcut,
            customFromMs: customFromMs,
            customToMs: customToMs,
            lastVisitMs: lastVisitMs,
            treatmentStartMs: treatmentStartMs)
    }

    var origin: String {
        ReportPeriodResolver.origin(period: period, shortcut: shortcut, manual: manualDates)
    }

    func loadAnchors(_ session: VaultService, units: [String: String]) async {
        let builder = DoctorReportBuilder(session: session, units: units)
        let anchors = await builder.anchors()
        lastVisitMs = anchors.lastVisitMs
        treatmentStartMs = anchors.treatmentStartMs
        await refreshVolumes(session, units: units)
    }

    func refreshVolumes(_ session: VaultService, units: [String: String]) async {
        loadingVolumes = true
        let builder = DoctorReportBuilder(session: session, units: units)
        volumes = await builder.volumes(range: range)
        loadingVolumes = false
    }

    func persist() {
        ReportPrefs.period = period
        ReportPrefs.shortcut = shortcut
        ReportPrefs.modules = modules
        ReportPrefs.customFromMs = customFromMs
        ReportPrefs.customToMs = customToMs
    }

    /// Builds the document, paints it, and writes it where the share sheet can
    /// reach it. Nothing leaves the device until the user picks a target.
    func generate(_ session: VaultService, units: [String: String]) async {
        generating = true
        error = nil
        generated = nil
        defer { generating = false }

        let chosen = range
        let wanted = modules
        let builder = DoctorReportBuilder(session: session, units: units)
        let document = await builder.build(range: chosen, modules: wanted)
        let banner = "SUIVI DE TRANSITION — RAPPORT DE SUIVI"
        let footer = "eggshell \(AppVersion.name) — document produit hors ligne, aucune donnée transmise."

        let data = await Task.detached(priority: .userInitiated) {
            DoctorReportRenderer(bannerLeft: banner, footerLeft: footer).pdfData(document)
        }.value

        do {
            let dir = Self.exportDir
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            let url = dir.appendingPathComponent(document.fileName)
            // Purge first: a previous export is decrypted health data whose file
            // name shows up in the share sheet, and it has no reason to outlive
            // the next one. The file about to be written is spared so re-exporting
            // the same period never leaves an empty stub behind.
            let existing = (try? FileManager.default.contentsOfDirectory(
                at: dir, includingPropertiesForKeys: nil)) ?? []
            for old in existing where old != url { AppPaths.secureDelete(old) }
            // Same protection class the decrypted photo and voice copies get:
            // this file carries a name, a date of birth and every hormone value,
            // so it must be unreadable while the device is locked rather than
            // taking the container default.
            try data.write(to: url, options: [.atomic, .completeFileProtection])
            generated = url
        } catch {
            self.error = describe(error)
        }
    }

    /// Erase every generated report.
    ///
    /// The document is the most concentrated plaintext the app ever writes — the
    /// identity block, the hormone values, the punctuality figures, the bleeding
    /// episodes, and decrypted progress photos when that module is on. It used
    /// to survive until the *next* export replaced it, so one export left it
    /// readable for ever. Called when the share sheet closes, when the screen
    /// goes away, and when the app is backgrounded (which is when the vault
    /// locks).
    func purgeExports() {
        let existing = (try? FileManager.default.contentsOfDirectory(
            at: Self.exportDir, includingPropertiesForKeys: nil)) ?? []
        for file in existing { AppPaths.secureDelete(file) }
        generated = nil
    }

    /// The only directory the document is ever written to.
    private static var exportDir: URL {
        AppPaths.cacheDir.appendingPathComponent("pdf_export", isDirectory: true)
    }
}

struct PdfExportView: View {
    @EnvironmentObject private var app: AppState
    @EnvironmentObject private var hormoneUnits: HormoneUnitStore
    @Environment(\.palette) private var palette
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var vm = PdfExportViewModel()
    /// Owned by this screen rather than by `AppState`: the two fields exist for
    /// this document and are read nowhere else, so the object that holds them has
    /// no reason to outlive the screen that decides to hand the document over.
    @StateObject private var identity = ReportIdentityStore()

    @State private var showCustomSheet = false
    @State private var showIdentitySheet = false
    /// Non-nil only while the share sheet that owns the file's clean-up is up.
    @State private var shareTarget: PdfShareTarget?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Metrics.blockGap) {
                intentCard
                identityRow
                SectionTitleView("Période", prominent: true)
                periodPills
                recapLine
                SectionTitleView(
                    "Contenu",
                    action: allChecked ? "Tout décocher" : "Tout cocher",
                    onAction: toggleAll,
                    prominent: true)
                modulesCard
                encryptedNote
                if let message = vm.error {
                    ErrorCardView(message, retryLabel: "Réessayer", retry: generate)
                }
                if let url = vm.generated { shareRow(url) }
                Color.clear.frame(height: Spacing.s)
            }
            .padding(.horizontal, Metrics.screenMargin)
            .padding(.top, Spacing.s)
        }
        .background(palette.surface.ignoresSafeArea())
        .navigationTitle("Rapport médecin")
        .navigationBarTitleDisplayMode(.inline)
        .eggActionBar {
            ActionBarButton(
                generateLabel,
                systemImage: "doc.richtext",
                enabled: vm.modules.activeCount > 0 && !vm.generating && app.session != nil,
                action: generate)
        }
        .sheet(isPresented: $showCustomSheet) {
            CustomPeriodSheet(vm: vm, units: unitSnapshot, session: app.session)
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
        }
        .sheet(isPresented: $showIdentitySheet) {
            ReportIdentitySheet(store: identity, session: app.session)
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
        }
        .sheet(item: $shareTarget) { target in
            PdfShareSheet(url: target.url) { vm.purgeExports() }
        }
        .task {
            guard let session = app.session else { return }
            await identity.load(session)
            await vm.loadAnchors(session, units: unitSnapshot)
        }
        // `.background` and not `.inactive`: presenting the share sheet makes the
        // scene inactive for a moment, and wiping the file there would hand the
        // receiving app an empty document.
        .onChange(of: scenePhase) { _, phase in
            if phase == .background { vm.purgeExports() }
        }
        .onDisappear { vm.purgeExports() }
    }

    // MARK: - Blocks

    private var intentCard: some View {
        EggCard(variant: .primary, spacing: Spacing.xs) {
            HStack(alignment: .top, spacing: Spacing.l) {
                Image(systemName: "doc.richtext.fill")
                    .font(.system(size: 26))
                VStack(alignment: .leading, spacing: 3) {
                    Text("Un PDF pour ta consultation")
                        .font(EggFont.titleS)
                    Text("Fabriqué sur l'appareil. Tu choisis ce qu'il contient, et à qui tu le donnes.")
                        .font(EggFont.bodyS)
                        .opacity(0.82)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
    }

    /// The identity block of §7.4.2, edited here and not in Réglages: it belongs
    /// next to the decision to hand the document over, and it keeps a real name
    /// out of a screen the user opens to change the theme.
    private var identityRow: some View {
        ListGroup {
            ListRowView(
                title: "Identité sur le rapport",
                subtitle: identitySubtitle,
                systemImage: "person.text.rectangle",
                iconTint: identity.isPartial ? palette.error : nil,
                showsChevron: true,
                action: { showIdentitySheet = true })
        }
    }

    /// States the truth about what the PDF will carry, never an invitation. The
    /// half-filled case is named rather than rounded to « renseignée »: the box
    /// needs both fields, and a subtitle that hid that would be a lie the
    /// document then tells.
    private var identitySubtitle: String {
        guard identity.loaded else { return "Lecture du coffre…" }
        if let person = identity.person, let birth = identity.birth {
            return "\(person) · \(ReportIdentityFields.long(birth))"
        }
        if identity.isPartial {
            return "Incomplète — il faut les deux, sinon le rapport n'en parle pas"
        }
        return "Non renseignée — le rapport n'en parlera pas"
    }

    private var periodPills: some View {
        ChipFlowLayout(spacing: 7, lineSpacing: 7) {
            ForEach(ReportPeriod.allCases) { option in
                PillView(option.label, selected: vm.period == option) {
                    if option == .custom {
                        showCustomSheet = true
                    } else {
                        vm.period = option
                        commit()
                    }
                }
            }
        }
    }

    private var recapLine: some View {
        HStack(spacing: Spacing.s) {
            Image(systemName: "calendar")
                .font(.system(size: 17))
                .foregroundStyle(palette.onSurfaceVariant)
            VStack(alignment: .leading, spacing: 1) {
                Text(rangeLabel)
                    .font(.eggBody)
                    .foregroundStyle(palette.onSurface)
                Text("\(plural(vm.range.days, "jour", "jours")) · \(vm.origin)")
                    .font(EggFont.bodyS)
                    .foregroundStyle(palette.onSurfaceVariant)
            }
            Spacer(minLength: Spacing.s)
            Button { showCustomSheet = true } label: {
                Text("Changer")
                    .font(EggFont.micro)
                    .tracking(0.5)
                    .foregroundStyle(palette.primary)
                    .padding(.vertical, 8)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, Spacing.l)
        .padding(.vertical, Spacing.m)
        .background(
            palette.surfaceContainer,
            in: RoundedRectangle(cornerRadius: Radius.field, style: .continuous))
    }

    private var modulesCard: some View {
        ListGroup {
            ForEach(Array(rows.enumerated()), id: \.offset) { index, row in
                moduleRow(row)
                if index != rows.count - 1 {
                    Rectangle()
                        .fill(palette.outlineVariant)
                        .frame(height: 1)
                }
            }
        }
    }

    private var encryptedNote: some View {
        HStack(alignment: .top, spacing: Spacing.s) {
            Image(systemName: "lock.shield")
                .font(.system(size: 17))
                .foregroundStyle(palette.primary)
            Text("Le PDF est fabriqué hors ligne puis passé au partage du téléphone. Rien ne part tant que tu ne l'envoies pas.")
                .font(EggFont.bodyS)
                .foregroundStyle(palette.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.horizontal, Spacing.l)
        .padding(.vertical, Spacing.m)
        .background(
            palette.surfaceContainer,
            in: RoundedRectangle(cornerRadius: Radius.field, style: .continuous))
    }

    /// The share step goes through a sheet of its own so the file can be wiped
    /// the moment that sheet closes — the same clean-up the photo and voice
    /// exports use, rather than leaving the document in the cache until the
    /// next export happens to replace it.
    private func shareRow(_ url: URL) -> some View {
        EggCard(variant: .low, spacing: Spacing.s) {
            Text("Ton rapport est prêt.")
                .font(EggFont.titleS)
                .foregroundStyle(palette.onSurface)
            Text(url.lastPathComponent)
                .font(EggFont.bodyS)
                .foregroundStyle(palette.onSurfaceVariant)
            Button {
                shareTarget = PdfShareTarget(url: url)
            } label: {
                Label("Partager le PDF", systemImage: "square.and.arrow.up")
                    .font(EggFont.label)
                    .foregroundStyle(palette.primary)
                    .frame(
                        maxWidth: .infinity,
                        minHeight: Metrics.touchTarget,
                        alignment: .leading)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
    }

    // MARK: - The eight module rows

    private struct ModuleRow {
        let title: String
        let subtitle: String
        let systemImage: String
        let error: Bool
        let binding: Binding<Bool>
    }

    private var rows: [ModuleRow] {
        let v = vm.volumes
        return [
            ModuleRow(
                title: "Traitements & régularité",
                subtitle: v.molecules == 0
                    ? ReportVolumes.empty
                    : "\(plural(v.molecules, "molécule", "molécules")) · observance et retards",
                systemImage: "pills",
                error: false,
                binding: $vm.modules.medications),
            ModuleRow(
                title: "Taux hormonaux",
                subtitle: v.labs == 0
                    ? ReportVolumes.empty
                    : "\(plural(v.labs, "relevé", "relevés")) · courbes et tableau",
                systemImage: "chart.line.uptrend.xyaxis",
                error: false,
                binding: $vm.modules.hormones),
            ModuleRow(
                title: "Poids",
                subtitle: v.weights == 0
                    ? ReportVolumes.empty
                    : plural(v.weights, "pesée", "pesées"),
                systemImage: "scalemass",
                error: false,
                binding: $vm.modules.weight),
            // A privacy statement, not a volume: the free text never leaves.
            ModuleRow(
                title: "Ressenti",
                subtitle: "Moyennes et effets signalés, sans le texte libre",
                systemImage: "heart.text.square",
                error: false,
                binding: $vm.modules.feel),
            ModuleRow(
                title: "Questions à aborder",
                subtitle: questionsSubtitle,
                systemImage: "checklist",
                error: false,
                binding: $vm.modules.questions),
            ModuleRow(
                title: "Règles",
                subtitle: v.bleedingDays == 0
                    ? ReportVolumes.empty
                    : plural(v.bleedingDays, "jour de saignement", "jours de saignement"),
                systemImage: "drop",
                error: false,
                binding: $vm.modules.bleeding),
            ModuleRow(
                title: "Voix",
                subtitle: v.clips == 0
                    ? ReportVolumes.empty
                    : "Hauteur moyenne · \(plural(v.clips, "enregistrement", "enregistrements"))",
                systemImage: "waveform",
                error: false,
                binding: $vm.modules.voice),
            // Never a volume, always the warning — and always in `error`.
            ModuleRow(
                title: "Photos d'évolution",
                subtitle: "Jamais incluses par défaut",
                systemImage: "photo.on.rectangle",
                error: true,
                binding: $vm.modules.photos),
        ]
    }

    private var questionsSubtitle: String {
        guard let date = vm.volumes.questionsDate else { return "Aucun rendez-vous à venir" }
        guard vm.volumes.questions > 0 else { return ReportVolumes.empty }
        return "\(plural(vm.volumes.questions, "note", "notes")) du rendez-vous du \(date)"
    }

    private func moduleRow(_ row: ModuleRow) -> some View {
        Toggle(isOn: row.binding) {
            HStack(spacing: Spacing.m) {
                Image(systemName: row.systemImage)
                    .font(.system(size: 17))
                    .foregroundStyle(palette.onSurfaceVariant)
                    .frame(width: 24)
                VStack(alignment: .leading, spacing: 2) {
                    Text(row.title)
                        .font(.eggBody)
                        .foregroundStyle(palette.onSurface)
                    Text(row.subtitle)
                        .font(EggFont.bodyS)
                        .foregroundStyle(row.error ? palette.error : palette.onSurfaceVariant)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        .tint(palette.primary)
        .padding(.horizontal, Metrics.screenMargin)
        .padding(.vertical, 11)
        .frame(minHeight: Metrics.touchTarget)
        .onChange(of: row.binding.wrappedValue) { _, _ in vm.persist() }
    }

    // MARK: - Derived copy

    private var rangeLabel: String {
        let f = ReportFormats()
        let range = vm.range
        let calendar = Calendar.current
        let sameYear = calendar.component(
            .year, from: Date(timeIntervalSince1970: Double(range.fromMs) / 1000))
            == calendar.component(
                .year, from: Date(timeIntervalSince1970: Double(range.toMs) / 1000))
        let start = sameYear ? f.proseNoYear(range.fromMs) : f.prose(range.fromMs)
        return "\(start) → \(f.prose(range.toMs))"
    }

    /// The button counts the pages live: `1 + ceil(n / 3)` (§6.12.6). Nothing
    /// checked is not a greyed button with a stale label — the label says so.
    private var generateLabel: String {
        if vm.generating { return "Préparation…" }
        let active = vm.modules.activeCount
        guard active > 0 else { return "Rien à exporter" }
        let pages = vm.modules.pages
        return "Générer · \(pages) " + (pages <= 1 ? "page" : "pages")
    }

    /// Photos stay out of « Tout cocher »: forcing them on would contradict
    /// « Jamais incluses par défaut », which is the guarantee of §6.12.4.
    private var allChecked: Bool {
        vm.modules.medications && vm.modules.hormones && vm.modules.weight && vm.modules.feel
            && vm.modules.questions && vm.modules.bleeding && vm.modules.voice
    }

    private func toggleAll() {
        let on = !allChecked
        vm.modules.medications = on
        vm.modules.hormones = on
        vm.modules.weight = on
        vm.modules.feel = on
        vm.modules.questions = on
        vm.modules.bleeding = on
        vm.modules.voice = on
        vm.persist()
    }

    private var unitSnapshot: [String: String] {
        var out: [String: String] = [:]
        for hormone in HormoneCatalog.kinds + [HormoneCatalog.weight] {
            if let unit = hormoneUnits.effectiveUnit(for: hormone) { out[hormone] = unit }
        }
        return out
    }

    private func plural(_ count: Int, _ one: String, _ many: String) -> String {
        ReportVolumes.plural(count, one, many)
    }

    private func commit() {
        vm.persist()
        guard let session = app.session else { return }
        let units = unitSnapshot
        Task { await vm.refreshVolumes(session, units: units) }
    }

    private func generate() {
        guard let session = app.session else { return }
        let units = unitSnapshot
        vm.persist()
        Task { await vm.generate(session, units: units) }
    }
}

// MARK: - Feuille de partage

/// Identifiable wrapper so `.sheet(item:)` can carry the plaintext URL.
private struct PdfShareTarget: Identifiable {
    let url: URL
    var id: String { url.absoluteString }
}

/// The share step in a sheet of its own, so closing it wipes the document.
private struct PdfShareSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.palette) private var palette
    let url: URL
    let onClose: () -> Void

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: Metrics.blockGap) {
                Text("Le PDF a été préparé en clair juste pour ce partage. "
                     + "Il est effacé dès que tu fermes cette feuille.")
                    .font(EggFont.bodyS)
                    .foregroundStyle(palette.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)

                ShareLink(item: url) {
                    HStack(spacing: Spacing.s) {
                        Image(systemName: "square.and.arrow.up")
                            .font(.system(size: 17, weight: .semibold))
                        Text("Partager le PDF").font(.system(size: 15.5, weight: .semibold))
                    }
                    .foregroundStyle(palette.onPrimary)
                    .frame(maxWidth: .infinity)
                    .frame(height: 46)
                    .background(palette.primary, in: Capsule())
                    .contentShape(Capsule())
                }
                .buttonStyle(.plain)

                Spacer(minLength: 0)
            }
            .padding(Metrics.cardPadding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(palette.surface.ignoresSafeArea())
            .navigationTitle("Partager")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Fermer") { dismiss() }
                }
            }
        }
        .presentationDetents([.height(260)])
        .presentationDragIndicator(.visible)
        .onDisappear { onClose() }
    }
}

// MARK: - « Identité sur le rapport »

/// The two fields of the report's boxed header (§7.4.2).
///
/// Both are optional and the sheet says why in two sentences rather than a
/// warning panel: the trade-off is the user's to make, and it is legible here
/// because here is where the document gets handed over. Leaving them empty is a
/// supported answer, not a mistake — the box simply does not exist on the page.
private struct ReportIdentitySheet: View {
    @ObservedObject var store: ReportIdentityStore
    let session: VaultService?

    @Environment(\.palette) private var palette
    @Environment(\.dismiss) private var dismiss

    @State private var name = ""
    /// The picker always has a value, so « no date » is a state of its own rather
    /// than a sentinel day someone would eventually mistake for an answer.
    @State private var hasBirth = false
    @State private var birth = ReportIdentitySheet.pickerStart
    @State private var busy = false
    @State private var error: String?

    /// Where the picker opens when there is nothing stored. Today's date would
    /// read as a filled-in answer; a generation back is visibly a starting point.
    private static var pickerStart: Date {
        Calendar.current.date(byAdding: .year, value: -30, to: Date()) ?? Date()
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: Spacing.l) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Identité sur le rapport")
                            .font(EggFont.titleL)
                            .foregroundStyle(palette.onSurface)
                        Text("Ces deux informations restent dans ton coffre chiffré et n'apparaissent que sur le PDF que tu remets. Si tu les laisses vides, le rapport n'a simplement pas d'encadré d'identité.")
                            .font(EggFont.bodyS)
                            .foregroundStyle(palette.onSurfaceVariant)
                            .fixedSize(horizontal: false, vertical: true)
                    }

                    field("NOM SUR LE RAPPORT") {
                        TextField("Facultatif", text: $name)
                            .font(.eggBody)
                            .foregroundStyle(palette.onSurface)
                            .textInputAutocapitalization(.words)
                            .autocorrectionDisabled()
                            .frame(minHeight: Metrics.touchTarget, alignment: .leading)
                    }

                    field("DATE DE NAISSANCE") {
                        if hasBirth {
                            HStack(spacing: Spacing.s) {
                                DatePicker(
                                    "",
                                    selection: $birth,
                                    in: ...Date(),
                                    displayedComponents: [.date])
                                    .labelsHidden()
                                    .datePickerStyle(.compact)
                                    .tint(palette.primary)
                                    .environment(\.locale, Locale(identifier: "fr_FR"))
                                Spacer(minLength: Spacing.s)
                                Button("Retirer") { hasBirth = false }
                                    .font(EggFont.label)
                                    .foregroundStyle(palette.primary)
                                    .buttonStyle(.plain)
                            }
                            .frame(minHeight: Metrics.touchTarget)
                        } else {
                            Button {
                                hasBirth = true
                            } label: {
                                Text("Ajouter une date")
                                    .font(EggFont.label)
                                    .foregroundStyle(palette.primary)
                                    .frame(
                                        maxWidth: .infinity,
                                        minHeight: Metrics.touchTarget,
                                        alignment: .leading)
                                    .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                        }
                    }

                    note("Le nom et la date vont ensemble : avec une seule des deux, l'encadré n'est pas imprimé.")

                    if let error {
                        ErrorCardView(error)
                    }

                    if store.person != nil || store.birth != nil {
                        Button(role: .destructive) {
                            act { session in try await store.erase(session) }
                        } label: {
                            Label("Effacer ces deux informations", systemImage: "trash")
                                .font(EggFont.label)
                                .foregroundStyle(palette.error)
                                .frame(
                                    maxWidth: .infinity,
                                    minHeight: Metrics.touchTarget,
                                    alignment: .leading)
                                .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .disabled(busy)
                    }
                }
                .padding(.horizontal, Metrics.screenMargin)
                .padding(.top, Spacing.s)
            }
            .background(palette.surfaceContainerHigh.ignoresSafeArea())
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Annuler") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Enregistrer") {
                        act { session in
                            try await store.save(
                                person: name, birth: hasBirth ? birth : nil, session)
                        }
                    }
                    .disabled(busy || session == nil)
                }
            }
            .onAppear { restore() }
        }
    }

    private func restore() {
        name = store.person ?? ""
        hasBirth = store.birth != nil
        birth = store.birth ?? Self.pickerStart
    }

    /// One place for the two writes, so neither can dismiss over a failure: a
    /// sheet that closes on an error would look like it saved.
    private func act(_ body: @escaping (VaultService) async throws -> Void) {
        guard let session else { return }
        busy = true
        error = nil
        Task {
            do {
                try await body(session)
                busy = false
                dismiss()
            } catch {
                self.error = describe(error)
                busy = false
            }
        }
    }

    private func field<Content: View>(
        _ label: String,
        @ViewBuilder content: () -> Content
    ) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            MicroLabel(label)
            content()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, Spacing.l)
        .padding(.vertical, Spacing.m)
        .overlay(
            RoundedRectangle(cornerRadius: Radius.field, style: .continuous)
                .stroke(palette.outline, lineWidth: 1))
    }

    private func note(_ text: String) -> some View {
        HStack(alignment: .top, spacing: 9) {
            Image(systemName: "info.circle")
                .font(.system(size: 16))
                .foregroundStyle(palette.onSurfaceVariant)
            Text(text)
                .font(EggFont.bodyS)
                .foregroundStyle(palette.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.horizontal, Spacing.l)
        .padding(.vertical, Spacing.m)
        .background(
            palette.surfaceContainer,
            in: RoundedRectangle(cornerRadius: Radius.field, style: .continuous))
    }
}

// MARK: - « Période personnalisée »

/// The custom-period sheet of §6.12.3. Its default is « Depuis la dernière
/// consultation » — the period a consultation is actually about.
private struct CustomPeriodSheet: View {
    @ObservedObject var vm: PdfExportViewModel
    let units: [String: String]
    let session: VaultService?

    @Environment(\.palette) private var palette
    @Environment(\.dismiss) private var dismiss

    @State private var shortcut: ReportShortcut = .lastVisit
    @State private var from = Date()
    @State private var to = Date()
    @State private var manual = false
    @State private var preview = ReportVolumes()
    @State private var previewDays = 0
    /// The last bounds *we* wrote into the pickers. `onChange` fires after the
    /// view update, so comparing against these is the only way to tell a hand
    /// edit from our own derivation — otherwise picking a shortcut would
    /// immediately mark the period as hand-typed and stop following it.
    @State private var derived: ReportRange?

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: Spacing.l) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Période personnalisée")
                            .font(EggFont.titleL)
                            .foregroundStyle(palette.onSurface)
                        Text("Choisis un raccourci, ou les deux dates.")
                            .font(EggFont.bodyS)
                            .foregroundStyle(palette.onSurfaceVariant)
                    }

                    ChipFlowLayout(spacing: 7, lineSpacing: 7) {
                        ForEach(ReportShortcut.offered) { option in
                            PillView(
                                option.label,
                                selected: !manual && shortcut == option,
                                enabled: enabled(option)
                            ) {
                                shortcut = option
                                applyShortcut(option)
                            }
                        }
                    }

                    HStack(spacing: Spacing.s) {
                        dateField("DU", date: $from)
                        dateField("AU", date: $to)
                    }

                    HStack(alignment: .center, spacing: 9) {
                        Image(systemName: "info.circle")
                            .font(.system(size: 16))
                            .foregroundStyle(palette.onSurfaceVariant)
                        Text(previewLine)
                            .font(EggFont.bodyS)
                            .foregroundStyle(palette.onSurfaceVariant)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .padding(.horizontal, Spacing.l)
                    .padding(.vertical, Spacing.m)
                    .background(
                        palette.surfaceContainer,
                        in: RoundedRectangle(cornerRadius: Radius.field, style: .continuous))
                }
                .padding(.horizontal, Metrics.screenMargin)
                .padding(.top, Spacing.s)
            }
            .background(palette.surfaceContainerHigh.ignoresSafeArea())
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Annuler") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Appliquer") { apply() }
                }
            }
            .onAppear { restore() }
            .onChange(of: from) { _, _ in noteEdit() }
            .onChange(of: to) { _, _ in noteEdit() }
        }
    }

    private func noteEdit() {
        if let derived, derived == currentRange {
            // Our own write coming back through the picker: not an edit.
        } else {
            manual = true
        }
        refresh()
    }

    private var currentRange: ReportRange {
        ReportRange(
            fromMs: Int64(from.timeIntervalSince1970 * 1000),
            toMs: Int64(to.timeIntervalSince1970 * 1000))
    }

    private func dateField(_ label: String, date: Binding<Date>) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label)
                .font(EggFont.micro)
                .tracking(0.5)
                .foregroundStyle(palette.onSurfaceVariant)
            DatePicker("", selection: date, displayedComponents: [.date])
                .labelsHidden()
                .datePickerStyle(.compact)
                .tint(palette.primary)
                .environment(\.locale, Locale(identifier: "fr_FR"))
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, Spacing.l)
        .padding(.vertical, Spacing.m)
        .overlay(
            RoundedRectangle(cornerRadius: Radius.field, style: .continuous)
                .stroke(palette.outline, lineWidth: 1))
    }

    /// « Depuis la dernière consultation » needs a past consultation to exist.
    private func enabled(_ option: ReportShortcut) -> Bool {
        option != .lastVisit || vm.lastVisitMs != nil
    }

    private var previewLine: String {
        "\(ReportVolumes.plural(previewDays, "jour", "jours")) · "
            + "\(ReportVolumes.plural(preview.labs, "relevé", "relevés")), "
            + "\(ReportVolumes.plural(preview.doses, "prise", "prises")), "
            + ReportVolumes.plural(preview.feelEntries, "entrée de ressenti", "entrées de ressenti")
    }

    private func restore() {
        shortcut = vm.shortcut
        if vm.manualDates {
            write(vm.range, manual: true)
        } else {
            applyShortcut(shortcut)
        }
    }

    private func applyShortcut(_ option: ReportShortcut) {
        write(
            ReportPeriodResolver.shortcutRange(
                option,
                lastVisitMs: vm.lastVisitMs,
                treatmentStartMs: vm.treatmentStartMs),
            manual: false)
    }

    private func write(_ range: ReportRange, manual isManual: Bool) {
        derived = range
        manual = isManual
        from = Date(timeIntervalSince1970: Double(range.fromMs) / 1000)
        to = Date(timeIntervalSince1970: Double(range.toMs) / 1000)
        refresh()
    }

    private func refresh() {
        guard let session else { return }
        let range = currentRange
        previewDays = range.days
        let snapshot = units
        Task {
            let builder = DoctorReportBuilder(session: session, units: snapshot)
            preview = await builder.volumes(range: range)
        }
    }

    private func apply() {
        vm.period = .custom
        vm.shortcut = shortcut
        if manual {
            vm.customFromMs = Int64(from.timeIntervalSince1970 * 1000)
            vm.customToMs = Int64(to.timeIntervalSince1970 * 1000)
        } else {
            // A shortcut is a *rule*, not a pair of dates: stored as bounds it
            // would silently stop following the last consultation.
            vm.customFromMs = 0
            vm.customToMs = 0
        }
        vm.persist()
        if let session {
            let snapshot = units
            Task { await vm.refreshVolumes(session, units: snapshot) }
        }
        dismiss()
    }
}
