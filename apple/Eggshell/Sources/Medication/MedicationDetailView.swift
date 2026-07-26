import SwiftUI
import TransitionCore

// ===========================================================================
// Médics — one treatment (handoff §6.5).
//
// The history is not the raw dose table: it is the schedule's occurrences
// paired with what was actually logged, so a dose that never happened still
// shows up as a line saying « manquée ». Intakes recorded before punctuality
// existed carry no planned time and read simply « notée » — we never guess
// which occurrence they belonged to (D2).
// ===========================================================================

@MainActor
final class MedicationDetailViewModel: ObservableObject {

    /// One line of the history: a real intake, or an occurrence nobody answered.
    struct HistoryEntry: Identifiable {
        let id: String
        /// Nil for a missed occurrence — there is no dose row to edit.
        let doseId: Int64?
        /// The real time when logged, the planned one when missed.
        let atMs: Int64
        let timing: MedTiming
        let deltaMin: Int?
        let dose: Double?
        let doseUnit: String?
        let route: String?
        let injectionSite: String?
    }

    @Published var loading = true
    @Published var med: Medication?
    @Published var schedules: [DoseSchedule] = []
    @Published var history: [HistoryEntry] = []
    @Published var changes: [TreatmentChange] = []
    @Published var alias: String?
    @Published var error: String?

    let medId: Int64

    private static let windowMs: Int64 = 30 * 24 * 60 * 60 * 1000
    private static let historyLimit: Int64 = 50
    /// The card is one block, not a lazy list: cap it so a treatment logged
    /// twice a day for two years can't turn the screen into a wall.
    private static let historyRows = 60
    /// How far a declared skip may sit from the occurrence it answered.
    private static let skipMatchMs: Int64 = 12 * 60 * 60 * 1000

    init(medId: Int64) {
        self.medId = medId
    }

    func load(_ session: VaultService) async {
        loading = true
        error = nil
        do {
            med = try await session.getMedication(medId)
            schedules = try await session.listSchedulesForMedication(medId, includeInactive: true)
            let now = Time.nowMs()
            let tenYears: Int64 = 10 * 365 * 24 * 60 * 60 * 1000
            changes = try await session.listTreatmentChanges(fromMs: now - tenYears, toMs: now)
                .filter { $0.medicationId == medId }
                .sorted { $0.atMs > $1.atMs }
            history = await buildHistory(session)
        } catch {
            self.error = describe(error)
        }
        alias = NotifPrefs.alias(for: medId)
        loading = false
    }

    private func buildHistory(_ session: VaultService) async -> [HistoryEntry] {
        let now = Time.nowMs()
        let doses = (try? await session.listDoses(
            medicationId: medId, offset: 0, limit: Self.historyLimit)) ?? []
        let window = await PlannedDoses.window(
            session: session, fromMs: now - Self.windowMs, toMs: now, medicationId: medId)

        var out: [HistoryEntry] = []
        var paired = Set<Int64>()

        for occurrence in window.occurrences {
            guard let event = occurrence.event else {
                out.append(HistoryEntry(
                    id: "planned-\(occurrence.scheduleId)-\(occurrence.plannedAtMs)",
                    doseId: nil,
                    atMs: occurrence.plannedAtMs,
                    timing: .missed,
                    deltaMin: nil,
                    dose: nil,
                    doseUnit: nil,
                    route: nil,
                    injectionSite: nil))
                continue
            }
            paired.insert(event.id)
            let delta = occurrence.deltaMin
            out.append(HistoryEntry(
                id: "dose-\(event.id)",
                doseId: event.id,
                atMs: event.takenAtMs,
                timing: Punctuality.timing(delta) == .late ? .late : .onTime,
                deltaMin: delta,
                dose: event.dose,
                doseUnit: event.doseUnit,
                route: event.route,
                injectionSite: event.injectionSite))
        }

        // Everything else the vault holds for this treatment: ad-hoc intakes,
        // declared skips, and the whole pre-punctuality history. They are real
        // — they just have no prescribed time to be measured against.
        let missedAt = window.occurrences.filter { $0.event == nil }.map(\.plannedAtMs)
        for event in doses where !paired.contains(event.id) {
            // A declared skip inside the window is already on screen as the
            // « manquée » occurrence it answered (D2 counts a skip as a miss).
            // Listing the event too would show the same dose twice.
            let alreadyShown = event.status == "skipped"
                && missedAt.contains { abs($0 - event.takenAtMs) <= Self.skipMatchMs }
            if alreadyShown { continue }
            out.append(HistoryEntry(
                id: "dose-\(event.id)",
                doseId: event.id,
                atMs: event.takenAtMs,
                timing: event.status == "skipped" ? .skipped : .unlinked,
                deltaMin: nil,
                dose: event.dose,
                doseUnit: event.doseUnit,
                route: event.route,
                injectionSite: event.injectionSite))
        }

        return Array(out.sorted { $0.atMs > $1.atMs }.prefix(Self.historyRows))
    }

