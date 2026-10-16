import SwiftUI
import TransitionCore

// The reminders hub, reached from the « Rappels » section of Réglages.
//
// The refonte moved the *content* of a notification up a level — the three chips
// and the lock-screen preview live in Réglages now — and left the CRUD here: the
// medication schedules, the lab / photo / voice / journal reminders, the
// per-treatment aliases and the priority switch. Everything the app can notify
// you about, in one place (D5).

@MainActor
final class RemindersViewModel: ObservableObject {
    @Published var loading = true
    @Published var schedules: [DoseSchedule] = []
    @Published var medsById: [Int64: Medication] = [:]
    /// Upcoming one-shot appointment reminders — read-only here, managed from
    /// Rendez-vous. Listed so this screen really shows every notification the app
    /// may fire.
    @Published var appointmentReminders: [Appointment] = []
    @Published var error: String?

    func load(_ session: VaultService) async {
        loading = true
        do {
            let meds = try await session.listMedications(includeArchived: true)
            medsById = Dictionary(uniqueKeysWithValues: meds.map { ($0.id, $0) })
            // Paused reminders included, deliberately. Listing only the active
            // ones hides exactly the reminders that need attention — the ones
            // that stopped firing — from the screen whose whole job is to show
            // everything the app may notify about.
            //
            // Walked medication by medication because the core offers "all
            // active" or "one medication, inactive included", and nothing else.
            // Archived ones are walked too: archiving does not cancel a
            // schedule, so a reminder still armed under an archived treatment
            // is precisely the one this screen has to surface. VaultService is
            // an actor, so this is a loop rather than a flatMap — a closure
            // cannot await.
            var all: [DoseSchedule] = []
            for med in meds {
                let own = (try? await session.listSchedulesForMedication(med.id, includeInactive: true)) ?? []
                all.append(contentsOf: own)
            }
            // Live reminders first, then paused ones, each block by next-due.
            schedules = all.sorted {
                $0.active == $1.active ? $0.nextDueAtMs < $1.nextDueAtMs : $0.active
            }
            let now = Time.nowMs()
            appointmentReminders = try await session.listAppointments()
                .filter { ($0.reminderAtMs ?? 0) > now }
                .sorted { ($0.reminderAtMs ?? 0) < ($1.reminderAtMs ?? 0) }
        } catch {
            self.error = describe(error)
        }
        loading = false
    }

    func medName(_ id: Int64) -> String { medsById[id]?.name ?? "Traitement" }

    /// Medications that have at least one active schedule — the only ones whose
    /// alias affects a real reminder.
    var aliasableMeds: [Medication] {
        let ids = Set(schedules.map(\.medicationId))
        return ids.compactMap { medsById[$0] }.sorted { $0.name < $1.name }
    }

    func setActive(_ schedule: DoseSchedule, _ active: Bool, session: VaultService) async {
        do {
            try await session.setScheduleActive(schedule.id, active)
            await load(session)
        } catch {
            self.error = describe(error)
        }
    }
}

struct RemindersView: View {
    @EnvironmentObject private var app: AppState
    @EnvironmentObject private var labReminders: LabReminderStore
    @Environment(\.palette) private var palette
    @StateObject private var vm = RemindersViewModel()

    /// Notification priority lives in `NotifPrefs` (plain UserDefaults), not in
    /// published state, so it is mirrored into local state for live UI.
    @State private var highPriority: Bool = NotifPrefs.highPriority
    @State private var contentMode: NotifContentMode = NotifPrefs.contentMode

