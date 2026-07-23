import SwiftUI
import TransitionCore

// Pushed settings screen (Route.reminders). Mirrors android RemindersScreen:
//   1. active medication schedules — pause/resume, tap-to-edit,
//   2. lab / photo / voice / journal reminders (LabReminderStore) — add /
//      edit / delete,
//   3. notification content mode (générique / nom / alias) + per-med alias,
//   4. priority (heads-up) toggle,
//   5. read-only recap of upcoming appointment reminders (managed in RDV tab).
// After any content change we ask AppState to re-schedule med notifications;
// after any lab change we re-schedule lab notifications.

@MainActor
final class RemindersViewModel: ObservableObject {
    @Published var loading = true
    @Published var schedules: [DoseSchedule] = []
    @Published var medsById: [Int64: Medication] = [:]
    /// Upcoming one-shot appointment reminders — read-only here, managed from
    /// the RDV tab. Listed so this screen shows every notification the app
    /// may fire.
    @Published var appointmentReminders: [Appointment] = []
    @Published var error: String?

    func load(_ session: VaultService) async {
        loading = true
        do {
            let meds = try await session.listMedications(includeArchived: true)
            medsById = Dictionary(uniqueKeysWithValues: meds.map { ($0.id, $0) })
            schedules = try await session.listActiveSchedules().sorted { $0.nextDueAtMs < $1.nextDueAtMs }
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

    /// Medications that have at least one active schedule (the only ones whose
    /// alias affects a real reminder).
    var aliasableMeds: [Medication] {
        let ids = Set(schedules.map(\.medicationId))
        return ids.compactMap { medsById[$0] }.sorted { $0.name < $1.name }
    }

    func setActive(_ schedule: DoseSchedule, _ active: Bool, session: VaultService) async {
        do {
            try await session.setScheduleActive(schedule.id, active)
            await load(session)
        } catch { self.error = describe(error) }
    }
}

struct RemindersView: View {
    @EnvironmentObject private var app: AppState
    @EnvironmentObject private var labReminders: LabReminderStore
    @Environment(\.palette) private var palette
    @StateObject private var vm = RemindersViewModel()

    // Notification-content settings live in NotifPrefs (plain UserDefaults), not
    // in @Published state, so we mirror them into local @State for live UI.
    @State private var contentMode: NotifContentMode = NotifPrefs.contentMode
    @State private var highPriority: Bool = NotifPrefs.highPriority

    // Lab-reminder editor sheet target (nil = closed).
    @State private var labEditor: LabEditorTarget?
    // Med schedule pending delete confirmation.
    @State private var confirmPause: DoseSchedule?
    // Med schedule being edited (sheet with the full schedule form).
    @State private var editingSchedule: ScheduleEditTarget?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Spacing.l) {
                // Spinner only on first load — reloads (e.g. after the edit
                // sheet closes) keep the sections on screen instead of
                // flashing the whole page blank.
                if vm.loading && vm.schedules.isEmpty {
                    ProgressView().tint(palette.primary).frame(maxWidth: .infinity).padding()
                } else {
                    medicationsSection
                    labSection(kind: LabReminderKind.lab,
                               title: "Bilans sanguins",
                               hint: "Un rappel récurrent pour vos prises de sang.",
                               addLabel: "Ajouter un bilan")
                    labSection(kind: LabReminderKind.photo,
                               title: "Photos de suivi",
                               hint: "Un rappel pour votre journal photo.",
                               addLabel: "Ajouter un rappel photo")
                    labSection(kind: LabReminderKind.voice,
                               title: "Suivi de la voix",
                               hint: "Un rappel pour enregistrer un clip vocal.",
                               addLabel: "Ajouter un rappel voix")
                    labSection(kind: LabReminderKind.journal,
                               title: "Journal d'humeur",
                               hint: "Un rappel pour noter ton humeur du jour.",
                               addLabel: "Ajouter un rappel journal")
                    contentModeSection
                    prioritySection
                    appointmentsSection
                }
                if let e = vm.error { ErrorBanner(message: e) }
            }
            .padding(Spacing.l)
        }
        .navigationTitle("Rappels")
        .task { if let s = app.session { await vm.load(s) } }
        .sheet(item: $labEditor) { target in
            LabReminderEditor(target: target) { saved in
                labReminders.upsert(saved)
                labEditor = nil
                Task { await NotificationManager.scheduleLabReminders(labReminders.items) }
            } onCancel: {
                labEditor = nil
            }
        }
        .sheet(item: $editingSchedule, onDismiss: {
            // The edit form saves through its own path; reload so the list
            // (cadence, label) reflects any change.
            if let s = app.session { Task { await vm.load(s) } }
        }) { target in
            NavigationStack {
                AddScheduleView(medicationId: target.schedule.medicationId,
                                editScheduleId: target.schedule.id)
            }
        }
        .alert("Suspendre ce planning ?", isPresented: confirmPauseBinding, presenting: confirmPause) { s in
            Button("Suspendre", role: .destructive) {
                if let session = app.session { Task { await vm.setActive(s, false, session: session) } }
                confirmPause = nil
            }
            Button("Annuler", role: .cancel) { confirmPause = nil }
        } message: { _ in
            Text("Le rappel ne se déclenchera plus tant que vous ne le réactivez pas.")
        }
    }

    private var confirmPauseBinding: Binding<Bool> {
        Binding(get: { confirmPause != nil }, set: { if !$0 { confirmPause = nil } })
    }

    // MARK: - Medications

    private var medicationsSection: some View {
        SectionCard {
            Text("Médics").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
            Text("Tes plannings de traitements actifs. Mets un rappel en pause sans supprimer le planning.")
                .font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.6))
            if vm.schedules.isEmpty {
                Text("Aucun planning actif").font(.eggCallout).foregroundStyle(palette.onSurface.opacity(0.6))
            } else {
                ForEach(vm.schedules, id: \.id) { s in
                    HStack(spacing: Spacing.m) {
                        // Tap the row to edit the schedule in place; the pause
                        // button stays its own tap target.
                        Button {
                            editingSchedule = ScheduleEditTarget(schedule: s)
                        } label: {
                            HStack(spacing: Spacing.m) {
                                Image(systemName: medIcon(s)).font(.title3).foregroundStyle(palette.primary)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(vm.medName(s.medicationId)).font(.eggCallout).foregroundStyle(palette.onSurface)
                                    Text(scheduleSubtitle(s)).font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.6))
                                }
                                Spacer()
                            }
                        }
                        .buttonStyle(.plain)
                        Button("Suspendre") { confirmPause = s }
                            .glassButton().tint(palette.primary)
                    }
                }
            }
        }
    }

    private func medIcon(_ s: DoseSchedule) -> String {
        guard let route = vm.medsById[s.medicationId]?.route else { return "pills" }
        if MedCatalog.isInjection(route) { return "syringe" }
        if route == "transdermal" || route == "topical" { return "bandage" }
        return "pills"
    }

    /// Cadence plus the schedule's optional custom label, mirroring android:
    /// «Tous les jours à 8:00 · « Aller chercher le traitement »».
    private func scheduleSubtitle(_ s: DoseSchedule) -> String {
        let cadence = NextDueCalculator.describe(s)
        if let label = s.label?.trimmingCharacters(in: .whitespaces), !label.isEmpty {
            return "\(cadence) · « \(label) »"
        }
        return cadence
    }

    // MARK: - Lab / photo / voice

    @ViewBuilder
    private func labSection(kind: String, title: String, hint: String, addLabel: String) -> some View {
        let items = labReminders.items.filter { $0.kind == kind }.sorted { $0.nextDueMs < $1.nextDueMs }
        SectionCard {
            Text(title).font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
            Text(hint).font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.6))
            if items.isEmpty {
                Text("Aucun rappel").font(.eggCallout).foregroundStyle(palette.onSurface.opacity(0.6))
            } else {
                ForEach(items) { r in
                    labRow(r)
                }
            }
            Button {
                labEditor = .new(kind: kind)
            } label: {
                Label(addLabel, systemImage: "plus")
            }
            .glassButton().tint(palette.primary)
        }
    }

    private func labRow(_ r: LabReminder) -> some View {
        HStack(spacing: Spacing.m) {
            Image(systemName: LabReminderKind.systemImage(r.kind)).font(.title3).foregroundStyle(palette.primary)
            VStack(alignment: .leading, spacing: 2) {
                Text(r.label).font(.eggCallout).foregroundStyle(palette.onSurface)
                Text(intervalLabel(r)).font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.6))
            }
            Spacer()
            Button { labEditor = .edit(r) } label: {
                Image(systemName: "pencil").foregroundStyle(palette.primary)
            }
            .buttonStyle(.plain)
            Button {
                labReminders.delete(id: r.id)
                Task { await NotificationManager.scheduleLabReminders(labReminders.items) }
            } label: {
                Image(systemName: "trash").foregroundStyle(palette.error)
            }
            .buttonStyle(.plain)
        }
    }

    private func intervalLabel(_ r: LabReminder) -> String {
        let suffix = r.enabled ? "" : " · désactivé"
        return "Tous les \(max(1, r.intervalDays)) j" + suffix
    }

    // MARK: - Content mode

    private var contentModeSection: some View {
        SectionCard {
            Text("Contenu des notifications").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
            Text("Ce qu'un rappel de traitement révèle. Par défaut, rien n'apparaît sur l'écran verrouillé.")
                .font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.6))
            ForEach(NotifContentMode.allCases) { mode in
                Button {
                    contentMode = mode
                    NotifPrefs.contentMode = mode
                    Task { await app.refreshNotifications() }
                } label: {
                    HStack(alignment: .top, spacing: Spacing.m) {
                        Image(systemName: contentMode == mode ? "largecircle.fill.circle" : "circle")
                            .foregroundStyle(contentMode == mode ? palette.primary : palette.outline)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(mode.label).font(.eggCallout).foregroundStyle(palette.onSurface)
                            Text(mode.detail).font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.6))
                        }
                        Spacer()
                    }
                }
                .buttonStyle(.plain)
            }
            if contentMode == .alias {
                aliasEditors
            }
        }
    }

    @ViewBuilder
    private var aliasEditors: some View {
        Divider().overlay(palette.outlineVariant)
        Text("Alias par traitement").font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.6))
        if vm.aliasableMeds.isEmpty {
            Text("Aucun traitement avec un planning actif").font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.5))
        } else {
            ForEach(vm.aliasableMeds, id: \.id) { med in
                AliasField(medId: med.id, realName: med.name) {
                    Task { await app.refreshNotifications() }
                }
            }
        }
    }

    // MARK: - Priority

    private var prioritySection: some View {
        SectionCard {
            Toggle(isOn: priorityBinding) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Rappels prioritaires").font(.eggCallout).foregroundStyle(palette.onSurface)
                    Text("Notification proéminente (bannière, son) plutôt que silencieuse.")
                        .font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.6))
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

    // MARK: - Appointments (read-only recap)

    // Upcoming appointment reminders — read-only so this screen really lists
    // everything the app may fire; editing stays in the RDV tab where the
    // full appointment form lives.
    private var appointmentsSection: some View {
        SectionCard {
            Text("Rendez-vous").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
            Text("Rappels ponctuels de tes prochains RDV. Ils se gèrent depuis l'onglet RDV.")
                .font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.6))
            if vm.appointmentReminders.isEmpty {
                Text("Aucun rappel de rendez-vous à venir.")
                    .font(.eggCallout).foregroundStyle(palette.onSurface.opacity(0.6))
            } else {
                ForEach(vm.appointmentReminders, id: \.id) { appt in
                    HStack(spacing: Spacing.m) {
                        Image(systemName: "calendar").font(.title3).foregroundStyle(palette.primary)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(appointmentTitle(appt)).font(.eggCallout).foregroundStyle(palette.onSurface)
                            if let ms = appt.reminderAtMs {
                                Text(reminderDateLabel(ms))
                                    .font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.6))
                            }
                        }
                        Spacer()
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

