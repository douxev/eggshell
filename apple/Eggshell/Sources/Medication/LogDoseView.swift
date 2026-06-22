import SwiftUI
import TransitionCore

// ===========================================================================
// PUSHED screen — log a dose for a medication. Fields: dose, unité, voie,
// date/heure, notes. For injection routes (MedCatalog.isInjection) a site
// picker from standardInjectionSites() is shown, pre-selecting the suggested
// next site (suggestNextInjectionSite). Parity with Android LogDoseScreen.
// All UI strings in French.
// ===========================================================================

@MainActor
final class LogDoseViewModel: ObservableObject {
    @Published var loading = true
    @Published var error: String?

    @Published var med: Medication?
    @Published var isInjection = false
    @Published var sites: [String] = []
    @Published var suggestedSite: String?

    // Editable fields
    @Published var doseText = ""
    @Published var unit = ""
    @Published var route = MedCatalog.routes.first ?? "oral"
    @Published var takenAt = Date()
    @Published var selectedSite: String?
    @Published var notes = ""

    @Published var saving = false

    func load(_ session: VaultService, medId: Int64) async {
        loading = true
        do {
            let m = try await session.getMedication(medId)
            med = m
            if let m {
                if let dose = m.defaultDose { doseText = formatDose(dose) }
                unit = m.defaultDoseUnit ?? ""
                route = m.route
                refreshInjection(for: m.route)
                if isInjection {
                    let suggestion = try await session.suggestNextInjectionSite(medicationId: medId)
                    suggestedSite = suggestion
                    selectedSite = suggestion
                }
            }
        } catch {
            self.error = describe(error)
        }
        loading = false
    }

    /// Recompute the injection state when the route changes (the user can pick a
    /// different route than the medication default).
    func refreshInjection(for route: String) {
        isInjection = MedCatalog.isInjection(route)
        if isInjection {
            if sites.isEmpty { sites = standardInjectionSites() }
        } else {
            selectedSite = nil
        }
    }

    private func formatDose(_ value: Double) -> String {
        if value == value.rounded() { return String(Int(value)) }
        return String(format: "%g", value)
    }

    func save(_ session: VaultService, medId: Int64) async -> Bool {
        saving = true
        defer { saving = false }
        let parsed = Double(doseText.replacingOccurrences(of: ",", with: "."))
        let takenMs = Int64(takenAt.timeIntervalSince1970 * 1000)
        do {
            _ = try await session.logDose(NewDoseEvent(
                medicationId: medId,
                takenAtMs: takenMs,
                dose: parsed,
                doseUnit: unit.isEmpty ? nil : unit,
                route: route,
                injectionSite: isInjection ? selectedSite : nil,
                notes: notes.isEmpty ? nil : notes))
            return true
        } catch {
            self.error = describe(error)
            return false
        }
    }
}

struct LogDoseView: View {
    let medId: Int64

    @EnvironmentObject private var app: AppState
    @Environment(\.palette) private var palette
    @Environment(\.dismiss) private var dismiss
    @StateObject private var vm = LogDoseViewModel()