    @State private var labEditor: LabEditorTarget?
    /// Per-schedule « Prioritaire » set from this screen. Read back from
    /// `NotifPrefs` when absent, so a schedule nobody touched shows the global
    /// default it actually follows.
    @State private var medPriority: [Int64: Bool] = [:]
    @State private var confirmPause: DoseSchedule?
    @State private var editingSchedule: ScheduleEditTarget?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Metrics.blockGap) {
                Text("Tout ce que l'app peut te notifier, au même endroit. Le contenu affiché se règle depuis Réglages.")
                    .font(EggFont.bodyS)
                    .foregroundStyle(palette.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)

                // Skeletons on first load only: a reload after the edit sheet
                // closes keeps the sections on screen instead of blanking them.
                if vm.loading && vm.schedules.isEmpty {
                    SkeletonBlock(height: 140, cornerRadius: Radius.card)
                    SkeletonBlock(height: 120, cornerRadius: Radius.card)
                } else {
                    medicationsSection
                    labSection(
                        kind: LabReminderKind.lab,
                        title: "BILANS SANGUINS",
                        hint: "Un rappel récurrent pour tes prises de sang.",
                        addLabel: "Ajouter un bilan")
                    labSection(
                        kind: LabReminderKind.photo,
                        title: "PHOTOS DE SUIVI",
                        hint: "Un rappel pour ton journal photo.",
                        addLabel: "Ajouter un rappel photo")
                    labSection(
                        kind: LabReminderKind.voice,
                        title: "SUIVI DE LA VOIX",
                        hint: "Un rappel pour enregistrer un extrait vocal.",
                        addLabel: "Ajouter un rappel voix")
                    labSection(
                        kind: LabReminderKind.journal,
                        title: "JOURNAL D'HUMEUR",
                        hint: "Un rappel pour noter ton humeur du jour.",
                        addLabel: "Ajouter un rappel journal")
                    labSection(
                        kind: LabReminderKind.sport,
                        title: "SPORT",
                        hint: "Rappels de séance. Même fonctionnement que les autres : une notification discrète, sans dire de quoi il s'agit.",
                        addLabel: "Ajouter un rappel de séance")
                    labSection(
                        kind: LabReminderKind.dream,
                        title: "RÊVES",
                        hint: "Un rappel au réveil : le souvenir d’un rêve s’efface en quelques minutes, donc l’heure fait tout.",
                        addLabel: "Ajouter un rappel de rêve")
                    aliasSection
                    prioritySection
                    appointmentsSection
                }
                if let message = vm.error { ErrorCardView(message) }
                Color.clear.frame(height: Spacing.s)
            }
            .padding(.horizontal, Metrics.screenMargin)
            .padding(.top, Spacing.s)
        }
        .background(palette.surface.ignoresSafeArea())
        .navigationTitle("Rappels")
        .navigationBarTitleDisplayMode(.inline)
        .task { if let session = app.session { await vm.load(session) } }
        .sheet(item: $labEditor) { target in
            LabReminderEditor(target: target) { saved in
                labReminders.upsert(saved)
                labEditor = nil
                labReminders.refresh()
            } onCancel: {
                labEditor = nil
            }
        }
        .sheet(item: $editingSchedule, onDismiss: {
            // The edit form saves through its own path; reload so the cadence and
            // label shown here reflect any change.
            if let session = app.session { Task { await vm.load(session) } }
        }) { target in
            NavigationStack {
                AddScheduleView(medicationId: target.schedule.medicationId,
                                editScheduleId: target.schedule.id)
            }
        }
        .alert("Suspendre ce planning ?", isPresented: confirmPauseBinding, presenting: confirmPause) { schedule in
            Button("Suspendre", role: .destructive) {
                if let session = app.session {
                    Task { await vm.setActive(schedule, false, session: session) }
                }
                confirmPause = nil
            }
            Button("Annuler", role: .cancel) { confirmPause = nil }
        } message: { _ in
            Text("Le rappel ne se déclenchera plus tant que tu ne le réactives pas.")
        }
    }

    private var confirmPauseBinding: Binding<Bool> {
        Binding(get: { confirmPause != nil }, set: { if !$0 { confirmPause = nil } })
    }

    // MARK: - Médics

    private var medicationsSection: some View {
        VStack(alignment: .leading, spacing: Spacing.s) {
            SectionTitleView("MÉDICS")
            if vm.schedules.isEmpty {
                EmptyStateView("Aucun planning actif pour l'instant. Ajoute un rappel depuis la fiche d'un traitement.")
            } else {
                ListGroup {
                    ForEach(Array(vm.schedules.enumerated()), id: \.element.id) { index, schedule in
                        HStack(spacing: 0) {
                            ListRowView(
                                title: vm.medName(schedule.medicationId),
                                subtitle: scheduleSubtitle(schedule),
                                systemImage: medIcon(schedule),
                                action: { editingSchedule = ScheduleEditTarget(schedule: schedule) })
                            Button { confirmPause = schedule } label: {
                                Text("Suspendre")
                                    .font(EggFont.label)
                                    .foregroundStyle(palette.primary)
                                    .padding(.trailing, Metrics.screenMargin)
                                    .frame(minHeight: Metrics.touchTarget)
                                    .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                        }
                        // Per schedule, as Android's list does: one treatment
                        // can warrant a banner and a sound when the others do
                        // not.
                        Toggle(isOn: medPriorityBinding(schedule.id)) {
                            Text("Prioritaire")
                                .font(EggFont.bodyS)
                                .foregroundStyle(palette.onSurfaceVariant)
                        }
                        .tint(palette.primary)
                        .padding(.leading, ListRowView.separatorInset)
                        .padding(.trailing, Metrics.screenMargin)
                        .padding(.bottom, Spacing.s)
                        if index != vm.schedules.count - 1 {
                            Rectangle()
                                .fill(palette.outlineVariant)
                                .frame(height: 1)
                                .padding(.leading, ListRowView.separatorInset)
                        }
                    }
                }
            }
        }
    }

    private func medPriorityBinding(_ scheduleId: Int64) -> Binding<Bool> {
        Binding(
            get: { medPriority[scheduleId] ?? NotifPrefs.isMedPriority(scheduleId) },
            set: { value in
                medPriority[scheduleId] = value
                NotifPrefs.setMedPriority(value, for: scheduleId)
                Task { await app.refreshNotifications() }
            })
    }

    private func medIcon(_ schedule: DoseSchedule) -> String {
        guard let route = vm.medsById[schedule.medicationId]?.route else { return "pills" }
        if MedCatalog.isInjection(route) { return "syringe" }
        if route == "transdermal" || route == "topical" { return "bandage" }
        return "pills"
    }

    /// Cadence plus the schedule's optional custom label:
    /// « Tous les jours à 8:00 · « Aller chercher le traitement » ».
    private func scheduleSubtitle(_ schedule: DoseSchedule) -> String {
        let cadence = NextDueCalculator.describe(schedule)
        if let label = schedule.label?.trimmingCharacters(in: .whitespaces), !label.isEmpty {
            return "\(cadence) · « \(label) »"
        }
        return cadence
    }

    // MARK: - Analyses / photo / voix / journal

    @ViewBuilder
    private func labSection(
        kind: String,
        title: String,
        hint: String,
        addLabel: String
    ) -> some View {
        let items = labReminders.items
            .filter { $0.kind == kind }
            .sorted { $0.nextDueMs < $1.nextDueMs }
        VStack(alignment: .leading, spacing: Spacing.s) {
            SectionTitleView(title, action: "Ajouter", onAction: { labEditor = .new(kind: kind) })
            if items.isEmpty {
                EmptyStateView(hint, actionLabel: addLabel, action: { labEditor = .new(kind: kind) })
            } else {
                ListGroup {
                    ForEach(Array(items.enumerated()), id: \.element.id) { index, reminder in
                        HStack(spacing: 0) {
                            ListRowView(
                                title: reminder.label,
                                subtitle: intervalLabel(reminder),
                                systemImage: LabReminderKind.systemImage(reminder.kind),
                                action: { labEditor = .edit(reminder) })
                            Button {
                                labReminders.delete(id: reminder.id)
                                labReminders.refresh()
                            } label: {
                                Image(systemName: "trash")
                                    .font(.system(size: 15))
                                    .foregroundStyle(palette.error)
                                    .padding(.trailing, Metrics.screenMargin)
                                    .frame(minHeight: Metrics.touchTarget)
                                    .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel("Supprimer « \(reminder.label) »")
                        }
                        if index != items.count - 1 {
                            Rectangle()
                                .fill(palette.outlineVariant)
                                .frame(height: 1)
                                .padding(.leading, ListRowView.separatorInset)
                        }
                    }
                }
            }
        }
    }

    private func intervalLabel(_ reminder: LabReminder) -> String {
        reminder.cadenceLabel
    }

    // MARK: - Alias par traitement

    /// An alias is a decoy label the user picked, so it can live in plain storage —
    /// that is the whole point. It only reaches a lock screen when the content
    /// mode in Réglages is set to « Alias ».
    private var aliasSection: some View {
        VStack(alignment: .leading, spacing: Spacing.s) {
            SectionTitleView("ALIAS PAR TRAITEMENT")
            EggCard(variant: .low, spacing: Spacing.m) {
                Text(contentMode == .alias
                    ? "Ces surnoms s'affichent à la place du vrai nom sur l'écran verrouillé."
                    : "Ces surnoms ne s'afficheront que si tu choisis « Alias » dans Réglages.")
                    .font(EggFont.bodyS)
                    .foregroundStyle(palette.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
                if vm.aliasableMeds.isEmpty {
                    Text("Aucun traitement avec un planning actif.")
                        .font(EggFont.bodyS)
                        .foregroundStyle(palette.onSurfaceVariant)
                } else {
                    ForEach(vm.aliasableMeds, id: \.id) { med in
                        AliasField(medId: med.id, realName: med.name) {
                            Task { await app.refreshNotifications() }
                        }
                    }
                }
            }
        }
    }

    // MARK: - Priorité

    private var prioritySection: some View {
        EggCard(variant: .low, spacing: Spacing.s) {
            Toggle(isOn: priorityBinding) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Rappels prioritaires")
                        .font(.eggBody)
                        .foregroundStyle(palette.onSurface)
                    Text("Notification proéminente (bannière, son) plutôt que silencieuse. Par défaut pour chaque rappel ; « Prioritaire » sur un rappel l'emporte.")
                        .font(EggFont.bodyS)
                        .foregroundStyle(palette.onSurfaceVariant)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .tint(palette.primary)
        }
    }

    private var priorityBinding: Binding<Bool> {
        Binding(
            get: { highPriority },
            set: { newValue in
                highPriority = newValue
                NotifPrefs.highPriority = newValue
                Task {
                    await app.refreshNotifications()
                    await NotificationManager.scheduleLabReminders(labReminders.items)
                }
            })
    }

    // MARK: - Rendez-vous (récapitulatif)

    private var appointmentsSection: some View {
        VStack(alignment: .leading, spacing: Spacing.s) {
            SectionTitleView("RENDEZ-VOUS")
            if vm.appointmentReminders.isEmpty {
                EmptyStateView("Aucun rappel de rendez-vous à venir. Ils se règlent depuis la fiche du rendez-vous.")
            } else {
                ListGroup {
                    ForEach(Array(vm.appointmentReminders.enumerated()), id: \.element.id) { index, appt in
                        ListRowView(
                            title: appointmentTitle(appt),
                            subtitle: appt.reminderAtMs.map { reminderDateLabel($0) } ?? "",
                            systemImage: "calendar",
                            showsSeparator: index != vm.appointmentReminders.count - 1)
                    }
                }
            }
        }
    }

    private func appointmentTitle(_ appt: Appointment) -> String {
        if let place = appt.place, !place.isEmpty { return place }
        if let name = appt.professionalName, !name.isEmpty { return name }
        return "Rendez-vous"
    }

    private func reminderDateLabel(_ ms: Int64) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "fr_FR")
        f.dateStyle = .medium
        f.timeStyle = .short
        return f.string(from: Date(timeIntervalSince1970: Double(ms) / 1000))
    }
}