// Sheet target wrapping the schedule to edit (DoseSchedule itself is not
// Identifiable, which .sheet(item:) requires).
private struct ScheduleEditTarget: Identifiable {
    let schedule: DoseSchedule
    var id: Int64 { schedule.id }
}

// MARK: - Alias field

/// One per-medication alias text field. Reads/writes NotifPrefs directly and
/// notifies the parent on commit so it can re-schedule notifications.
private struct AliasField: View {
    @Environment(\.palette) private var palette
    let medId: Int64
    let realName: String
    let onCommit: () -> Void
    @State private var text: String = ""

    var body: some View {
        HStack(spacing: Spacing.m) {
            Image(systemName: "tag").foregroundStyle(palette.tertiary)
            TextField("Surnom (ex : Vitamines)", text: $text)
                .textFieldStyle(.roundedBorder)
                .onSubmit { commit() }
        }
        .onAppear { text = NotifPrefs.alias(for: medId) ?? "" }
        .onChange(of: text) { _, _ in commit() }
    }

    private func commit() {
        NotifPrefs.setAlias(text, for: medId)
        onCommit()
    }
}

// MARK: - Lab reminder editor

private enum LabEditorTarget: Identifiable {
    case new(kind: String)
    case edit(LabReminder)

    var id: String {
        switch self {
        case .new(let kind):  return "new-\(kind)"
        case .edit(let r):    return "edit-\(r.id)"
        }
    }
}

