import SwiftUI
import TransitionCore

// Pushed screen: log a new hormone measurement. Hormone & unit are chosen via
// ChoiceChips (units depend on the selected hormone); "Autre" reveals a custom
// hormone TextField. Save builds a NewHormoneMeasurement and dismisses.

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

    // Canonical hormone keys (stored) paired with their French display labels.
    private static let hormones: [(key: String, label: String)] = [
        ("estradiol", "Œstradiol"),
        ("testosterone", "Testostérone"),
        ("progesterone", "Progestérone"),
        ("lh", "LH"),
        ("fsh", "FSH"),
        ("prolactin", "Prolactine"),
        ("shbg", "SHBG"),
        ("other", "Autre"),
    ]

    private static let allUnits = ["pg/mL", "pmol/L", "ng/dL", "nmol/L", "ng/mL", "mIU/mL"]

    @State private var hormone: String = "estradiol"
    @State private var customHormone: String = ""
    @State private var valueText: String = ""
    @State private var unit: String = "pg/mL"
    @State private var date: Date = Date()
    @State private var labName: String = ""
    @State private var notes: String = ""

    private func units(for hormone: String) -> [String] {
        switch hormone {
        case "estradiol": return ["pg/mL", "pmol/L"]
        case "testosterone": return ["ng/dL", "nmol/L", "ng/mL"]
        case "progesterone": return ["ng/mL", "nmol/L"]
        case "lh", "fsh": return ["mIU/mL"]
        case "prolactin": return ["ng/mL"]
        case "shbg": return ["nmol/L"]
        default: return Self.allUnits
        }
    }

    private var parsedValue: Double? {
        Double(valueText.replacingOccurrences(of: ",", with: "."))
    }

    private var resolvedHormone: String {
        let trimmed = customHormone.trimmingCharacters(in: .whitespacesAndNewlines)
        return hormone == "other" ? trimmed : hormone
    }

    private var canSave: Bool {
        parsedValue != nil && !resolvedHormone.isEmpty && !unit.isEmpty && !vm.status.isSubmitting
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
                ForEach(Self.hormones, id: \.key) { item in
                    ChoiceChip(label: item.label, selected: hormone == item.key) {
                        hormone = item.key
                        let opts = units(for: item.key)
                        if !opts.contains(unit) { unit = opts.first ?? unit }
                    }
                }
            }
            if hormone == "other" {
                TextField("Nom de l'hormone", text: $customHormone)
                    .font(.eggBody)
                    .textFieldStyle(.roundedBorder)
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
                hormone: resolvedHormone,
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