// Sheet target wrapping the schedule to edit (`DoseSchedule` is not Identifiable,
// which `.sheet(item:)` requires).
private struct ScheduleEditTarget: Identifiable {
    let schedule: DoseSchedule
    var id: Int64 { schedule.id }
}

// MARK: - Alias field

/// One per-medication alias text field. Reads and writes `NotifPrefs` directly and
/// notifies the parent on commit so it can re-schedule notifications.
private struct AliasField: View {
    @Environment(\.palette) private var palette
    let medId: Int64
    let realName: String
    let onCommit: () -> Void
    @State private var text: String = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(realName)
                .font(EggFont.bodyS)
                .foregroundStyle(palette.onSurfaceVariant)
            TextField("Surnom (ex : Vitamines)", text: $text)
                .font(.eggBody)
                .foregroundStyle(palette.onSurface)
                .padding(.horizontal, Spacing.m)
                .padding(.vertical, 10)
                .frame(minHeight: Metrics.touchTarget)
                .background(
                    palette.surfaceContainerHigh,
                    in: RoundedRectangle(cornerRadius: Radius.field, style: .continuous))
                .onSubmit { commit() }
        }
        .onAppear { text = NotifPrefs.alias(for: medId) ?? "" }
        .onChange(of: text) { _, _ in commit() }
        .accessibilityLabel("Surnom de \(realName)")
    }

    private func commit() {
        NotifPrefs.setAlias(text, for: medId)
        onCommit()
    }
}

