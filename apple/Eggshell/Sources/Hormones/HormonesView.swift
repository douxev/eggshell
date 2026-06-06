import SwiftUI
import TransitionCore
import Charts

// ===========================================================================
// TAB ROOT — hormone tracking. Mirrors HormonesScreen.kt.
//
// Two modes (when weight tracking is enabled): "Hormones" and "Poids".
//  • Hormones: hormone-selector chips, an evolution chart + a "latest" card,
//    and a tappable history list. Display values are converted to the user's
//    preferred unit via HormoneUnitStore.effectiveUnit(for:) +
//    convertHormoneValue(...) (HormoneCatalog.convertWeight for weight).
//  • Poids: a single implicit "weight" kind, quick-add via an inline sheet.
//
// The Rust core has no `updateHormoneMeasurement`, so editing a row =
// deleteHormoneMeasurement(id) then addHormoneMeasurement(new), driven from
// an edit sheet.
//
// Toolbar / links: gear → settings (via ScreenHeader); a row button →
// Route.importLab and Route.hormoneUnits; FAB → Route.addHormone (or the
// weight quick-add sheet in Poids mode).
// ===========================================================================

/// A measurement paired with its display-time conversion to the preferred unit.
struct DisplayMeasurement: Identifiable {
    let raw: HormoneMeasurement
    let displayValue: Double
    let displayUnit: String
    var id: Int64 { raw.id }
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

    /// Unit-resolution closures supplied by the view (the store is @MainActor
    /// and lives in the environment). `effective` → effectiveUnit(for:).
    private var effective: (String) -> String? = { _ in nil }

    func configure(effective: @escaping (String) -> String?) {
        self.effective = effective
    }