    func setAlias(_ value: String?) {
        let trimmed = value?.trimmingCharacters(in: .whitespacesAndNewlines)
        NotifPrefs.setAlias(trimmed, for: medId)
        alias = (trimmed?.isEmpty ?? true) ? nil : trimmed
    }

    func toggleSchedule(_ schedule: DoseSchedule, session: VaultService, app: AppState) async {
        do {
            try await session.setScheduleActive(schedule.id, !schedule.active)
            await app.refreshNotifications()
            await load(session)
        } catch {
            self.error = describe(error)
        }
    }

    func deleteSchedule(_ id: Int64, session: VaultService, app: AppState) async {
        do {
            try await session.deleteSchedule(id)
            await app.refreshNotifications()
            await load(session)
        } catch {
            self.error = describe(error)
        }
    }

    func deleteDose(_ id: Int64, session: VaultService) async {
        do {
            try await session.deleteDose(id)
            await load(session)
        } catch {
            self.error = describe(error)
        }
    }

    /// Archive (hide, reversible). Only returns true when the write succeeded,
    /// so a failed archive doesn't navigate away as if it had worked.
    func archive(_ session: VaultService, app: AppState) async -> Bool {
        do {
            try await session.setMedicationArchived(medId, true)
            await app.refreshNotifications()
            return true
        } catch {
            self.error = describe(error)
            return false
        }
    }

    /// Put an archived treatment back in circulation. Stays on the screen: you
    /// are looking at it, and the header has to redraw without the notice.
    func unarchive(_ session: VaultService, app: AppState) async {
        do {
            try await session.setMedicationArchived(medId, false)
            await app.refreshNotifications()
            await load(session)
        } catch {
            self.error = describe(error)
        }
    }

    /// Hard-delete: the core cascades doses, schedules and treatment changes,
    /// then the pending reminders are rebuilt from what's left.
    func deleteMedication(_ session: VaultService, app: AppState) async -> Bool {
        do {
            try await session.deleteMedication(medId)
            await app.refreshNotifications()
            return true
        } catch {
            self.error = describe(error)
            return false
        }
    }
}

struct MedicationDetailView: View {
    let medId: Int64

    @EnvironmentObject private var app: AppState
    @EnvironmentObject private var router: Router
    @Environment(\.palette) private var palette
    @Environment(\.dismiss) private var dismiss
    @StateObject private var vm: MedicationDetailViewModel

    @State private var editingAlias = false
    @State private var aliasDraft = ""
    @State private var confirmDelete = false
    @State private var doseToDelete: Int64?
    @State private var scheduleToDelete: Int64?
    /// Where this screen sits in the stack. `.task` does not re-fire when the
    /// dose form on top of it pops, so the depth says when to read again —
    /// otherwise a dose you just noted would be missing from the history.
    @State private var depth: Int?