// MARK: - Lab reminder editor

/// Shared with the sport module's own reminder screen, which is the same
/// store through a second door.
enum LabEditorTarget: Identifiable {
    case new(kind: String)
    case edit(LabReminder)

    var id: String {
        switch self {
        case .new(let kind):    return "new-\(kind)"
        case .edit(let saved):  return "edit-\(saved.id)"
        }
    }
}

/// Create or edit a lab / photo / voice / journal / dream / sport reminder.
///
/// Two cadences, as on Android: every N days (a blood test, a progress photo)
/// or every day at a set time (a training session, the moment after waking).
struct LabReminderEditor: View {
    @Environment(\.palette) private var palette
    let target: LabEditorTarget
    let onSave: (LabReminder) -> Void
    let onCancel: () -> Void

    private enum Cadence: Hashable { case interval, daily }

    @State private var label: String
    @State private var cadence: Cadence
    @State private var daysStr: String
    @State private var time: Date
    @State private var enabled: Bool
    @State private var priority: Bool
    /// What the reminder had before this sheet opened. Kept unless the toggle
    /// is actually moved, so opening and saving a reminder that follows the
    /// global default does not quietly pin it to today's value of that default.
    private let storedPriority: Bool?
    private let shownPriority: Bool

    init(target: LabEditorTarget, onSave: @escaping (LabReminder) -> Void, onCancel: @escaping () -> Void) {
        self.target = target
        self.onSave = onSave
        self.onCancel = onCancel
        switch target {
        case .new(let kind):
            _label = State(initialValue: LabReminderKind.label(kind))
            _cadence = State(initialValue: .interval)
            _daysStr = State(initialValue: String(LabReminderKind.defaultIntervalDays(kind)))
            _time = State(initialValue: Self.time(hour: 9, minute: 0))
            _enabled = State(initialValue: true)
            storedPriority = nil
            shownPriority = NotifPrefs.highPriority
            _priority = State(initialValue: NotifPrefs.highPriority)
        case .edit(let saved):
            _label = State(initialValue: saved.label)
            _cadence = State(initialValue: saved.isDaily ? .daily : .interval)
            // A daily reminder keeps 1 in intervalDays; switching it back to
            // « tous les N jours » should not offer « 1 » as if it were a choice.
            _daysStr = State(initialValue: saved.isDaily
                             ? String(LabReminderKind.defaultIntervalDays(saved.kind))
                             : String(saved.intervalDays))
            _time = State(initialValue: Self.time(hour: saved.dailyHour ?? 9, minute: saved.dailyMinute ?? 0))
            _enabled = State(initialValue: saved.enabled)
            storedPriority = saved.highPriority
            shownPriority = saved.isPriority
            _priority = State(initialValue: saved.isPriority)
        }
    }