/// Create/edit a lab/photo/voice reminder (label + interval in days + enabled).
private struct LabReminderEditor: View {
    @Environment(\.palette) private var palette
    let target: LabEditorTarget
    let onSave: (LabReminder) -> Void
    let onCancel: () -> Void

    @State private var label: String
    @State private var daysStr: String
    @State private var enabled: Bool

    init(target: LabEditorTarget, onSave: @escaping (LabReminder) -> Void, onCancel: @escaping () -> Void) {
        self.target = target
        self.onSave = onSave
        self.onCancel = onCancel
        switch target {
        case .new(let kind):
            _label = State(initialValue: LabReminderKind.label(kind))
            _daysStr = State(initialValue: "90")
            _enabled = State(initialValue: true)
        case .edit(let r):
            _label = State(initialValue: r.label)
            _daysStr = State(initialValue: String(r.intervalDays))
            _enabled = State(initialValue: r.enabled)
        }
    }

    private var kind: String {
        switch target {
        case .new(let kind): return kind
        case .edit(let r):   return r.kind
        }
    }

    private var canSave: Bool {
        !label.trimmingCharacters(in: .whitespaces).isEmpty &&
        (Int(daysStr).map { $0 > 0 } ?? false)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Libellé") {
                    TextField("Libellé", text: $label)
                }
                Section("Fréquence") {
                    HStack {
                        Text("Tous les")
                        TextField("90", text: $daysStr)
                            .keyboardType(.numberPad)
                            .multilineTextAlignment(.trailing)
                        Text("jours")
                    }
                    Toggle("Activé", isOn: $enabled).tint(palette.primary)
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
    }

    private var title: String {
        switch target {
        case .new:  return "Nouveau rappel"
        case .edit: return "Modifier le rappel"
        }
    }

    private func save() {
        let days = max(1, Int(daysStr) ?? 1)
        let trimmed = label.trimmingCharacters(in: .whitespaces)
        let next = Calendar.current.date(byAdding: .day, value: days, to: Date()) ?? Date()
        let nextMs = Int64(next.timeIntervalSince1970 * 1000)
        let saved: LabReminder
        switch target {
        case .new:
            saved = LabReminder(
                id: UUID().uuidString, kind: kind, label: trimmed,
                intervalDays: days, nextDueMs: nextMs, enabled: enabled)
        case .edit(let existing):
            saved = LabReminder(
                id: existing.id, kind: existing.kind, label: trimmed,
                intervalDays: days, nextDueMs: nextMs, enabled: enabled)
        }
        onSave(saved)
    }
}
