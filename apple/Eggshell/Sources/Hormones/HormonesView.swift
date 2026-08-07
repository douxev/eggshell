import SwiftUI
import TransitionCore

// ===========================================================================
// PUSHED screen — « Mesures » (§6.8). Mirrors HormonesScreen.kt.
//
// One screen, two families behind a segmented selector: the analytes of a lab
// sheet, and weight. They share the same shell — a curve card and a readings
// list — because they are read the same way: what is the last value, where is
// it going, and what did the sheet actually say.
//
// The curve speaks the one graphic vocabulary of §5.1: the measured value in
// `primary` with its gradient area, a logged intake in `tertiary`, a treatment
// change as a dashed `secondary` vertical, the grid in `chartGrid`. The legend
// is carried by the axis gradations — there is no separate row under the plot.
//
// The Rust core has no `updateHormoneMeasurement`, so editing a row is
// delete + re-add, driven from the sheet at the bottom of this file.
// ===========================================================================

/// Which family the screen opens on. Accueil has a tile for each.
enum MeasuresSegment: Int, Hashable {
    case hormones = 0
    case weight = 1
}

/// A measurement paired with its display-time conversion to the preferred unit.
struct DisplayMeasurement: Identifiable {
    let raw: HormoneMeasurement
    let displayValue: Double
    let displayUnit: String
    var id: Int64 { raw.id }
}

/// A dose taken inside the charted window — drawn as a dot on the interpolated
/// curve so intakes and levels correlate visually. Always `tertiary`: §5.1
/// gives that role to "dose, medication" in every chart of the app, which is
/// why the medication's own colour is not used here.
struct DoseMarker {
    let atMs: Int64
}

@MainActor
final class HormonesViewModel: ObservableObject {
    @Published var loading = true
    @Published var error: String?

    // Hormones mode
    @Published var hormones: [String] = []
    @Published var selectedHormone: String?
    @Published var measurements: [DisplayMeasurement] = []   // ascending by atMs

    // Weight mode
    @Published var weightMeasurements: [DisplayMeasurement] = []  // ascending by atMs

    /// Doses taken inside the charted window (selected analyte only), one
    /// marker per med per day — daily treatments would otherwise stack dozens
    /// of dots on one spot. Empty in weight mode.
    @Published var doseMarkers: [DoseMarker] = []

    /// Instants at which a treatment changed, drawn as dashed verticals so a
    /// jump in the curve can be read against the change that caused it.
    @Published var treatmentChanges: [Int64] = []

    /// Bumped on every successful write, so the screen can fire one haptic and
    /// one snackbar without inspecting what changed.
    @Published var savedTick = 0

    /// Unit-resolution closure supplied by the view (the store is @MainActor
    /// and lives in the environment). `effective` → effectiveUnit(for:).
    private var effective: (String) -> String? = { _ in nil }

    func configure(effective: @escaping (String) -> String?) {
        self.effective = effective
    }

    /// `showSkeleton` is false for the silent refresh that runs when the stack
    /// comes back to this screen: the content is already on screen and must not
    /// blink back into skeletons.
    func load(_ session: VaultService, showSkeleton: Bool = true) async {
        if showSkeleton { loading = true }
        error = nil
        do {
            let all = try await session.distinctHormones()
            hormones = all.filter { $0 != HormoneCatalog.weight }
            if selectedHormone == nil || !hormones.contains(selectedHormone ?? "") {
                selectedHormone = hormones.first
            }
            if let h = selectedHormone {
                let raw = try await session.listHormoneMeasurements(hormone: h)
                measurements = convert(raw.sorted { $0.atMs < $1.atMs }, hormone: h)
            } else {
                measurements = []
            }
            let rawWeight = try await session.listHormoneMeasurements(hormone: HormoneCatalog.weight)
            weightMeasurements = convert(
                rawWeight.sorted { $0.atMs < $1.atMs }, hormone: HormoneCatalog.weight)
            await loadOverlays(session)
        } catch {
            self.error = describe(error)
        }
        loading = false
    }

    func selectHormone(_ hormone: String, session: VaultService) async {
        selectedHormone = hormone
        do {
            let raw = try await session.listHormoneMeasurements(hormone: hormone)
            measurements = convert(raw.sorted { $0.atMs < $1.atMs }, hormone: hormone)
            await loadOverlays(session)
        } catch {
            self.error = describe(error)
        }
    }

