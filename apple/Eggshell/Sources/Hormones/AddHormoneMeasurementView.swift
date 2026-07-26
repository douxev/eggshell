import SwiftUI
import TransitionCore

// PUSHED screen — « Nouveau relevé » (§6.8, the FAB of Mesures). Mirrors
// AddHormoneMeasurementScreen.kt.
//
// The value is stored exactly as it is typed: the unit chips pick what the sheet
// says, and any conversion happens at display time only (Réglages → Apparence &
// langue owns the display unit). Nothing here rounds or normalises a reading a
// doctor may read back.

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
    @State private var valueText = ""
    @State private var unit: String = HormoneCatalog.defaultUnit(HormoneCatalog.kinds.first ?? "estradiol")
        ?? HormoneCatalog.units.first ?? "pg/mL"
    @State private var date = Date()
    @State private var labName = ""
    @State private var notes = ""

    /// Clinically meaningful units per analyte, drawn from the shared catalog.
    /// "other" falls back to the full `HormoneCatalog.units` list.
    private func units(for hormone: String) -> [String] {
        switch hormone {
        case "estradiol":    return ["pg/mL", "pmol/L"]
        case "testosterone": return ["ng/dL", "nmol/L", "ng/mL"]
        case "progesterone": return ["ng/mL", "nmol/L"]
        case "lh", "fsh":    return ["mIU/mL"]
        case "prolactin":    return ["ng/mL"]
        case "shbg":         return ["nmol/L"]
        case "bp_systolic", "bp_diastolic": return ["mmHg"]
        case "hemoglobin":   return ["g/dL"]
        case "hematocrit":   return ["%"]
        default:             return HormoneCatalog.units
        }
    }

    private var parsedValue: Double? {
        Double(valueText.replacingOccurrences(of: ",", with: "."))
    }

    private var canSave: Bool {
        guard let value = parsedValue, value > 0 else { return false }
        return !unit.isEmpty && !vm.status.isSubmitting
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text("Recopie ce que dit ta feuille : la valeur est gardée exactement comme tu la saisis.")
                    .font(EggFont.bodyS)
                    .foregroundStyle(palette.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)

                analyteCard
                valueCard
                dateCard
                detailsCard
                if let message = vm.status.errorText { ErrorCardView(message) }
                Color.clear.frame(height: Spacing.m)
            }
            .padding(.horizontal, Metrics.screenMargin)
            .padding(.top, Spacing.m)
        }
        .measuresScreen("Nouveau relevé")
        .eggActionBar {
            ActionBarButton("Enregistrer", systemImage: "checkmark", enabled: canSave) { save() }
        }
        .sensoryFeedback(.success, trigger: vm.status == .done)
    }

    private var analyteCard: some View {
        EggCard(variant: .low) {
            MicroLabel("ANALYSE")
            ChipFlowLayout(spacing: 7, lineSpacing: 4) {
                ForEach(HormoneCatalog.kinds, id: \.self) { kind in
                    AnalyteChip(HormoneCatalog.kindLabel(kind), selected: hormone == kind) {
                        hormone = kind
                        let options = units(for: kind)
                        if !options.contains(unit) {
                            unit = HormoneCatalog.defaultUnit(kind) ?? options.first ?? unit
                        }
                    }
                }
            }
        }
    }

    private var valueCard: some View {
        EggCard(variant: .low) {
            MicroLabel("VALEUR")
            TextField("0", text: $valueText)
                .keyboardType(.decimalPad)
                .font(.system(size: 28, weight: .semibold))
                .foregroundStyle(palette.onSurface)
            MicroLabel("UNITÉ")
            ChipFlowLayout(spacing: 7, lineSpacing: 4) {
                ForEach(units(for: hormone), id: \.self) { option in
                    AnalyteChip(option, selected: unit == option) { unit = option }
                }
            }
        }
    }

    private var dateCard: some View {
        EggCard(variant: .low) {
            DatePicker(selection: $date, displayedComponents: [.date]) {
                Text("Date du prélèvement").font(.eggBody).foregroundStyle(palette.onSurface)
            }
            .tint(palette.primary)
        }
    }

    private var detailsCard: some View {
        EggCard(variant: .low) {
            MicroLabel("LABORATOIRE")
            TextField("Facultatif", text: $labName)
                .font(.eggBody)
                .textFieldStyle(.roundedBorder)
            MicroLabel("NOTES")
            TextField("Facultatif", text: $notes, axis: .vertical)
                .font(.eggBody)
                .lineLimit(3, reservesSpace: true)
                .textFieldStyle(.roundedBorder)
        }
    }

    private func save() {
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
    }
}
