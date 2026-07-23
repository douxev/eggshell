import SwiftUI
import TransitionCore

// ===========================================================================
// PUSHED screen — log a dose for a medication. Fields: dose, unité, voie,
// date/heure, notes. For injection routes (MedCatalog.isInjection, keyed off
// the SELECTED route) a site picker from standardInjectionSites() is shown,
// pre-selecting the suggested next site (suggestNextInjectionSite).
// Create only: a « Période » mode logs one dose per day at a chosen hour over
// a span in one core transaction (logDoses). With editDoseId set, the screen
// edits that recorded dose in place (updateDose), carrying the schedule
// linkage over. Parity with Android LogDoseScreen. All UI strings in French.
// ===========================================================================

@MainActor
final class LogDoseViewModel: ObservableObject {
    @Published var loading = true
    @Published var error: String?

    @Published var med: Medication?
    @Published var isInjection = false
    @Published var sites: [String] = []
    @Published var suggestedSite: String?
    /// The recorded dose being edited (nil in create mode). Kept so its
    /// schedule linkage (status/scheduledAtMs/scheduleId) survives the edit.
    @Published var loadedDose: DoseEvent?

    // Editable fields
    @Published var doseText = ""
    @Published var unit = ""
    @Published var route = MedCatalog.routes.first ?? "oral"
    @Published var takenAt = Date()
    @Published var selectedSite: String?
    @Published var notes = ""

    // Range mode (create only): declare a daily intake over a whole span
    // (e.g. a topical applied every day for months) in one action.
    @Published var rangeMode = false
    @Published var rangeStart = Date()
    @Published var rangeEnd = Date()
    @Published var rangeTime = LogDoseViewModel.defaultRangeTime()

    @Published var saving = false

    /// Days in the selected span, inclusive; 0 when the range is inverted.
    var rangeDayCount: Int {
        let cal = Calendar.current
        let start = cal.startOfDay(for: rangeStart)
        let end = cal.startOfDay(for: rangeEnd)
        guard let days = cal.dateComponents([.day], from: start, to: end).day, days >= 0 else { return 0 }
        return days + 1
    }

    /// Default time-of-day for range mode: 12:00 (only h/m are read back).
    private static func defaultRangeTime() -> Date {
        Calendar.current.date(bySettingHour: 12, minute: 0, second: 0, of: Date()) ?? Date()
    }