    /// (Re)load the two overlays of the charted window: the logged intakes and
    /// the treatment changes.
    private func loadOverlays(_ session: VaultService) async {
        guard measurements.count >= 2,
              let fromMs = measurements.map(\.raw.atMs).min(),
              let toMs = measurements.map(\.raw.atMs).max() else {
            doseMarkers = []
            treatmentChanges = []
            return
        }
        do {
            // Bucket by *local* calendar day — UTC buckets would split a
            // 23:00 + 01:30 same-night pair into two markers in UTC+2.
            let cal = Calendar.current
            var seen = Set<String>()
            var markers: [DoseMarker] = []
            let doses = try await session.listDoseEventsBetween(fromMs: fromMs, toMs: toMs)
            for e in doses where e.status == "taken" {
                let day = cal.startOfDay(for: Date(timeIntervalSince1970: Double(e.takenAtMs) / 1000))
                let key = "\(e.medicationId)-\(day.timeIntervalSince1970)"
                if seen.insert(key).inserted {
                    markers.append(DoseMarker(atMs: e.takenAtMs))
                }
            }
            doseMarkers = markers
        } catch {
            doseMarkers = []
        }
        do {
            let changes = try await session.listTreatmentChanges(fromMs: fromMs, toMs: toMs)
            treatmentChanges = Array(Set(changes.map(\.atMs))).sorted()
        } catch {
            treatmentChanges = []
        }
    }

    func deleteMeasurement(_ id: Int64, session: VaultService) async {
        do {
            try await session.deleteHormoneMeasurement(id)
            savedTick += 1
            await load(session, showSkeleton: false)
        } catch {
            self.error = describe(error)
        }
    }

    /// Edit = delete + re-add (the core lacks an update). The analyte, the lab
    /// name and the notes carry over from the original record — in particular
    /// the provenance, which is what tells an import from a manual entry.
    func updateMeasurement(
        original: HormoneMeasurement,
        value: Double,
        unit: String,
        atMs: Int64,
        session: VaultService
    ) async {
        do {
            try await session.deleteHormoneMeasurement(original.id)
            _ = try await session.addHormoneMeasurement(NewHormoneMeasurement(
                atMs: atMs,
                hormone: original.hormone,
                value: value,
                unit: unit,
                labName: original.labName,
                notes: original.notes))
            savedTick += 1
            await load(session, showSkeleton: false)
        } catch {
            self.error = describe(error)
        }
    }

    func addWeight(value: Double, unit: String, atMs: Int64, session: VaultService) async {
        do {
            _ = try await session.addHormoneMeasurement(NewHormoneMeasurement(
                atMs: atMs,
                hormone: HormoneCatalog.weight,
                value: value,
                unit: unit,
                labName: nil,
                notes: nil))
            savedTick += 1
            await load(session, showSkeleton: false)
        } catch {
            self.error = describe(error)
        }
    }

    // Apply the preferred-unit conversion to each raw measurement.
    private func convert(_ raw: [HormoneMeasurement], hormone: String) -> [DisplayMeasurement] {
        // Weight defaults to kg; kg ↔ lb is a local helper because the Rust
        // core's convertHormoneValue does not know about weight.
        let target = hormone == HormoneCatalog.weight
            ? (effective(hormone) ?? "kg")
            : effective(hormone)
        return raw.map { m in
            let converted: Double?
            if let t = target, !t.isEmpty, t != m.unit {
                if hormone == HormoneCatalog.weight {
                    converted = HormoneCatalog.convertWeight(m.value, from: m.unit, to: t)
                } else {
                    converted = convertHormoneValue(
                        value: m.value, fromUnit: m.unit, toUnit: t, hormone: hormone)
                }
            } else {
                converted = nil
            }
            return DisplayMeasurement(
                raw: m,
                displayValue: converted ?? m.value,
                displayUnit: converted != nil ? (target ?? m.unit) : m.unit)
        }
    }
}

struct HormonesView: View {
    @EnvironmentObject private var app: AppState
    @EnvironmentObject private var features: FeaturesStore
    @EnvironmentObject private var hormoneUnits: HormoneUnitStore
    @EnvironmentObject private var router: Router
    @Environment(\.palette) private var palette
    @StateObject private var vm = HormonesViewModel()