    func load(_ session: VaultService) async {
        loading = true
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
            weightMeasurements = convert(rawWeight.sorted { $0.atMs < $1.atMs }, hormone: HormoneCatalog.weight)
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
        } catch {
            self.error = describe(error)
        }
    }

    func deleteMeasurement(_ id: Int64, session: VaultService) async {
        do {
            try await session.deleteHormoneMeasurement(id)
            await load(session)
        } catch {
            self.error = describe(error)
        }
    }

    /// Edit = delete + re-add (the core lacks an update). The hormone kind,
    /// lab name and notes carry over from the original record.
    func updateMeasurement(original: HormoneMeasurement, value: Double, unit: String, atMs: Int64,
                           session: VaultService) async {
        do {
            try await session.deleteHormoneMeasurement(original.id)
            _ = try await session.addHormoneMeasurement(NewHormoneMeasurement(
                atMs: atMs,
                hormone: original.hormone,
                value: value,
                unit: unit,
                labName: original.labName,
                notes: original.notes))
            await load(session)
        } catch {
            self.error = describe(error)
        }
    }

    func addWeight(value: Double, unit: String, session: VaultService) async {
        do {
            _ = try await session.addHormoneMeasurement(NewHormoneMeasurement(
                atMs: Time.nowMs(),
                hormone: HormoneCatalog.weight,
                value: value,
                unit: unit,
                labName: nil,
                notes: nil))
            await load(session)
        } catch {
            self.error = describe(error)
        }
    }

    // Apply the preferred-unit conversion to each raw measurement.
    private func convert(_ raw: [HormoneMeasurement], hormone: String) -> [DisplayMeasurement] {
        let target = effective(hormone)
        return raw.map { m in
            let converted: Double?
            if let t = target, !t.isEmpty, t != m.unit {
                if hormone == HormoneCatalog.weight {
                    converted = HormoneCatalog.convertWeight(m.value, from: m.unit, to: t)
                } else {
                    converted = convertHormoneValue(value: m.value, fromUnit: m.unit, toUnit: t, hormone: hormone)
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

    @State private var weightMode = false
    @State private var showWeightDialog = false
    @State private var editing: DisplayMeasurement?

    private var active: [DisplayMeasurement] {
        weightMode ? vm.weightMeasurements : vm.measurements
    }

    var body: some View {
        TabScaffold(title: "Courbes") {
            if features.weight {
                HStack(spacing: Spacing.s) {
                    ChoiceChip(label: "Hormones", selected: !weightMode) { weightMode = false }
                    ChoiceChip(label: "Poids", selected: weightMode) { weightMode = true }
                }
            }

            if vm.loading {
                ProgressView().tint(palette.primary).frame(maxWidth: .infinity).padding()
            } else if weightMode {
                weightSection
            } else {
                hormoneSection
            }

            if let e = vm.error { ErrorBanner(message: e) }
        }
        .overlay(alignment: .bottomTrailing) {
            Button {
                if weightMode {
                    showWeightDialog = true
                } else {
                    router.push(.addHormone)
                }
            } label: {
                Image(systemName: "plus").font(.title2.weight(.semibold)).frame(width: 60, height: 60)
            }
            .glassProminentButton().tint(palette.primary)
            .clipShape(Circle())
            .padding(Spacing.xl)
        }
        .sheet(isPresented: $showWeightDialog) {
            MeasurementSheet(
                title: "Ajouter un poids",
                initialValue: nil,
                initialUnit: "kg",
                unitOptions: HormoneCatalog.weightUnits,
                initialDate: Date(),
                showDelete: false,
                onSave: { value, unit, _ in
                    if let s = app.session { Task { await vm.addWeight(value: value, unit: unit, session: s) } }
                },
                onDelete: nil)
        }
        .sheet(item: $editing) { entry in
            MeasurementSheet(
                title: entry.raw.hormone == HormoneCatalog.weight ? "Modifier le poids" : "Modifier la mesure",
                initialValue: entry.raw.value,
                initialUnit: entry.raw.unit,
                unitOptions: entry.raw.hormone == HormoneCatalog.weight ? HormoneCatalog.weightUnits : HormoneCatalog.units,
                initialDate: Date(timeIntervalSince1970: Double(entry.raw.atMs) / 1000.0),
                showDelete: true,
                onSave: { value, unit, date in
                    if let s = app.session {
                        Task { await vm.updateMeasurement(original: entry.raw, value: value, unit: unit,
                                                          atMs: Int64(date.timeIntervalSince1970 * 1000), session: s) }
                    }
                },
                onDelete: {
                    if let s = app.session { Task { await vm.deleteMeasurement(entry.raw.id, session: s) } }
                })
        }
        .task {
            vm.configure(effective: { hormoneUnits.effectiveUnit(for: $0) })
            if let s = app.session { await vm.load(s) }
        }
    }

    // MARK: - Hormones mode

    private var hormoneSection: some View {
        VStack(alignment: .leading, spacing: Spacing.m) {
            if !vm.hormones.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: Spacing.s) {
                        ForEach(vm.hormones, id: \.self) { h in
                            ChoiceChip(label: HormoneCatalog.kindLabel(h), selected: vm.selectedHormone == h) {
                                if let s = app.session { Task { await vm.selectHormone(h, session: s) } }
                            }
                        }
                    }
                }
            }

            importRow
            unitsRow

            if active.isEmpty {
                EmptyStateCard(text: "Aucune mesure hormonale", systemImage: "drop")
            } else {
                latestCard(weight: false)
                evolutionCard
                historyCard
            }
        }
    }

    // MARK: - Weight mode

    private var weightSection: some View {
        VStack(alignment: .leading, spacing: Spacing.m) {
            if active.isEmpty {
                EmptyStateCard(text: "Aucune mesure de poids", systemImage: "scalemass")
            } else {
                latestCard(weight: true)
                evolutionCard
                historyCard
            }
        }
    }

    // MARK: - Shared cards

    private var importRow: some View {
        Button {
            router.push(.importLab)
        } label: {
            HStack {
                Image(systemName: "doc.text.viewfinder").foregroundStyle(palette.primary)
                Text("Importer un résultat de labo").font(.eggCallout).foregroundStyle(palette.onSurface)
                Spacer()
                Image(systemName: "chevron.right").font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.4))
            }
            .padding(Spacing.l)
            .frame(maxWidth: .infinity)
            .glassCard(cornerRadius: Corner.large)
        }
        .buttonStyle(.plain)
    }

    private var unitsRow: some View {
        Button {
            router.push(.hormoneUnits)
        } label: {
            HStack {
                Image(systemName: "ruler").foregroundStyle(palette.primary)
                Text("Unités d'affichage").font(.eggCallout).foregroundStyle(palette.onSurface)
                Spacer()
                Image(systemName: "chevron.right").font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.4))
            }
            .padding(Spacing.l)
            .frame(maxWidth: .infinity)
            .glassCard(cornerRadius: Corner.large)
        }
        .buttonStyle(.plain)
    }

    private func latestCard(weight: Bool) -> some View {
        let items = active
        let latest = items.last
        let prev = items.count >= 2 ? items[items.count - 2] : nil
        return SectionCard {
            Text(weight ? "Dernière pesée" : "Dernière mesure")
                .font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
            if let latest {
                HStack(alignment: .firstTextBaseline, spacing: Spacing.s) {
                    Text(formatValue(latest.displayValue)).font(.eggDisplay).foregroundStyle(palette.onSurface)
                    Text(latest.displayUnit).font(.eggCallout).foregroundStyle(palette.onSurface.opacity(0.6))
                    Spacer()
                    if let prev {
                        Pill(text: deltaText(latest: latest, prev: prev))
                    }
                }
                Text(dateLabel(latest.raw.atMs)).font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.6))
            }
        }
    }

    @ViewBuilder
    private var evolutionCard: some View {
        let items = active
        if items.count >= 2 {
            SectionCard {
                Text("Évolution").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
                Chart {
                    ForEach(items) { m in
                        LineMark(
                            x: .value("Date", Date(timeIntervalSince1970: Double(m.raw.atMs) / 1000.0)),
                            y: .value("Valeur", m.displayValue))
                        AreaMark(
                            x: .value("Date", Date(timeIntervalSince1970: Double(m.raw.atMs) / 1000.0)),
                            y: .value("Valeur", m.displayValue))
                        .foregroundStyle(palette.primary.opacity(0.18))
                    }
                    .foregroundStyle(palette.primary)
                    .interpolationMethod(.catmullRom)
                }
                .frame(height: 180)
            }
        }
    }

    private var historyCard: some View {
        SectionCard {
            Text("Historique").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
            ForEach(active.reversed()) { m in
                Button {
                    editing = m
                } label: {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(dateLabel(m.raw.atMs)).font(.eggCallout).foregroundStyle(palette.onSurface)
                            if m.displayUnit != m.raw.unit {
                                // Show the original entry too so the conversion is auditable.
                                Text("\(formatValue(m.raw.value)) \(m.raw.unit)")
                                    .font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.5))
                            }
                        }
                        Spacer()
                        Text("\(formatValue(m.displayValue)) \(m.displayUnit)")
                            .font(.eggCallout).foregroundStyle(palette.onSurface.opacity(0.8))
                    }
                    .padding(.vertical, Spacing.xs)
                }
                .buttonStyle(.plain)
            }
        }
    }

    // MARK: - Helpers

    private func deltaText(latest: DisplayMeasurement, prev: DisplayMeasurement) -> String {
        let d = latest.displayValue - prev.displayValue
        let sign = d >= 0 ? "+" : "−"
        return "\(sign)\(formatValue(abs(d))) \(latest.displayUnit)"
    }

    private func formatValue(_ v: Double) -> String {
        if v == v.rounded() { return String(format: "%.0f", v) }
        return String(format: "%.2f", v)
    }

    private func dateLabel(_ ms: Int64) -> String {
        let date = Date(timeIntervalSince1970: Double(ms) / 1000.0)
        let f = DateFormatter()
        f.locale = Locale(identifier: "fr")
        f.dateStyle = .medium
        f.timeStyle = .none
        return f.string(from: date)
    }
}

