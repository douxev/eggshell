import SwiftUI
import TransitionCore

// PUSHED screen — log a new hormone measurement. Hormone is chosen via
// ChoiceChips (HormoneCatalog.kinds / kindLabel); the unit list is also the
// shared catalog (HormoneCatalog.units) but pre-filters down to the units that
// make clinical sense for the selected hormone. Save builds a
// NewHormoneMeasurement and dismisses. Mirrors AddHormoneMeasurementScreen.kt.

@MainActor
final class AddHormoneMeasurementViewModel: ObservableObject {
    @Published var status: FormStatus = .idle

    func save(_ measurement: NewHormoneMeasurement, session: VaultService) async -> Bool {
        status = .submitting
        do {
            _ = try await session.addHormoneMeasurement(measurement)
            status = .done
            return true
        } catch {
            status = .error(describe(error))
            return false
        }
    }
}

struct AddHormoneMeasurementView: View {
    @EnvironmentObject private var app: AppState
    @Environment(\.palette) private var palette
    @Environment(\.dismiss) private var dismiss
    @StateObject private var vm = AddHormoneMeasurementViewModel()

    @State private var hormone: String = HormoneCatalog.kinds.first ?? "estradiol"
    @State private var valueText: String = ""
    @State private var unit: String = HormoneCatalog.defaultUnit(HormoneCatalog.kinds.first ?? "estradiol")
        ?? HormoneCatalog.units.first ?? "pg/mL"
    @State private var date: Date = Date()
    @State private var labName: String = ""
    @State private var notes: String = ""

    // Clinically meaningful units per hormone, drawn from the shared catalog.
    // "other" falls back to the full HormoneCatalog.units list.
    private func units(for hormone: String) -> [String] {
        switch hormone {
        case "estradiol":    return ["pg/mL", "pmol/L"]
        case "testosterone": return ["ng/dL", "nmol/L", "ng/mL"]
        case "progesterone": return ["ng/mL", "nmol/L"]
        case "lh", "fsh":    return ["mIU/mL"]
        case "prolactin":    return ["ng/mL"]
        case "shbg":         return ["nmol/L"]
        default:             return HormoneCatalog.units
        }
    }

    private var parsedValue: Double? {
        Double(valueText.replacingOccurrences(of: ",", with: "."))
    }

    private var canSave: Bool {
        guard let v = parsedValue, v > 0 else { return false }
        return !unit.isEmpty && !vm.status.isSubmitting
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Spacing.l) {
                hormoneCard
                valueCard
                dateCard
                detailsCard
                if let e = vm.status.errorText { ErrorBanner(message: e) }
                saveButton
            }
            .padding(Spacing.l)
        }
        .navigationTitle("Nouvelle mesure")
    }

    private var hormoneCard: some View {
        SectionCard {
            Text("Hormone").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 96), spacing: Spacing.s)],
                      alignment: .leading, spacing: Spacing.s) {
                ForEach(HormoneCatalog.kinds, id: \.self) { id in
                    ChoiceChip(label: HormoneCatalog.kindLabel(id), selected: hormone == id) {
                        hormone = id
                        let opts = units(for: id)
                        if !opts.contains(unit) {
                            unit = HormoneCatalog.defaultUnit(id) ?? opts.first ?? unit
                        }
                    }
                }
            }
        }
    }

    private var valueCard: some View {
        SectionCard {
            Text("Valeur").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
            TextField("0", text: $valueText)
                .font(.eggBody)
                .keyboardType(.decimalPad)
                .textFieldStyle(.roundedBorder)
            Text("Unité").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 80), spacing: Spacing.s)],
                      alignment: .leading, spacing: Spacing.s) {
                ForEach(units(for: hormone), id: \.self) { u in
                    ChoiceChip(label: u, selected: unit == u) { unit = u }
                }
            }
        }
    }

    private var dateCard: some View {
        SectionCard {
            DatePicker("Date", selection: $date, displayedComponents: [.date])
                .font(.eggBody)
                .tint(palette.primary)
        }
    }

    private var detailsCard: some View {
        SectionCard {
            Text("Laboratoire").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
            TextField("Nom du laboratoire (facultatif)", text: $labName)
                .font(.eggBody)
                .textFieldStyle(.roundedBorder)
            Text("Notes").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
            TextField("Notes (facultatif)", text: $notes, axis: .vertical)
                .font(.eggBody)
                .lineLimit(3, reservesSpace: true)
                .textFieldStyle(.roundedBorder)
        }
    }

    private var saveButton: some View {
        Button {
            guard let value = parsedValue, let session = app.session else { return }
            let trimmedLab = labName.trimmingCharacters(in: .whitespacesAndNewlines)
            let trimmedNotes = notes.trimmingCharacters(in: .whitespacesAndNewlines)
            let measurement = NewHormoneMeasurement(
                atMs: Int64(date.timeIntervalSince1970 * 1000),
                hormone: hormone,
                value: value,
                unit: unit,
                labName: trimmedLab.isEmpty ? nil : trimmedLab,
                notes: trimmedNotes.isEmpty ? nil : trimmedNotes)
            Task {
                if await vm.save(measurement, session: session) { dismiss() }
            }
        } label: {
            if vm.status.isSubmitting {
                ProgressView().tint(palette.onPrimary).frame(maxWidth: .infinity)
            } else {
                Text("Enregistrer").font(.eggHeadline).frame(maxWidth: .infinity)
            }
        }
        .glassProminentButton()
        .tint(palette.primary)
        .disabled(!canSave)
    }
}