    @State private var segment: Int
    @State private var showWeightSheet = false
    @State private var editing: DisplayMeasurement?
    @State private var toast: String?

    /// `Route` opens this screen with no argument for « Analyses »; the
    /// « Poids » tile wants the other segment, so the parameter carries a
    /// default and the existing call site keeps working unchanged.
    init(initialTab: MeasuresSegment = .hormones) {
        _segment = State(initialValue: initialTab.rawValue)
    }

    private var weightMode: Bool { segment == MeasuresSegment.weight.rawValue }

    private var active: [DisplayMeasurement] {
        weightMode ? vm.weightMeasurements : vm.measurements
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Metrics.blockGap) {
                if features.weight {
                    SegmentedSelector(
                        options: ["Hormones", "Poids"],
                        selection: $segment,
                        accessibilityLabel: "Famille de mesures")
                }
                if let message = vm.error { ErrorCardView(message) }
                if vm.loading {
                    SkeletonBlock(height: 40, cornerRadius: Radius.pill)
                    SkeletonBlock(height: 214, cornerRadius: Radius.card)
                    SkeletonBlock(height: 132, cornerRadius: Radius.card)
                } else if weightMode {
                    weightSection
                } else {
                    hormoneSection
                }
                Color.clear.frame(height: Spacing.m)
            }
            .padding(.horizontal, Metrics.screenMargin)
            .padding(.top, Spacing.s)
        }
        .measuresScreen("Mesures")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { router.push(.importLab) } label: {
                    Image(systemName: "doc.text.viewfinder")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(palette.primary)
                }
                .accessibilityLabel("Importer une analyse")
            }
        }
        .eggActionBar {
            ActionBarButton(
                weightMode ? "Noter un poids" : "Ajouter un relevé",
                systemImage: "plus"
            ) {
                if weightMode { showWeightSheet = true } else { router.push(.addHormone) }
            }
        }
        .overlay(alignment: .bottom) {
            if let toast {
                SnackbarView(message: toast)
                    .padding(.bottom, Metrics.actionBarHeight + Spacing.m)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .sheet(isPresented: $showWeightSheet) {
            MeasurementSheet(
                title: "Noter un poids",
                hint: "Une pesée de temps en temps suffit : la courbe se dessine toute seule.",
                initialValue: nil,
                initialUnit: "kg",
                unitOptions: HormoneCatalog.weightUnits,
                initialDate: Date(),
                showDelete: false,
                onSave: { value, unit, date in
                    guard let session = app.session else { return }
                    Task {
                        await vm.addWeight(
                            value: value,
                            unit: unit,
                            atMs: Int64(date.timeIntervalSince1970 * 1000),
                            session: session)
                    }
                },
                onDelete: nil)
            .presentationDetents([.medium, .large])
            .presentationDragIndicator(.visible)
            .presentationCornerRadius(Radius.sheet)
        }
        .sheet(item: $editing) { entry in
            MeasurementSheet(
                title: entry.raw.hormone == HormoneCatalog.weight
                    ? "Modifier cette pesée"
                    : "Modifier ce relevé",
                hint: "Tu peux corriger la valeur, l’unité ou la date. Rien d’autre ne bouge.",
                initialValue: entry.raw.value,
                initialUnit: entry.raw.unit,
                unitOptions: entry.raw.hormone == HormoneCatalog.weight
                    ? HormoneCatalog.weightUnits
                    : HormoneCatalog.units,
                initialDate: Date(timeIntervalSince1970: Double(entry.raw.atMs) / 1000),
                showDelete: true,
                onSave: { value, unit, date in
                    guard let session = app.session else { return }
                    Task {
                        await vm.updateMeasurement(
                            original: entry.raw,
                            value: value,
                            unit: unit,
                            atMs: Int64(date.timeIntervalSince1970 * 1000),
                            session: session)
                    }
                },
                onDelete: {
                    guard let session = app.session else { return }
                    Task { await vm.deleteMeasurement(entry.raw.id, session: session) }
                })
            .presentationDetents([.medium, .large])
            .presentationDragIndicator(.visible)
            .presentationCornerRadius(Radius.sheet)
        }
        .sensoryFeedback(.success, trigger: vm.savedTick)
        .onChange(of: vm.savedTick) { _, _ in flash("Enregistré ✓") }
        // Turning weight tracking off while sitting on the Poids segment must
        // not strand the user on a hidden family.
        .onChange(of: features.weight) { _, enabled in
            if !enabled { segment = MeasuresSegment.hormones.rawValue }
        }
        .task {
            vm.configure(effective: { hormoneUnits.effectiveUnit(for: $0) })
            if let session = app.session { await vm.load(session) }
        }
        // Coming back from « Ajouter » or from the OCR import must show the
        // value that was just written.
        .onChange(of: router.path.count) { _, _ in
            if let session = app.session {
                Task { await vm.load(session, showSkeleton: false) }
            }
        }
    }

    private func flash(_ message: String) {
        withAnimation(.easeOut(duration: 0.2)) { toast = message }
        Task {
            try? await Task.sleep(nanoseconds: 2_500_000_000)
            // Guard on the message so a stale timer cannot swallow a newer
            // confirmation.
            withAnimation(.easeIn(duration: 0.2)) { if toast == message { toast = nil } }
        }
    }

    // MARK: - Hormones

    @ViewBuilder
    private var hormoneSection: some View {
        if !vm.hormones.isEmpty {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 7) {
                    ForEach(vm.hormones, id: \.self) { kind in
                        AnalyteChip(
                            HormoneCatalog.kindLabel(kind),
                            selected: vm.selectedHormone == kind
                        ) {
                            guard let session = app.session else { return }
                            Task { await vm.selectHormone(kind, session: session) }
                        }
                    }
                }
                .padding(.horizontal, 1)
            }
        }

        if active.isEmpty {
            EmptyStateView(
                "Tes analyses n’ont pas encore de courbe. Ajoute un résultat, ou importe "
                    + "directement le PDF de ton labo.",
                systemImage: "chart.xyaxis.line",
                actionLabel: "Importer une analyse") {
                    router.push(.importLab)
                }
        } else {
            curveCard(weight: false)
            SectionTitleView("Relevés", prominent: true)
            readingsCard
        }
    }

    // MARK: - Poids

    @ViewBuilder
    private var weightSection: some View {
        if active.isEmpty {
            EmptyStateView(
                "Note ton poids de temps en temps : la courbe se dessine toute seule.",
                systemImage: "scalemass",
                actionLabel: "Noter un poids") {
                    showWeightSheet = true
                }
        } else {
            curveCard(weight: true)
            SectionTitleView("Relevés", prominent: true)
            readingsCard
        }
    }

    // MARK: - The curve card

    @ViewBuilder
    private func curveCard(weight: Bool) -> some View {
        let items = active
        if let latest = items.last {
            let previous = items.count >= 2 ? items[items.count - 2] : nil
            EggCard(variant: .low, paddingH: 18, paddingV: 18, cornerRadius: 24, spacing: 0) {
                HStack(alignment: .bottom, spacing: Spacing.s) {
                    VStack(alignment: .leading, spacing: 2) {
                        MicroLabel(
                            (weight ? "DERNIÈRE PESÉE · " : "DERNIÈRE VALEUR · ")
                                + MeasureFormat.upper(MeasureFormat.dayMonth(latest.raw.atMs)))
                        HStack(alignment: .lastTextBaseline, spacing: 6) {
                            Text(MeasureFormat.value(latest.displayValue))
                                .font(.system(size: 34, weight: .semibold))
                                .foregroundStyle(palette.onSurface)
                            Text(latest.displayUnit)
                                .font(EggFont.titleS)
                                .foregroundStyle(palette.onSurfaceVariant)
                        }
                    }
                    Spacer(minLength: Spacing.s)
                    if let previous {
                        MeasureDeltaPill(delta: latest.displayValue - previous.displayValue)
                    }
                }

                if items.count >= 2 {
                    let doses = weight ? [] : vm.doseMarkers.map(\.atMs)
                    let changes = weight ? [] : vm.treatmentChanges
                    MeasureChart(
                        points: items.map { MeasurePoint(atMs: $0.raw.atMs, value: $0.displayValue) },
                        unit: latest.displayUnit,
                        doseMarkers: doses,
                        treatmentChanges: changes,
                        accessibilityText: chartDescription(items, weight: weight))
                        .padding(.top, 12)
                } else {
                    Text("Encore un relevé et la courbe se dessine.")
                        .font(EggFont.bodyS)
                        .foregroundStyle(palette.onSurfaceVariant)
                        .padding(.top, 10)
                }
            }
        }
    }

    private func chartDescription(_ items: [DisplayMeasurement], weight: Bool) -> String {
        let kind = weight
            ? "poids"
            : HormoneCatalog.kindLabel(vm.selectedHormone ?? HormoneCatalog.weight)
        guard let first = items.first, let last = items.last else { return kind }
        return "Courbe de \(kind), de \(MeasureFormat.monthYear(first.raw.atMs)) "
            + "à \(MeasureFormat.monthYear(last.raw.atMs)). "
            + "Dernière valeur \(MeasureFormat.value(last.displayValue)) \(last.displayUnit)."
    }

    // MARK: - The readings list

    private var readingsCard: some View {
        EggCard(variant: .low, paddingH: 18, paddingV: 6, spacing: 0) {
            let items = active.reversed().map { $0 }
            ForEach(Array(items.enumerated()), id: \.element.id) { index, measurement in
                readingRow(measurement)
                if index < items.count - 1 { CardRule() }
            }
        }
    }

    private func readingRow(_ m: DisplayMeasurement) -> some View {
        let date = MeasureFormat.fullDate(m.raw.atMs)
        let value = MeasureFormat.value(m.displayValue)
        // The subtitle carries the provenance, and the original unit whenever
        // the display unit differs — so the conversion can always be audited
        // against what the sheet said.
        let origin = m.raw.labName?.trimmingCharacters(in: .whitespacesAndNewlines)
            .nilIfEmpty ?? "Saisi à la main"
        let subtitle = m.displayUnit != m.raw.unit
            ? "\(origin) · \(MeasureFormat.value(m.raw.value)) \(m.raw.unit)"
            : origin
        return Button {
            editing = m
        } label: {
            HStack(spacing: Spacing.m) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(date)
                        .font(.eggCallout)
                        .foregroundStyle(palette.onSurface)
                    Text(subtitle)
                        .font(EggFont.bodyS)
                        .foregroundStyle(palette.onSurfaceVariant)
                }
                Spacer(minLength: Spacing.s)
                Text(value)
                    .font(EggFont.titleS)
                    .foregroundStyle(palette.onSurface)
                Text(m.displayUnit)
                    .font(EggFont.bodyS)
                    .foregroundStyle(palette.onSurfaceVariant)
            }
            .padding(.vertical, 13)
            .frame(minHeight: Metrics.touchTarget)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(date), \(value) \(m.displayUnit). \(subtitle)")
        .accessibilityHint("Modifier ce relevé")
    }
}