    init(medId: Int64) {
        self.medId = medId
        _vm = StateObject(wrappedValue: MedicationDetailViewModel(medId: medId))
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Metrics.blockGap) {
                if let error = vm.error {
                    ErrorCardView(error, retryLabel: "Réessayer") { reload() }
                }

                if vm.loading && vm.med == nil {
                    SkeletonBlock(height: 150)
                    SkeletonBlock(height: 120)
                    SkeletonBlock(height: 188)
                }

                if let med = vm.med {
                    identityCard(med)
                    if med.archived { archivedNotice }
                }

                SectionTitleView(
                    "Schémas de prise",
                    action: "Ajouter",
                    onAction: { router.push(.addSchedule(medId: medId)) },
                    prominent: true)

                if vm.schedules.isEmpty {
                    if !vm.loading {
                        EmptyStateView(
                            "Aucun rappel programmé pour ce traitement. Dis-moi quand tu le prends et je m'en souviens pour toi.",
                            systemImage: "bell.slash",
                            actionLabel: "Ajouter un rappel") {
                                router.push(.addSchedule(medId: medId))
                            }
                    }
                } else {
                    ForEach(vm.schedules, id: \.id) { scheduleCard($0) }
                }

                SectionTitleView("Historique", prominent: true)

                if vm.history.isEmpty {
                    if !vm.loading {
                        EmptyStateView(
                            "Aucune prise notée pour l'instant. La première apparaîtra ici, avec son écart à l'heure prévue.",
                            systemImage: "clock.arrow.circlepath",
                            actionLabel: "Noter une prise") {
                                router.push(.logDose(medId: medId))
                            }
                    }
                } else {
                    historyCard
                }

                if !vm.changes.isEmpty {
                    SectionTitleView("Changements de traitement", prominent: true)
                    changesCard
                }
            }
            .padding(.horizontal, Metrics.screenMargin)
            .padding(.top, Spacing.xs)
            .padding(.bottom, Metrics.blockGap)
        }
        .medsScreen(vm.med?.name ?? "Traitement")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) { overflowMenu }
        }
        .eggActionBar {
            ActionBarButton("Noter une prise", systemImage: "plus") {
                router.push(.logDose(medId: medId))
            }
        }
        .sheet(isPresented: $editingAlias) { aliasSheet }
        .alert("Supprimer ce traitement ?", isPresented: $confirmDelete) {
            Button("Annuler", role: .cancel) {}
            Button("Supprimer définitivement", role: .destructive) { deleteMedication() }
        } message: {
            Text("Le traitement, tout son historique de prises et ses rappels seront définitivement supprimés. Pour le masquer sans rien perdre, choisis plutôt « Archiver ».")
        }
        .alert("Supprimer cette prise ?", isPresented: Binding(
            get: { doseToDelete != nil },
            set: { if !$0 { doseToDelete = nil } }
        )) {
            Button("Annuler", role: .cancel) { doseToDelete = nil }
            Button("Supprimer", role: .destructive) {
                if let id = doseToDelete, let session = app.session {
                    Task { await vm.deleteDose(id, session: session) }
                }
                doseToDelete = nil
            }
        } message: {
            Text("Cette prise sera retirée de l'historique. C'est définitif.")
        }
        .alert("Supprimer ce rappel ?", isPresented: Binding(
            get: { scheduleToDelete != nil },
            set: { if !$0 { scheduleToDelete = nil } }
        )) {
            Button("Annuler", role: .cancel) { scheduleToDelete = nil }
            Button("Supprimer", role: .destructive) {
                if let id = scheduleToDelete, let session = app.session {
                    Task { await vm.deleteSchedule(id, session: session, app: app) }
                }
                scheduleToDelete = nil
            }
        } message: {
            Text("Le rappel disparaît, ton historique de prises reste intact.")
        }
        .onAppear { if depth == nil { depth = router.path.count } }
        .onChange(of: router.path.count) { _, current in
            if current == depth { reload() }
        }
        .task { reload() }
    }

    // MARK: - Overflow

    private var overflowMenu: some View {
        Menu {
            Button {
                router.push(.editMedication(id: medId))
            } label: {
                Label("Modifier le traitement", systemImage: "pencil")
            }
            if vm.med?.archived == true {
                Button {
                    guard let session = app.session else { return }
                    Task { await vm.unarchive(session, app: app) }
                } label: {
                    Label("Sortir de l'archive", systemImage: "tray.and.arrow.up")
                }
            } else {
                Button {
                    archive()
                } label: {
                    Label("Archiver", systemImage: "archivebox")
                }
            }
            Divider()
            Button(role: .destructive) {
                confirmDelete = true
            } label: {
                Label("Supprimer", systemImage: "trash")
            }
        } label: {
            Image(systemName: "ellipsis.circle")
                .foregroundStyle(palette.onSurfaceVariant)
        }
        .accessibilityLabel("Plus d'options")
    }

    // MARK: - Identity

    /// The tile deliberately wears the *strong* `primary` pair inside a
    /// `primaryContainer` card — the inversion is what makes it read as the
    /// subject of the screen rather than one more row.
    private func identityCard(_ med: Medication) -> some View {
        let accent: Color? = med.color.map { MedColor.color(fromArgb: $0) }
        return EggCard(variant: .primary, spacing: 0) {
            HStack(spacing: 14) {
                IconTile(
                    size: 52,
                    cornerRadius: Radius.launcherTile,
                    container: accent ?? palette.primary
                ) {
                    Image(systemName: MedFormat.routeIcon(med.route))
                        .font(.system(size: 24, weight: .semibold))
                        .foregroundStyle(palette.onPrimary)
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(MedFormat.doseWithUnit(med.defaultDose, med.defaultDoseUnit)
                        .map { "\($0) par prise" } ?? "Dose libre")
                        .font(EggFont.titleL)
                    Text(MedCatalog.kindLabel(med.kind) + MedFormat.sep + MedCatalog.routeLabel(med.route))
                        .font(EggFont.bodyS)
                        .opacity(0.78)
                }
                Spacer(minLength: 0)
            }

            if let notes = med.notes, !notes.isEmpty {
                Text(notes)
                    .font(EggFont.bodyS)
                    .opacity(0.78)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 10)
            }

            CardRule(opacity: 0.22).padding(.top, Spacing.l)

            Button {
                aliasDraft = vm.alias ?? ""
                editingAlias = true
            } label: {
                HStack(spacing: 9) {
                    Image(systemName: "bell.fill")
                        .font(.system(size: 15))
                        .opacity(0.75)
                    Text("Alias dans les notifications")
                        .font(EggFont.bodyS)
                        .opacity(0.85)
                    Spacer(minLength: Spacing.s)
                    Text(vm.alias ?? "Non défini")
                        .font(EggFont.label)
                }
                .padding(.top, 14)
                .frame(minHeight: Metrics.touchTarget)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
    }

    private var archivedNotice: some View {
        EggCard(variant: .outlined) {
            Text("Ce traitement est archivé : il garde tout son historique, mais ne déclenche plus de rappel.")
                .font(EggFont.bodyS)
                .foregroundStyle(palette.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    // MARK: - Schedules

    private func scheduleCard(_ schedule: DoseSchedule) -> some View {
        EggCard(variant: .low, paddingH: 18, paddingV: Spacing.l, spacing: 0) {
            HStack(spacing: Metrics.blockGap) {
                Button {
                    router.push(.editSchedule(medId: medId, scheduleId: schedule.id))
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(MedFormat.cadence(schedule))
                            .font(EggFont.titleS)
                            .foregroundStyle(palette.onSurface)
                            .multilineTextAlignment(.leading)
                        Text("Prochain : " + MedFormat.dayAndTime(schedule.nextDueAtMs))
                            .font(EggFont.bodyS)
                            .foregroundStyle(palette.onSurfaceVariant)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)

                Toggle("Activer le rappel", isOn: Binding(
                    get: { schedule.active },
                    set: { _ in
                        guard let session = app.session else { return }
                        Task { await vm.toggleSchedule(schedule, session: session, app: app) }
                    }))
                    .labelsHidden()
                    .tint(palette.primary)
                    .accessibilityLabel("Activer le rappel")
            }

            CardRule().padding(.top, 14)

            HStack(spacing: Spacing.s) {
                Image(systemName: "text.bubble")
                    .font(.system(size: 14))
                    .foregroundStyle(palette.onSurfaceVariant)
                Text("Texte du rappel")
                    .font(EggFont.bodyS)
                    .foregroundStyle(palette.onSurfaceVariant)
                Spacer(minLength: Spacing.s)
                Text("« " + (schedule.label.flatMap { $0.isEmpty ? nil : $0 } ?? "C'est l'heure") + " »")
                    .font(EggFont.bodyS)
                    .foregroundStyle(palette.onSurface)
                    .multilineTextAlignment(.trailing)
            }
            .padding(.top, Spacing.m)
        }
        .contextMenu {
            Button {
                router.push(.editSchedule(medId: medId, scheduleId: schedule.id))
            } label: {
                Label("Modifier le rappel", systemImage: "pencil")
            }
            Button(role: .destructive) {
                scheduleToDelete = schedule.id
            } label: {
                Label("Supprimer le rappel", systemImage: "trash")
            }
        }
    }

    // MARK: - History

    /// The 6 / 18 padding is what lets each rule run edge to edge inside the
    /// card, so the lines read as one block rather than as five cards.
    private var historyCard: some View {
        EggCard(variant: .low, paddingH: 18, paddingV: 6, spacing: 0) {
            ForEach(Array(vm.history.enumerated()), id: \.element.id) { pair in
                if pair.offset > 0 { CardRule(opacity: 0.14) }
                historyRow(pair.element)
            }
        }
    }

    private func historyRow(_ entry: MedicationDetailViewModel.HistoryEntry) -> some View {
        let style = MedTimingStyle.of(entry.timing, deltaMin: entry.deltaMin, palette: palette)
        var detail: [String] = []
        if let dose = MedFormat.doseWithUnit(entry.dose, entry.doseUnit) { detail.append(dose) }
        if let route = entry.route { detail.append(MedCatalog.routeLabel(route)) }
        if let site = entry.injectionSite { detail.append(MedCatalog.injectionSiteLabel(site)) }

        return HStack(spacing: Metrics.blockGap) {
            Image(systemName: style.systemImage)
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(style.glyph)
                .frame(width: 20)
            VStack(alignment: .leading, spacing: 2) {
                Text(MedFormat.dayAndTime(entry.atMs))
                    .font(.eggBody)
                    .foregroundStyle(palette.onSurface)
                Text(detail.isEmpty ? "Non notée" : detail.joined(separator: MedFormat.sep))
                    .font(EggFont.bodyS)
                    .foregroundStyle(palette.onSurfaceVariant)
            }
            Spacer(minLength: Spacing.s)
            StatusPillView(style.word, container: style.container, content: style.content)
        }
        .padding(.vertical, 13)
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
        .onTapGesture {
            if let id = entry.doseId { router.push(.editDose(medId: medId, doseId: id)) }
        }
        .contextMenu {
            if let id = entry.doseId {
                Button {
                    router.push(.editDose(medId: medId, doseId: id))
                } label: {
                    Label("Modifier cette prise", systemImage: "pencil")
                }
                Button(role: .destructive) {
                    doseToDelete = id
                } label: {
                    Label("Supprimer cette prise", systemImage: "trash")
                }
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(MedFormat.dayAndTime(entry.atMs) + ", " + style.word)
    }

    // MARK: - Treatment changes

    private var changesCard: some View {
        EggCard(variant: .low, paddingH: 18, paddingV: 6, spacing: 0) {
            ForEach(Array(vm.changes.enumerated()), id: \.element.id) { pair in
                if pair.offset > 0 { CardRule(opacity: 0.14) }
                VStack(alignment: .leading, spacing: 2) {
                    Text(MedFormat.dayAndTime(pair.element.atMs))
                        .font(EggFont.bodyS)
                        .foregroundStyle(palette.onSurfaceVariant)
                    Text(changeDescription(pair.element))
                        .font(.eggBody)
                        .foregroundStyle(palette.onSurface)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.vertical, 13)
            }
        }
    }

    private func changeDescription(_ change: TreatmentChange) -> String {
        let field: String
        switch change.field {
        case "dose":  field = "Dose"
        case "unit":  field = "Unité"
        case "route": field = "Voie"
        default:      field = change.field
        }
        return "\(field) : \(change.oldValue ?? "—") → \(change.newValue ?? "—")"
    }

    // MARK: - Alias

    private var aliasSheet: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: Spacing.m) {
                Text("Le nom qui s'affiche à la place du vrai, dans les rappels — seulement si tu as choisi « Alias » dans Réglages → Rappels.")
                    .font(EggFont.bodyS)
                    .foregroundStyle(palette.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
                TextField("Ex. : Vitamines", text: $aliasDraft)
                    .font(.eggBody)
                    .foregroundStyle(palette.onSurface)
                    .padding(Spacing.m)
                    .background(
                        palette.surfaceContainerHigh,
                        in: RoundedRectangle(cornerRadius: Radius.field, style: .continuous))
                    .onChange(of: aliasDraft) { _, value in
                        if value.count > 40 { aliasDraft = String(value.prefix(40)) }
                    }
                Spacer()
            }
            .padding(Metrics.screenMargin)
            .background(palette.surface)
            .navigationTitle("Alias dans les notifications")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Annuler") { editingAlias = false }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Enregistrer") {
                        vm.setAlias(aliasDraft)
                        editingAlias = false
                    }
                }
            }
        }
        .presentationDetents([.height(280)])
        .presentationDragIndicator(.visible)
    }

    // MARK: - Actions

    private func reload() {
        guard let session = app.session else {
            vm.loading = false
            return
        }
        Task { await vm.load(session) }
    }

    private func archive() {
        guard let session = app.session else { return }
        Task {
            if await vm.archive(session, app: app) { dismiss() }
        }
    }

    private func deleteMedication() {
        guard let session = app.session else { return }
        Task {
            if await vm.deleteMedication(session, app: app) { dismiss() }
        }
    }
}
