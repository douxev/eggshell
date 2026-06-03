import SwiftUI
import TransitionCore
import Charts

@MainActor
final class HormonesViewModel: ObservableObject {
    @Published var loading = true
    @Published var error: String?

    // Hormones mode
    @Published var hormones: [String] = []
    @Published var selectedHormone: String?
    @Published var measurements: [HormoneMeasurement] = []

    // Weight mode
    @Published var weightMeasurements: [HormoneMeasurement] = []

    func load(_ session: VaultService) async {
        loading = true
        do {
            let all = try await session.distinctHormones()
            hormones = all.filter { $0 != "weight" }
            if selectedHormone == nil || !(hormones.contains(selectedHormone ?? "")) {
                selectedHormone = hormones.first
            }
            if let h = selectedHormone {
                measurements = try await session.listHormoneMeasurements(hormone: h)
                    .sorted { $0.atMs < $1.atMs }
            } else {
                measurements = []
            }
            weightMeasurements = try await session.listHormoneMeasurements(hormone: "weight")
                .sorted { $0.atMs > $1.atMs }
        } catch {
            self.error = describe(error)
        }
        loading = false
    }

    func selectHormone(_ hormone: String, session: VaultService) async {
        selectedHormone = hormone
        do {
            measurements = try await session.listHormoneMeasurements(hormone: hormone)
                .sorted { $0.atMs < $1.atMs }
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

    func addWeight(value: Double, unit: String, session: VaultService) async {
        do {
            _ = try await session.addHormoneMeasurement(NewHormoneMeasurement(
                atMs: Time.nowMs(),
                hormone: "weight",
                value: value,
                unit: unit,
                labName: nil,
                notes: nil))
            await load(session)
        } catch {
            self.error = describe(error)
        }
    }

    var latest: HormoneMeasurement? { measurements.last }
    var previous: HormoneMeasurement? {
        guard measurements.count >= 2 else { return nil }
        return measurements[measurements.count - 2]
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
    @State private var pendingDelete: HormoneMeasurement?

    var body: some View {
        TabScaffold(title: "Hormones") {
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

            Button {
                router.push(.importLab)
            } label: {
                HStack {
                    Image(systemName: "doc.text.viewfinder").foregroundStyle(palette.primary)
                    Text("Importer depuis une image").font(.eggCallout).foregroundStyle(palette.onSurface)
                    Spacer()
                }
                .padding(Spacing.l)
                .frame(maxWidth: .infinity)
                .glassCard(cornerRadius: Corner.large)
            }
            .buttonStyle(.plain)

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
            WeightAddSheet { value, unit in
                if let s = app.session { Task { await vm.addWeight(value: value, unit: unit, session: s) } }
            }
        }
        .confirmationDialog("Supprimer cette mesure ?",
                            isPresented: Binding(get: { pendingDelete != nil },
                                                 set: { if !$0 { pendingDelete = nil } }),
                            titleVisibility: .visible) {
            Button("Supprimer", role: .destructive) {
                if let m = pendingDelete, let s = app.session {
                    Task { await vm.deleteMeasurement(m.id, session: s) }
                }
                pendingDelete = nil
            }
            Button("Annuler", role: .cancel) { pendingDelete = nil }
        }
        .task { if let s = app.session { await vm.load(s) } }
    }

    // MARK: - Hormones

    private var hormoneSection: some View {
        VStack(alignment: .leading, spacing: Spacing.m) {
            if vm.hormones.isEmpty {
                EmptyStateCard(text: "Aucune mesure hormonale", systemImage: "drop")
            } else {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: Spacing.s) {
                        ForEach(vm.hormones, id: \.self) { h in
                            ChoiceChip(label: h, selected: vm.selectedHormone == h) {
                                if let s = app.session { Task { await vm.selectHormone(h, session: s) } }
                            }
                        }
                    }
                }

                if let latest = vm.latest {
                    latestCard(latest)
                }

                if vm.measurements.count >= 2 {
                    SectionCard {
                        Text("Évolution").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
                        Chart {
                            ForEach(vm.measurements, id: \.id) { m in
                                LineMark(
                                    x: .value("Date", dateOf(m)),
                                    y: .value("Valeur", displayValue(m)))
                            }
                        }
                        .frame(height: 180)
                    }
                }

                historyCard
            }
        }
    }

    private func latestCard(_ m: HormoneMeasurement) -> some View {
        SectionCard {
            Text("Dernière mesure").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
            HStack(alignment: .firstTextBaseline, spacing: Spacing.s) {
                Text(formatValue(displayValue(m))).font(.eggDisplay).foregroundStyle(palette.onSurface)
                Text(displayUnit(m)).font(.eggCallout).foregroundStyle(palette.onSurface.opacity(0.6))
                Spacer()
                if let delta = deltaText() {
                    Pill(text: delta)
                }
            }
            Text(dateLabel(m.atMs)).font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.6))
        }
    }

    private var historyCard: some View {
        SectionCard {
            Text("Historique").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
            ForEach(vm.measurements.reversed(), id: \.id) { m in
                Button {
                    pendingDelete = m
                } label: {
                    HStack {
                        Text(dateLabel(m.atMs)).font(.eggCallout).foregroundStyle(palette.onSurface)
                        Spacer()
                        Text("\(formatValue(displayValue(m))) \(displayUnit(m))")
                            .font(.eggCallout).foregroundStyle(palette.onSurface.opacity(0.8))
                    }
                    .padding(.vertical, Spacing.xs)
                }
                .buttonStyle(.plain)
            }
        }
    }

    // MARK: - Weight

    private var weightSection: some View {
        VStack(alignment: .leading, spacing: Spacing.m) {
            if vm.weightMeasurements.isEmpty {
                EmptyStateCard(text: "Aucune mesure de poids", systemImage: "scalemass")
            } else {
                SectionCard {
                    Text("Poids").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
                    ForEach(vm.weightMeasurements, id: \.id) { m in
                        Button {
                            pendingDelete = m
                        } label: {
                            HStack {
                                Text(dateLabel(m.atMs)).font(.eggCallout).foregroundStyle(palette.onSurface)
                                Spacer()
                                Text("\(formatValue(m.value)) \(m.unit)")
                                    .font(.eggCallout).foregroundStyle(palette.onSurface.opacity(0.8))
                            }
                            .padding(.vertical, Spacing.xs)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
    }

    // MARK: - Display helpers

    private func displayUnit(_ m: HormoneMeasurement) -> String {
        if let target = hormoneUnits.unit(for: m.hormone), !target.isEmpty {
            return target
        }
        return m.unit
    }

    private func displayValue(_ m: HormoneMeasurement) -> Double {
        if let target = hormoneUnits.unit(for: m.hormone), !target.isEmpty, target != m.unit,
           let converted = convertHormoneValue(value: m.value, fromUnit: m.unit, toUnit: target, hormone: m.hormone) {
            return converted
        }
        return m.value
    }

    private func deltaText() -> String? {
        guard let latest = vm.latest, let prev = vm.previous else { return nil }
        let d = displayValue(latest) - displayValue(prev)
        let sign = d >= 0 ? "+" : "−"
        return "\(sign)\(formatValue(abs(d))) \(displayUnit(latest))"
    }

    private func formatValue(_ v: Double) -> String {
        if v == v.rounded() { return String(format: "%.0f", v) }
        return String(format: "%.2f", v)
    }

    private func dateOf(_ m: HormoneMeasurement) -> Date {
        Date(timeIntervalSince1970: Double(m.atMs) / 1000.0)
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

// Inline weight-add dialog presented as a sheet.
private struct WeightAddSheet: View {
    @Environment(\.palette) private var palette
    @Environment(\.dismiss) private var dismiss
    let onAdd: (Double, String) -> Void

    @State private var text = ""
    @State private var unit = "kg"

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.m) {
            Text("Ajouter un poids").font(.eggTitle).foregroundStyle(palette.onSurface)

            TextField("Valeur", text: $text)
                .keyboardType(.decimalPad)
                .padding(Spacing.m)
                .glassCard(cornerRadius: Corner.medium)

            HStack(spacing: Spacing.s) {
                ChoiceChip(label: "kg", selected: unit == "kg") { unit = "kg" }
                ChoiceChip(label: "lb", selected: unit == "lb") { unit = "lb" }
            }

            HStack {
                Button("Annuler") { dismiss() }
                    .glassButton().tint(palette.secondary)
                Spacer()
                Button("Enregistrer") {
                    if let v = Double(text.replacingOccurrences(of: ",", with: ".")) {
                        onAdd(v, unit)
                    }
                    dismiss()
                }
                .glassProminentButton().tint(palette.primary)
                .disabled(Double(text.replacingOccurrences(of: ",", with: ".")) == nil)
            }
        }
        .padding(Spacing.xl)
        .presentationDetents([.medium])
    }
}