// ===========================================================================
// Chart pieces
// ===========================================================================

/// One plotted reading, already converted to the display unit.
///
/// Equatable so the chart can tell "the user tapped the reading that is already
/// pinned" from "they tapped a different one", which is what makes a second tap
/// dismiss the readout.
struct MeasurePoint: Equatable {
    let atMs: Int64
    let value: Double
}

/// The delta pill of §6.8. A rise is the one direction the handoff colours in
/// `successContainer`; a fall stays neutral, because whether a level going down
/// is good news depends entirely on the analyte, and eggshell does not judge.
/// The arrow is written as text: `trending_up` is missing from the icon set the
/// design system ships.
struct MeasureDeltaPill: View {
    @Environment(\.palette) private var palette
    let delta: Double

    var body: some View {
        let rising = delta > 0
        let flat = delta == 0
        let magnitude = MeasureFormat.delta(abs(delta))
        let glyph = flat ? "→" : (rising ? "↗" : "↘")
        let spoken = flat
            ? "Identique au relevé précédent"
            : (rising
                ? "En hausse de \(magnitude) depuis le relevé précédent"
                : "En baisse de \(magnitude) depuis le relevé précédent")
        HStack(spacing: 4) {
            Text(glyph).font(.system(size: 13, weight: .bold))
            Text(magnitude).font(EggFont.micro)
        }
        .foregroundStyle(rising ? palette.onSuccessContainer : palette.onSurfaceVariant)
        .padding(.horizontal, 10)
        .padding(.vertical, 5)
        .background(rising ? palette.successContainer : palette.surfaceContainerHigh, in: Capsule())
        .accessibilityElement()
        .accessibilityLabel(spoken)
    }
}

