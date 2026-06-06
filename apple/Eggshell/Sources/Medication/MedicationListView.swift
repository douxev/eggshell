import SwiftUI
import TransitionCore

@MainActor
final class MedicationListViewModel: ObservableObject {
    @Published var loading = true
    @Published var medications: [Medication] = []
    @Published var error: String?

    func load(_ session: VaultService) async {
        loading = true
        do {
            medications = try await session.listMedications()
        } catch {
            self.error = describe(error)
        }
        loading = false
    }

    func kindLabel(_ raw: String) -> String { MedCatalog.kindLabel(raw) }

    func routeLabel(_ raw: String) -> String { MedCatalog.routeLabel(raw) }

    func routeIcon(_ raw: String) -> String {
        if raw.hasPrefix("injection_") { return "syringe" }
        switch raw {
        case "transdermal": return "bandage.fill"
        case "topical", "oral", "sublingual": return "pills.fill"
        default: return "cross.case.fill"
        }
    }

    func subtitle(_ med: Medication) -> String {
        var parts: [String] = []
        if let dose = med.defaultDose {
            let doseStr = dose.truncatingRemainder(dividingBy: 1) == 0
                ? String(Int(dose))
                : String(dose)
            if let unit = med.defaultDoseUnit, !unit.isEmpty {
                parts.append("\(doseStr) \(unit)")
            } else {
                parts.append(doseStr)
            }
        }
        parts.append(routeLabel(med.route))
        return parts.joined(separator: " · ")
    }
}

struct MedicationListView: View {
    @EnvironmentObject private var app: AppState
    @EnvironmentObject private var router: Router
    @Environment(\.palette) private var palette
    @StateObject private var vm = MedicationListViewModel()

    var body: some View {
        TabScaffold(title: "Médicaments") {
            if vm.loading {
                ProgressView().tint(palette.primary).frame(maxWidth: .infinity).padding()
            } else if vm.medications.isEmpty {
                EmptyStateCard(text: "Aucun médicament", systemImage: "pills")
            } else {
                ForEach(vm.medications, id: \.id) { med in
                    medicationCard(med)
                }
            }
            if let e = vm.error { ErrorBanner(message: e) }
        }
        .overlay(alignment: .bottomTrailing) {
            Button {
                router.push(.addMedication)
            } label: {
                Image(systemName: "plus").font(.title2.weight(.semibold)).frame(width: 60, height: 60)
            }
            .glassProminentButton().tint(palette.primary)
            .clipShape(Circle())
            .padding(Spacing.xl)
        }
        .task { if let s = app.session { await vm.load(s) } }
    }

    private func medicationCard(_ med: Medication) -> some View {
        Button {
            router.push(.medicationDetail(id: med.id))
        } label: {
            SectionCard {
                HStack(spacing: Spacing.m) {
                    Image(systemName: vm.routeIcon(med.route))
                        .font(.title3)
                        .foregroundStyle(palette.onPrimaryContainer)
                        .frame(width: 44, height: 44)
                        .background(palette.primaryContainer, in: Circle())
                    VStack(alignment: .leading, spacing: Spacing.xs) {
                        Text(med.name).font(.eggHeadline).foregroundStyle(palette.onSurface)
                        Pill(text: vm.kindLabel(med.kind))
                        Text(vm.subtitle(med)).font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.6))
                    }
                    Spacer()
                    Image(systemName: "chevron.right").font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.4))
                }
            }
        }
        .buttonStyle(.plain)
    }
}