    func load(_ session: VaultService, medId: Int64, editDoseId: Int64?) async {
        loading = true
        do {
            let m = try await session.getMedication(medId)
            med = m
            if let m {
                if let dose = m.defaultDose { doseText = formatDose(dose) }
                unit = m.defaultDoseUnit ?? ""
                route = m.route
                refreshInjection(for: m.route)
            }
            if let id = editDoseId {
                // Editing: seed every field from the recorded dose.
                if let d = try await session.getDose(id) {
                    loadedDose = d
                    doseText = d.dose.map { formatDose($0) } ?? ""
                    unit = d.doseUnit ?? ""
                    route = d.route ?? med?.route ?? route
                    refreshInjection(for: route)
                    selectedSite = d.injectionSite
                    notes = d.notes ?? ""
                    takenAt = Date(timeIntervalSince1970: Double(d.takenAtMs) / 1000.0)
                } else {
                    // Surface a failed load — Save stays gated on loadedDose,
                    // but the user must not stare at a silently empty form
                    // believing it's the record.
                    self.error = "Prise introuvable."
                }
            } else if isInjection {
                let suggestion = try await session.suggestNextInjectionSite(medicationId: medId)
                suggestedSite = suggestion
                selectedSite = suggestion
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
        // On edit, carry the schedule linkage over untouched — the dose keeps
        // counting against whichever reminder produced it.
        let prev = loadedDose
        let event = NewDoseEvent(
            medicationId: medId,
            takenAtMs: takenMs,
            dose: parsed,
            doseUnit: unit.isEmpty ? nil : unit,
            route: route,
            injectionSite: isInjection ? selectedSite : nil,
            notes: notes.isEmpty ? nil : notes,
            status: prev?.status ?? "taken",
            scheduledAtMs: prev?.scheduledAtMs,
            scheduleId: prev?.scheduleId)
        do {
            if let prev {
                _ = try await session.updateDose(prev.id, event)
            } else {
                _ = try await session.logDose(event)
            }
            return true
        } catch {
            self.error = describe(error)
            return false
        }
    }

    /// Log the same intake once per day across a span — one core transaction.
    func saveRange(_ session: VaultService, medId: Int64) async -> Bool {
        saving = true
        defer { saving = false }
        let parsed = Double(doseText.replacingOccurrences(of: ",", with: "."))
        let cal = Calendar.current
        let comps = cal.dateComponents([.hour, .minute], from: rangeTime)
        let endDay = cal.startOfDay(for: rangeEnd)
        var day = cal.startOfDay(for: rangeStart)
        var doses: [NewDoseEvent] = []
        while day <= endDay {
            let at = cal.date(bySettingHour: comps.hour ?? 12, minute: comps.minute ?? 0, second: 0, of: day) ?? day
            doses.append(NewDoseEvent(
                medicationId: medId,
                takenAtMs: Int64(at.timeIntervalSince1970 * 1000),
                dose: parsed,
                doseUnit: unit.isEmpty ? nil : unit,
                route: route,
                injectionSite: isInjection ? selectedSite : nil,
                notes: notes.isEmpty ? nil : notes))
            day = cal.date(byAdding: .day, value: 1, to: day) ?? day.addingTimeInterval(86_400)
        }
        do {
            _ = try await session.logDoses(doses)
            return true
        } catch {
            self.error = describe(error)
            return false
        }
    }
}

struct LogDoseView: View {
    let medId: Int64
    /// When set, the screen edits this recorded dose instead of logging a new one.
    let editDoseId: Int64?

    @EnvironmentObject private var app: AppState
    @Environment(\.palette) private var palette
    @Environment(\.dismiss) private var dismiss
    @StateObject private var vm = LogDoseViewModel()

    init(medId: Int64, editDoseId: Int64? = nil) {
        self.medId = medId
        self.editDoseId = editDoseId
    }

    private var isEditing: Bool { editDoseId != nil }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Spacing.l) {
                if vm.loading {
                    ProgressView().tint(palette.primary).frame(maxWidth: .infinity).padding()
                } else {
                    if let med = vm.med {
                        Text(med.name).font(.eggTitle).foregroundStyle(palette.onSurface)
                    }
                    if !isEditing { modeCard }
                    doseCard
                    routeCard
                    if vm.isInjection { siteCard }
                    if vm.rangeMode && !isEditing { rangeCard } else { dateCard }
                    notesCard
                    saveButton
                }
                if let e = vm.error { ErrorBanner(message: e) }
            }
            .padding(Spacing.l)
        }
        .navigationTitle(isEditing ? "Modifier la prise" : "Enregistrer une prise")
        .task { if let s = app.session { await vm.load(s, medId: medId, editDoseId: editDoseId) } }
    }

    private var modeCard: some View {
        Picker("Mode", selection: $vm.rangeMode) {
            Text("Une prise").tag(false)
            Text("Période").tag(true)
        }
        .pickerStyle(.segmented)
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

    private var rangeCard: some View {
        SectionCard {
            Text("Période").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
            DatePicker("Début", selection: $vm.rangeStart, in: ...Date(), displayedComponents: [.date])
                .font(.eggBody)
                .tint(palette.primary)
            DatePicker("Fin", selection: $vm.rangeEnd, in: ...Date(), displayedComponents: [.date])
                .font(.eggBody)
                .tint(palette.primary)
            DatePicker("Heure de chaque prise", selection: $vm.rangeTime, displayedComponents: .hourAndMinute)
                .font(.eggBody)
                .tint(palette.primary)
            if vm.rangeDayCount >= 1 {
                Text("\(vm.rangeDayCount) prises seront enregistrées.")
                    .font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.6))
            }
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
                let ok: Bool
                if vm.rangeMode && !isEditing {
                    ok = await vm.saveRange(session, medId: medId)
                } else {
                    ok = await vm.save(session, medId: medId)
                }
                if ok { dismiss() }
            }
        } label: {
            Text("Enregistrer")
                .font(.eggHeadline)
                .frame(maxWidth: .infinity)
                .padding(.vertical, Spacing.s)
        }
        .glassProminentButton()
        .tint(palette.primary)
        // In edit mode, block Save until the record actually loaded — saving
        // an unseeded form would blank the dose and sever its schedule linkage.
        .disabled(vm.saving
            || (isEditing && vm.loadedDose == nil)
            || (vm.rangeMode && !isEditing && vm.rangeDayCount < 1))
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