    init(medId: Int64) {
        self.medId = medId
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Spacing.l) {
                if vm.loading {
                    ProgressView().tint(palette.primary).frame(maxWidth: .infinity).padding()
                } else {
                    if let med = vm.med {
                        Text(med.name).font(.eggTitle).foregroundStyle(palette.onSurface)
                    }
                    doseCard
                    routeCard
                    if vm.isInjection { siteCard }
                    dateCard
                    notesCard
                    saveButton
                }
                if let e = vm.error { ErrorBanner(message: e) }
            }
            .padding(Spacing.l)
        }
        .navigationTitle("Enregistrer une prise")
        .task { if let s = app.session { await vm.load(s, medId: medId) } }
    }

    private var doseCard: some View {
        SectionCard {
            Text("Dose").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
            HStack(spacing: Spacing.m) {
                TextField("0", text: $vm.doseText)
                    .keyboardType(.decimalPad)
                    .font(.eggHeadline)
                    .foregroundStyle(palette.onSurface)
                    .padding(Spacing.m)
                    .background(palette.surfaceContainerHigh, in: RoundedRectangle(cornerRadius: Corner.medium, style: .continuous))
                TextField("unité", text: $vm.unit)
                    .font(.eggHeadline)
                    .foregroundStyle(palette.onSurface)
                    .padding(Spacing.m)
                    .background(palette.surfaceContainerHigh, in: RoundedRectangle(cornerRadius: Corner.medium, style: .continuous))
                    .frame(maxWidth: 120)
            }
        }
    }

    private var routeCard: some View {
        SectionCard {
            Text("Voie d'administration").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
            FlowChips {
                ForEach(MedCatalog.routes, id: \.self) { value in
                    ChoiceChip(label: MedCatalog.routeLabel(value), selected: vm.route == value) {
                        vm.route = value
                        vm.refreshInjection(for: value)
                    }
                }
            }
        }
    }

    private var siteCard: some View {
        SectionCard {
            Text("Site d'injection").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
            if let suggested = vm.suggestedSite {
                Text("Suggéré : \(MedCatalog.injectionSiteLabel(suggested))")
                    .font(.eggCaption).foregroundStyle(palette.primary)
            }
            SiteChips(items: vm.sites, selected: vm.selectedSite, suggested: vm.suggestedSite) { site in
                vm.selectedSite = site
            }
        }
    }

    private var dateCard: some View {
        SectionCard {
            Text("Date et heure").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
            // A dose can only have been taken in the past — cap the picker at now.
            DatePicker("Prise le", selection: $vm.takenAt, in: ...Date(), displayedComponents: [.date, .hourAndMinute])
                .font(.eggBody)
                .tint(palette.primary)
        }
    }

    private var notesCard: some View {
        SectionCard {
            Text("Notes").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
            TextField("Remarques (optionnel)", text: $vm.notes, axis: .vertical)
                .lineLimit(3...6)
                .font(.eggBody)
                .foregroundStyle(palette.onSurface)
                .padding(Spacing.m)
                .background(palette.surfaceContainerHigh, in: RoundedRectangle(cornerRadius: Corner.medium, style: .continuous))
        }
    }

    private var saveButton: some View {
        Button {
            guard let session = app.session else { return }
            Task {
                if await vm.save(session, medId: medId) { dismiss() }
            }
        } label: {
            Text("Enregistrer")
                .font(.eggHeadline)
                .frame(maxWidth: .infinity)
                .padding(.vertical, Spacing.s)
        }
        .glassProminentButton()
        .tint(palette.primary)
        .disabled(vm.saving)
    }
}

// Wrapping grid of ChoiceChips for injection sites (labels via MedCatalog).
private struct SiteChips: View {
    let items: [String]
    let selected: String?
    let suggested: String?
    let onSelect: (String) -> Void

    var body: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 110), spacing: Spacing.s)], alignment: .leading, spacing: Spacing.s) {
            ForEach(items, id: \.self) { site in
                let label = MedCatalog.injectionSiteLabel(site)
                ChoiceChip(
                    label: site == suggested ? "\(label) ★" : label,
                    selected: selected == site
                ) {
                    onSelect(site)
                }
            }
        }
    }
}

// Private layout helper: wraps the route chips onto multiple lines.
private struct FlowChips: Layout {
    var spacing: CGFloat = Spacing.s

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout Void) -> CGSize {
        let maxWidth = proposal.width ?? .infinity
        var rowWidth: CGFloat = 0
        var rowHeight: CGFloat = 0
        var totalHeight: CGFloat = 0
        var totalWidth: CGFloat = 0
        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if rowWidth > 0, rowWidth + spacing + size.width > maxWidth {
                totalHeight += rowHeight + spacing
                totalWidth = max(totalWidth, rowWidth)
                rowWidth = size.width
                rowHeight = size.height
            } else {
                rowWidth += (rowWidth > 0 ? spacing : 0) + size.width
                rowHeight = max(rowHeight, size.height)
            }
        }
        totalHeight += rowHeight
        totalWidth = max(totalWidth, rowWidth)
        return CGSize(width: maxWidth.isFinite ? totalWidth : rowWidth, height: totalHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout Void) {
        var x = bounds.minX
        var y = bounds.minY
        var rowHeight: CGFloat = 0
        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if x > bounds.minX, x + size.width > bounds.maxX {
                x = bounds.minX
                y += rowHeight + spacing
                rowHeight = 0
            }
            subview.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
    }
}