/// One axis gradation: the mark, then the word.
struct MeasureAxisKey: View {
    let label: String
    let color: Color
    let dashed: Bool

    var body: some View {
        HStack(spacing: 5) {
            if dashed {
                Rectangle().fill(color).frame(width: 14, height: 2)
            } else {
                Circle().fill(color).frame(width: 7, height: 7)
            }
            MicroLabel(label, color: color)
        }
        .accessibilityElement(children: .combine)
    }
}

/// Time-proportional area chart (§5.1). X follows the real dates, so a
/// six-month gap looks like one. Dose markers ride the interpolated curve in
/// `tertiary`; each treatment change is a dashed `secondary` vertical; the last
/// point is filled, bigger, and haloed.

// ===========================================================================
// Shared value + unit + date sheet, used both for the weight quick-add and for
// editing an existing reading (which under the hood is delete + re-add).
// Behaviour unchanged from before the refonte — restyled only (D6).
// ===========================================================================
private struct MeasurementSheet: View {
    @Environment(\.palette) private var palette
    @Environment(\.dismiss) private var dismiss

    let title: String
    let hint: String
    let initialValue: Double?
    let initialUnit: String
    let unitOptions: [String]
    let initialDate: Date
    let showDelete: Bool
    let onSave: (Double, String, Date) -> Void
    let onDelete: (() -> Void)?