    private static func time(hour: Int, minute: Int) -> Date {
        Calendar.current.date(bySettingHour: hour, minute: minute, second: 0, of: Date()) ?? Date()
    }

    private var kind: String {
        switch target {
        case .new(let kind):   return kind
        case .edit(let saved): return saved.kind
        }
    }

    private var canSave: Bool {
        guard !label.trimmingCharacters(in: .whitespaces).isEmpty else { return false }
        switch cadence {
        case .interval: return Int(daysStr).map { $0 > 0 } ?? false
        case .daily:    return true
        }
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Libellé") {
                    TextField("Libellé", text: $label)
                }
                Section("Fréquence") {
                    Picker("Fréquence", selection: $cadence) {
                        Text("Tous les N jours").tag(Cadence.interval)
                        Text("Chaque jour").tag(Cadence.daily)
                    }
                    .pickerStyle(.segmented)
                    switch cadence {
                    case .interval:
                        HStack {
                            Text("Tous les")
                            TextField("7", text: $daysStr)
                                .keyboardType(.numberPad)
                                .multilineTextAlignment(.trailing)
                            Text("jours")
                        }
                    case .daily:
                        DatePicker("À", selection: $time, displayedComponents: .hourAndMinute)
                            .environment(\.locale, Locale(identifier: "fr_FR"))
                    }
                    Toggle("Activé", isOn: $enabled).tint(palette.primary)
                    Toggle("Prioritaire", isOn: $priority).tint(palette.primary)
                }
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Annuler", action: onCancel)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Enregistrer") { save() }.disabled(!canSave)
                }
            }
        }
        .presentationDetents([.medium])
        .presentationDragIndicator(.visible)
    }

    private var title: String {
        switch target {
        case .new:  return "Nouveau rappel"
        case .edit: return "Modifier le rappel"
        }
    }

    private func save() {
        let trimmed = label.trimmingCharacters(in: .whitespaces)
        let id: String
        switch target {
        case .new:                id = UUID().uuidString
        case .edit(let existing): id = existing.id
        }
        var saved = LabReminder(
            id: id, kind: kind, label: trimmed,
            intervalDays: 1, nextDueMs: 0, enabled: enabled)
        saved.highPriority = priority == shownPriority ? storedPriority : priority
        switch cadence {
        case .interval:
            let days = max(1, Int(daysStr) ?? 1)
            saved.intervalDays = days
            let next = Calendar.current.date(byAdding: .day, value: days, to: Date()) ?? Date()
            saved.nextDueMs = Int64(next.timeIntervalSince1970 * 1000)
        case .daily:
            let parts = Calendar.current.dateComponents([.hour, .minute], from: time)
            saved.dailyHour = parts.hour ?? 9
            saved.dailyMinute = parts.minute ?? 0
            // intervalDays stays 1: an older build reading this sees "every day".
            saved.nextDueMs = Int64(saved.nextOccurrence(after: Date()).timeIntervalSince1970 * 1000)
        }
        onSave(saved)
    }
}
