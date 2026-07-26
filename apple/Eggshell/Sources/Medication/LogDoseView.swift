import SwiftUI
import TransitionCore

// ===========================================================================
// Médics — note a dose (handoff §6.5, action bar « Noter une prise »).
//
// Two modes, both kept: « Une prise » and « Période » (one intake a day across
// a span, in a single core transaction — a gel applied every morning for three
// months is not thirty taps). The injection-site selector keeps its rotation
// suggestion, and the route can differ from the treatment's default so an
// occasional other administration is recorded truthfully.
//
// The screen also *attaches* what it writes: a dose typed by hand is worth as
// much as one ticked from a notification, so `DueOccurrence.linkage` fills
// `scheduledAtMs` / `scheduleId` when the intake falls within half a cadence of
// an occurrence. Outside that, nothing is attached — an ad-hoc dose is not
// late, it is unplanned, and we never invent a prescribed time (D2).
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
    /// The treatment's reminders, so a hand-noted dose can be attached to the
    /// occurrence it answers. Read once: they don't change under this screen.
    @Published var schedules: [DoseSchedule] = []

    // Editable fields
    @Published var doseText = ""
    @Published var unit = ""
    @Published var route = MedCatalog.routes.first ?? "oral"
    @Published var takenAt = Date()
    @Published var selectedSite: String?
    @Published var notes = ""

    // Range mode (create only): declare a daily intake over a whole span
    // (e.g. a topical applied every day for months) in one action.
    @Published var modeIndex = 0
    @Published var rangeStart = Date()
    @Published var rangeEnd = Date()
    @Published var rangeTime = LogDoseViewModel.defaultRangeTime()

    @Published var saving = false
    /// Bumped on a successful write — the trigger of `.sensoryFeedback` (§4).
    @Published var savedTick = 0

    static let modes = ["Une prise", "Période"]

    var rangeMode: Bool { modeIndex == 1 }

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
            schedules = (try? await session.listSchedulesForMedication(medId, includeInactive: true)) ?? []
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
                    self.error = "Impossible de charger cette prise."
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

    /// The occurrence an intake at `atMs` answers, and how far from it — what
    /// the line under the date field says before anything is written.
    func plannedMatch(at atMs: Int64) -> (plannedAtMs: Int64, deltaMin: Int)? {
        guard let planned = DueOccurrence.linkage(for: atMs, schedules: schedules).scheduledAtMs
        else { return nil }
        return (planned, Int((atMs - planned) / 60_000))
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
        // counting against whichever reminder produced it, and we never
        // retro-fit a prescribed time onto a record written before punctuality
        // existed. On create, attach the occurrence this intake answers.
        let prev = loadedDose
        let link: (scheduleId: Int64?, scheduledAtMs: Int64?)
        if let prev {
            link = (prev.scheduleId, prev.scheduledAtMs)
        } else {
            link = DueOccurrence.linkage(for: takenMs, schedules: schedules)
        }
        let event = NewDoseEvent(
            medicationId: medId,
            takenAtMs: takenMs,
            dose: parsed,
            doseUnit: unit.isEmpty ? nil : unit,
            route: route,
            injectionSite: isInjection ? selectedSite : nil,
            notes: notes.isEmpty ? nil : notes,
            status: prev?.status ?? "taken",
            scheduledAtMs: link.scheduledAtMs,
            scheduleId: link.scheduleId)
        do {
            if let prev {
                _ = try await session.updateDose(prev.id, event)
            } else {
                _ = try await session.logDose(event)
            }
            savedTick += 1
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
            let atMs = Int64(at.timeIntervalSince1970 * 1000)
            // Each day is matched on its own: a fortnight of doses can very
            // well answer a reminder on some days and none on others.
            let link = DueOccurrence.linkage(for: atMs, schedules: schedules)
            doses.append(NewDoseEvent(
                medicationId: medId,
                takenAtMs: atMs,
                dose: parsed,
                doseUnit: unit.isEmpty ? nil : unit,
                route: route,
                injectionSite: isInjection ? selectedSite : nil,
                notes: notes.isEmpty ? nil : notes,
                status: "taken",
                scheduledAtMs: link.scheduledAtMs,
                scheduleId: link.scheduleId))
            day = cal.date(byAdding: .day, value: 1, to: day) ?? day.addingTimeInterval(86_400)
        }
        do {
            _ = try await session.logDoses(doses)
            savedTick += 1
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
    private var isRange: Bool { vm.rangeMode && !isEditing }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Metrics.blockGap) {
                if let error = vm.error {
                    ErrorCardView(error, retryLabel: "Réessayer") { reload() }
                }

                if vm.loading {
                    SkeletonBlock(height: 72)
                    SkeletonBlock(height: 120)
                    SkeletonBlock(height: 120)
                } else {
                    if let med = vm.med { identityCard(med) }
                    if !isEditing {
                        SegmentedSelector(
                            options: LogDoseViewModel.modes,
                            selection: $vm.modeIndex,
                            accessibilityLabel: "Ce que tu notes")
                    }
                    whenBlock
                    doseBlock
                    routeBlock
                    if vm.isInjection { siteBlock }
                    noteBlock
                }
            }
            .padding(.horizontal, Metrics.screenMargin)
            .padding(.top, Spacing.xs)
            .padding(.bottom, Metrics.blockGap)
        }
        .medsScreen(isEditing ? "Modifier la prise" : "Noter une prise")
        .eggActionBar {
            ActionBarButton(saveLabel, systemImage: "checkmark", enabled: canSave) { save() }
        }
        // The confirmation you feel in your hand, on a dose that landed (§4).
        .sensoryFeedback(.success, trigger: vm.savedTick)
        .task { reload() }
    }

    // MARK: - Blocks

    /// Which treatment this is about — the screen is reached from three places,
    /// so it always says so rather than assuming you remember.
    private func identityCard(_ med: Medication) -> some View {
        let accent: Color? = med.color.map { MedColor.color(fromArgb: $0) }
        return EggCard(variant: .low, paddingH: 18, paddingV: 14, spacing: 0) {
            HStack(spacing: Spacing.m) {
                IconTile(size: 44, container: accent?.opacity(0.18) ?? palette.primaryContainer) {
                    Image(systemName: MedFormat.routeIcon(med.route))
                        .font(.system(size: 19, weight: .semibold))
                        .foregroundStyle(accent ?? palette.onPrimaryContainer)
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(med.name)
                        .font(EggFont.titleS)
                        .foregroundStyle(palette.onSurface)
                    Text(MedCatalog.kindLabel(med.kind) + MedFormat.sep + MedCatalog.routeLabel(med.route))
                        .font(EggFont.bodyS)
                        .foregroundStyle(palette.onSurfaceVariant)
                }
                Spacer(minLength: 0)
            }
        }
    }

    @ViewBuilder
    private var whenBlock: some View {
        if isRange {
            MedsFormBlock("QUAND", footnote: rangeFootnote) {
                DatePicker("Du", selection: $vm.rangeStart, in: ...Date(), displayedComponents: .date)
                    .font(.eggBody)
                DatePicker("Au", selection: $vm.rangeEnd, in: ...Date(), displayedComponents: .date)
                    .font(.eggBody)
                DatePicker("À quelle heure", selection: $vm.rangeTime, displayedComponents: .hourAndMinute)
                    .font(.eggBody)
            }
        } else {
            MedsFormBlock("QUAND") {
                // A dose can only have been taken in the past — cap the picker.
                DatePicker("Prise le", selection: $vm.takenAt, in: ...Date(),
                           displayedComponents: [.date, .hourAndMinute])
                    .font(.eggBody)
                plannedLine
            }
        }
    }

    private var rangeFootnote: String {
        switch vm.rangeDayCount {
        case 0:  return "La date de fin est avant celle du début — je ne sais pas quoi noter."
        case 1:  return "Une prise sera notée."
        default: return "\(vm.rangeDayCount) prises seront notées, une par jour."
        }
    }

    /// Says out loud what the dose will be measured against, before it is
    /// written — and says just as clearly when it will be measured against
    /// nothing, so a missing écart never looks like a bug (D2).
    @ViewBuilder
    private var plannedLine: some View {
        let atMs = Int64(vm.takenAt.timeIntervalSince1970 * 1000)
        if let match = vm.plannedMatch(at: atMs) {
            let timing: MedTiming = Punctuality.timing(match.deltaMin) == .late ? .late : .onTime
            let style = MedTimingStyle.of(timing, deltaMin: match.deltaMin, palette: palette)
            HStack(spacing: Spacing.s) {
                Image(systemName: style.systemImage)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(style.glyph)
                Text("Répond au rappel : " + MedFormat.dayAndTime(match.plannedAtMs))
                    .font(EggFont.bodyS)
                    .foregroundStyle(palette.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: Spacing.s)
                StatusPillView(style.word, container: style.container, content: style.content)
            }
            .frame(minHeight: 32)
        } else {
            HStack(spacing: Spacing.s) {
                Image(systemName: "clock")
                    .font(.system(size: 15))
                    .foregroundStyle(palette.onSurfaceVariant)
                Text("Aucun rappel autour de cette heure : je la note sans écart.")
                    .font(EggFont.bodyS)
                    .foregroundStyle(palette.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(minHeight: 32)
        }
    }

    private var doseBlock: some View {
        MedsFormBlock("LA DOSE") {
            HStack(spacing: Spacing.m) {
                MedsField(placeholder: "0", text: $vm.doseText, keyboard: .decimalPad)
                MedsField(placeholder: "unité", text: $vm.unit)
                    .frame(maxWidth: 130)
            }
        }
    }

    private var routeBlock: some View {
        // Per-dose route: it defaults to the treatment's, and stays editable so
        // an occasional different administration is recorded truthfully.
        MedsFormBlock("LA VOIE") {
            MedsChipRow(
                options: MedCatalog.routes,
                selected: vm.route,
                label: MedCatalog.routeLabel,
                onSelect: { value in
                    vm.route = value
                    vm.refreshInjection(for: value)
                })
        }
    }

    private var siteBlock: some View {
        MedsFormBlock("LE SITE", footnote: siteFootnote) {
            MedsChipRow(
                options: vm.sites,
                selected: vm.selectedSite,
                label: MedCatalog.injectionSiteLabel,
                suggested: vm.suggestedSite,
                onSelect: { vm.selectedSite = $0 })
        }
    }

    private var siteFootnote: String {
        guard let suggested = vm.suggestedSite else {
            return "Note où tu piques : c'est ce qui permet de faire tourner les sites."
        }
        return "Suggéré : \(MedCatalog.injectionSiteLabel(suggested)) — on alterne pour laisser la peau se remettre."
    }

    private var noteBlock: some View {
        MedsFormBlock("UN MOT") {
            MedsField(
                placeholder: "Ce que tu veux te rappeler (facultatif)",
                text: $vm.notes,
                multiline: true)
        }
    }

    // MARK: - Saving

    private var saveLabel: String {
        guard isRange else { return "Enregistrer" }
        return vm.rangeDayCount <= 1 ? "Enregistrer" : "Enregistrer \(vm.rangeDayCount) prises"
    }

    private var canSave: Bool {
        if vm.saving { return false }
        // In edit mode, block Save until the record actually loaded — saving an
        // unseeded form would blank the dose and sever its schedule linkage.
        if isEditing && vm.loadedDose == nil { return false }
        if isRange && vm.rangeDayCount < 1 { return false }
        return true
    }

    private func save() {
        guard canSave, let session = app.session else { return }
        Task {
            let ok = isRange
                ? await vm.saveRange(session, medId: medId)
                : await vm.save(session, medId: medId)
            if ok { dismiss() }
        }
    }

    private func reload() {
        guard let session = app.session else {
            vm.loading = false
            return
        }
        Task { await vm.load(session, medId: medId, editDoseId: editDoseId) }
    }
}