    @State private var text = ""
    @State private var unit = ""
    @State private var date = Date()
    @State private var confirmDelete = false
    @State private var started = false

    private var parsed: Double? { Double(text.replacingOccurrences(of: ",", with: ".")) }
    private var canSave: Bool { (parsed ?? 0) > 0 && !unit.isEmpty }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: Metrics.blockGap) {
                    Text(hint)
                        .font(EggFont.bodyS)
                        .foregroundStyle(palette.onSurfaceVariant)
                        .fixedSize(horizontal: false, vertical: true)

                    EggCard(variant: .low) {
                        MicroLabel("VALEUR")
                        TextField("0", text: $text)
                            .keyboardType(.decimalPad)
                            .font(.system(size: 28, weight: .semibold))
                            .foregroundStyle(palette.onSurface)
                        MicroLabel("UNITÉ")
                        ChipFlowLayout(spacing: 7, lineSpacing: 4) {
                            ForEach(unitOptions, id: \.self) { option in
                                AnalyteChip(option, selected: unit == option) { unit = option }
                            }
                        }
                    }

                    EggCard(variant: .low) {
                        DatePicker(selection: $date, displayedComponents: [.date]) {
                            Text("Date").font(.eggBody).foregroundStyle(palette.onSurface)
                        }
                        .tint(palette.primary)
                    }

                    if showDelete, onDelete != nil {
                        Button(role: .destructive) { confirmDelete = true } label: {
                            Label("Supprimer ce relevé", systemImage: "trash")
                                .font(EggFont.label)
                                .frame(maxWidth: .infinity, minHeight: Metrics.touchTarget)
                        }
                        .buttonStyle(.plain)
                        .foregroundStyle(palette.error)
                    }
                    Color.clear.frame(height: Spacing.m)
                }
                .padding(.horizontal, Metrics.screenMargin)
                .padding(.top, Spacing.m)
            }
            .background(palette.surface.ignoresSafeArea())
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Annuler") { dismiss() }.foregroundStyle(palette.primary)
                }
            }
            .eggActionBar {
                ActionBarButton("Enregistrer", systemImage: "checkmark", enabled: canSave) {
                    if let value = parsed { onSave(value, unit, date) }
                    dismiss()
                }
            }
            .alert("Supprimer ce relevé ?", isPresented: $confirmDelete) {
                Button("Supprimer", role: .destructive) {
                    onDelete?()
                    dismiss()
                }
                Button("Annuler", role: .cancel) {}
            } message: {
                Text("Il disparaîtra de la courbe et de la liste. C’est sans retour.")
            }
            .onAppear {
                guard !started else { return }
                started = true
                if let value = initialValue { text = MeasureFormat.plain(value) }
                unit = unitOptions.contains(initialUnit) ? initialUnit : (unitOptions.first ?? initialUnit)
                date = initialDate
            }
        }
    }
}

private extension String {
    /// A blank lab name is the same as none: both mean « saisi à la main ».
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