// ===========================================================================
// Shared value+unit+date sheet, used both for the weight quick-add and for
// editing an existing measurement (which under the hood is delete + re-add).
// ===========================================================================
private struct MeasurementSheet: View {
    @Environment(\.palette) private var palette
    @Environment(\.dismiss) private var dismiss

    let title: String
    let initialValue: Double?
    let initialUnit: String
    let unitOptions: [String]
    let initialDate: Date
    let showDelete: Bool
    let onSave: (Double, String, Date) -> Void
    let onDelete: (() -> Void)?

    @State private var text: String = ""
    @State private var unit: String = ""
    @State private var date: Date = Date()
    @State private var showDeleteConfirm = false
    @State private var started = false

    private var parsed: Double? { Double(text.replacingOccurrences(of: ",", with: ".")) }
    private var canSave: Bool { (parsed ?? 0) > 0 && !unit.isEmpty }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Spacing.l) {
                Text(title).font(.eggTitle).foregroundStyle(palette.onSurface)

                SectionCard {
                    Text("Valeur").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
                    TextField("0", text: $text)
                        .keyboardType(.decimalPad)
                        .font(.eggBody)
                        .textFieldStyle(.roundedBorder)
                    Text("Unité").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 80), spacing: Spacing.s)],
                              alignment: .leading, spacing: Spacing.s) {
                        ForEach(unitOptions, id: \.self) { u in
                            ChoiceChip(label: u, selected: unit == u) { unit = u }
                        }
                    }
                }

                SectionCard {
                    DatePicker("Date", selection: $date, displayedComponents: [.date])
                        .font(.eggBody)
                        .tint(palette.primary)
                }

                HStack {
                    Button("Annuler") { dismiss() }
                        .glassButton().tint(palette.secondary)
                    Spacer()
                    Button("Enregistrer") {
                        if let v = parsed { onSave(v, unit, date) }
                        dismiss()
                    }
                    .glassProminentButton().tint(palette.primary)
                    .disabled(!canSave)
                }

                if showDelete, onDelete != nil {
                    Button {
                        showDeleteConfirm = true
                    } label: {
                        Label("Supprimer", systemImage: "trash").font(.eggCallout).frame(maxWidth: .infinity)
                    }
                    .glassButton().tint(palette.error)
                }
            }
            .padding(Spacing.xl)
        }
        .confirmationDialog("Supprimer cette mesure ?",
                            isPresented: $showDeleteConfirm,
                            titleVisibility: .visible) {
            Button("Supprimer", role: .destructive) {
                onDelete?()
                dismiss()
            }
            Button("Annuler", role: .cancel) {}
        }
        .onAppear {
            guard !started else { return }
            started = true
            if let v = initialValue { text = formatInitial(v) }
            unit = unitOptions.contains(initialUnit) ? initialUnit : (unitOptions.first ?? initialUnit)
            date = initialDate
        }
    }

    private func formatInitial(_ v: Double) -> String {
        if v == v.rounded() { return String(format: "%.0f", v) }
        return String(v)
    }
}
