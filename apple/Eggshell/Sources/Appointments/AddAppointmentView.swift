import SwiftUI
import TransitionCore

// Create or edit a consultation. Date and time, where, who, what you want to
// ask, free notes, and an optional reminder.
//
// « À demander » is a real checklist here too, not a blob of text: the lines are
// stored in `Appointment.todo` prefixed with `- [x] ` / `- [ ] ` (see
// `AppointmentTodo`), one task per line, so a tick survives a reload and the
// doctor's report can print the same list as checkboxes.

private let oneDay: TimeInterval = 24 * 60 * 60
private let oneHour: TimeInterval = 60 * 60

/// A sensible, always-in-the-future default reminder for an appointment: a day
/// before if that's still ahead, otherwise an hour before, never the past.
private func defaultReminder(for appt: Date) -> Date {
    let now = Date()
    let dayBefore = appt.addingTimeInterval(-oneDay)
    if dayBefore > now { return dayBefore }
    let hourBefore = appt.addingTimeInterval(-oneHour)
    return hourBefore > now ? hourBefore : now.addingTimeInterval(5 * 60)
}

@MainActor
final class AddAppointmentViewModel: ObservableObject {
    @Published var loading = true
    @Published var error: String?

    @Published var date = Date().addingTimeInterval(oneDay)
    @Published var place = ""
    @Published var proName = ""
    @Published var proRole = ""
    @Published var notes = ""
    @Published var todo: [AppointmentTodo] = []
    @Published var draft = ""
    @Published var reminderEnabled = false
    @Published var reminderDate = Date()
    /// Once the user picks a reminder time we stop auto-deriving it from the RDV.
    @Published var reminderEdited = false
    /// The last value we set programmatically, so the picker's value-onChange can
    /// tell a user edit (flips reminderEdited) from our own auto-derive (doesn't).
    var lastAutoReminder: Date?

    func load(_ session: VaultService, entryId: Int64?) async {
        loading = true
        do {
            if let id = entryId, let a = try await session.getAppointment(id) {
                date = Date(timeIntervalSince1970: Double(a.atMs) / 1000.0)
                place = a.place ?? ""
                proName = a.professionalName ?? ""
                proRole = a.professionalRole ?? ""
                notes = a.notes ?? ""
                todo = appointmentTodoItems(a.todo)
                if let r = a.reminderAtMs {
                    reminderEnabled = true
                    let stored = Date(timeIntervalSince1970: Double(r) / 1000.0)
                    if stored > Date() {
                        reminderEdited = true
                        reminderDate = stored
                    } else {
                        // The stored reminder already elapsed — present a fresh
                        // future default rather than a dead past time the picker
                        // can't show and that would never fire.
                        setAutoReminder()
                    }
                } else {
                    setAutoReminder()
                }
            } else {
                setAutoReminder()
            }
        } catch {
            self.error = describe(error)
        }
        loading = false
    }

    /// Derive the reminder from the appointment time and record the value so a
    /// subsequent programmatic onChange isn't mistaken for a user edit. Floored
    /// to the whole minute so it exactly matches what the minute-granularity
    /// reminder picker would write back (no sub-minute equality drift).
    func setAutoReminder() {
        let raw = defaultReminder(for: date)
        let d = Date(timeIntervalSince1970: (raw.timeIntervalSince1970 / 60).rounded(.down) * 60)
        lastAutoReminder = d
        reminderDate = d
    }

    /// Keep the reminder a sensible offset before the RDV until the user sets it.
    func appointmentDateChanged() {
        if !reminderEdited { setAutoReminder() }
    }

    func addTask() {
        let label = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !label.isEmpty else { return }
        todo.append(AppointmentTodo(label: label, done: false))
        draft = ""
    }

    func toggleTask(_ index: Int) {
        guard todo.indices.contains(index) else { return }
        todo[index] = AppointmentTodo(label: todo[index].label, done: !todo[index].done)
    }

    func removeTask(_ index: Int) {
        guard todo.indices.contains(index) else { return }
        todo.remove(at: index)
    }

    func save(_ session: VaultService, entryId: Int64?) async -> Bool {
        do {
            func trimmed(_ s: String) -> String? {
                let t = s.trimmingCharacters(in: .whitespacesAndNewlines)
                return t.isEmpty ? nil : t
            }
            // A line still sitting in the composer is something the user typed;
            // losing it on save would be the worst possible moment to lose it.
            addTask()
            let reminderMs: Int64? = reminderEnabled
                ? Int64(reminderDate.timeIntervalSince1970 * 1000)
                : nil
            let entry = NewAppointment(
                atMs: Int64(date.timeIntervalSince1970 * 1000),
                place: trimmed(place),
                professionalName: trimmed(proName),
                professionalRole: trimmed(proRole),
                notes: trimmed(notes),
                todo: renderAppointmentTodo(todo),
                reminderAtMs: reminderMs)
            if let id = entryId {
                _ = try await session.updateAppointment(id, entry)
            } else {
                _ = try await session.addAppointment(entry)
            }
            return true
        } catch {
            self.error = describe(error)
            return false
        }
    }

    func delete(_ session: VaultService, entryId: Int64) async -> Bool {
        do {
            try await session.deleteAppointment(entryId)
            return true
        } catch {
            self.error = describe(error)
            return false
        }
    }
}

struct AddAppointmentView: View {
    let entryId: Int64?

    @EnvironmentObject private var app: AppState
    @Environment(\.palette) private var palette
    @Environment(\.dismiss) private var dismiss
    @StateObject private var vm = AddAppointmentViewModel()

    @State private var confirmDelete = false

    init(entryId: Int64?) {
        self.entryId = entryId
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Metrics.blockGap) {
                if vm.loading {
                    SkeletonBlock(height: 96, cornerRadius: Radius.card)
                    SkeletonBlock(height: 180, cornerRadius: Radius.card)
                } else {
                    dateCard
                    detailsCard
                    todoCard
                    notesCard
                    reminderCard
                }
                if let message = vm.error { ErrorCardView(message) }
                Color.clear.frame(height: Spacing.s)
            }
            .padding(.horizontal, Metrics.screenMargin)
            .padding(.top, Spacing.s)
        }
        .background(palette.surface.ignoresSafeArea())
        .navigationTitle(entryId == nil ? "Nouveau rendez-vous" : "Modifier le rendez-vous")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if let id = entryId {
                ToolbarItem(placement: .destructiveAction) {
                    Button("Supprimer", role: .destructive) { confirmDelete = true }
                        .tint(palette.error)
                        .accessibilityIdentifier("delete-appointment-\(id)")
                }
            }
        }
        .eggActionBar {
            ActionBarButton("Enregistrer", enabled: !vm.loading, action: save)
        }
        .alert("Supprimer ce rendez-vous ?", isPresented: $confirmDelete) {
            Button("Supprimer", role: .destructive) { if let id = entryId { delete(id) } }
            Button("Annuler", role: .cancel) {}
        } message: {
            Text("Le rendez-vous, ses notes et sa liste « à demander » seront effacés. Le rappel associé est annulé.")
        }
        .task { if let session = app.session { await vm.load(session, entryId: entryId) } }
    }

    // MARK: - Cards

    private var dateCard: some View {
        EggCard(variant: .low, spacing: Spacing.s) {
            MicroLabel("DATE ET HEURE")
            DatePicker("", selection: $vm.date, displayedComponents: [.date, .hourAndMinute])
                .labelsHidden()
                .tint(palette.primary)
                .environment(\.locale, Locale(identifier: "fr_FR"))
                .onChange(of: vm.date) { _, _ in vm.appointmentDateChanged() }
        }
    }

    private var detailsCard: some View {
        EggCard(variant: .low, spacing: Metrics.blockGap) {
            field("Lieu", placeholder: "CHU, cabinet, téléconsultation…", text: $vm.place)
            field("Professionnel·le", placeholder: "Le nom que tu veux revoir", text: $vm.proName)
            field("Spécialité", placeholder: "Endocrinologue, généraliste…", text: $vm.proRole)
        }
    }

    private var todoCard: some View {
        EggCard(variant: .low, spacing: Spacing.s) {
            MicroLabel("À DEMANDER")
            Text("Une question par ligne. Tu peux les cocher au fil de la consultation, et elles partent dans le rapport médecin.")
                .font(EggFont.bodyS)
                .foregroundStyle(palette.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)

            ForEach(Array(vm.todo.enumerated()), id: \.offset) { index, item in
                HStack(spacing: Spacing.m) {
                    Button { vm.toggleTask(index) } label: {
                        HStack(spacing: 10) {
                            Image(systemName: item.done ? "checkmark.circle.fill" : "circle")
                                .font(.system(size: 19))
                                .foregroundStyle(item.done ? palette.primary : palette.outline)
                            Text(item.label)
                                .font(.eggBody)
                                .foregroundStyle(palette.onSurface)
                                .strikethrough(item.done)
                                .opacity(item.done ? 0.6 : 1)
                                .fixedSize(horizontal: false, vertical: true)
                            Spacer(minLength: 0)
                        }
                        .frame(minHeight: Metrics.touchTarget, alignment: .leading)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(item.label)
                    .accessibilityValue(item.done ? "Fait" : "À faire")

                    Button { vm.removeTask(index) } label: {
                        Image(systemName: "minus.circle")
                            .font(.system(size: 17))
                            .foregroundStyle(palette.onSurfaceVariant)
                            .frame(width: Metrics.touchTarget, height: Metrics.touchTarget)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Retirer « \(item.label) »")
                }
            }

            HStack(spacing: Spacing.s) {
                TextField("Renouveler l'ordonnance…", text: $vm.draft)
                    .font(.eggBody)
                    .foregroundStyle(palette.onSurface)
                    .submitLabel(.done)
                    .onSubmit { vm.addTask() }
                    .padding(.horizontal, Spacing.m)
                    .padding(.vertical, 10)
                    .background(
                        palette.surfaceContainerHigh,
                        in: RoundedRectangle(cornerRadius: Radius.field, style: .continuous))
                Button { vm.addTask() } label: {
                    Image(systemName: "plus")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(palette.primary)
                        .frame(width: Metrics.touchTarget, height: Metrics.touchTarget)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Ajouter la question")
            }
        }
    }

    private var notesCard: some View {
        EggCard(variant: .low, spacing: Spacing.s) {
            MicroLabel("NOTES")
            TextField("Ce que tu veux garder de ce rendez-vous…", text: $vm.notes, axis: .vertical)
                .font(.eggBody)
                .foregroundStyle(palette.onSurface)
                .lineLimit(2...6)
        }
    }

    private var reminderCard: some View {
        EggCard(variant: .low, spacing: Spacing.s) {
            Toggle(isOn: $vm.reminderEnabled) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Me rappeler")
                        .font(.eggBody)
                        .foregroundStyle(palette.onSurface)
                    Text("Une notification avant le rendez-vous.")
                        .font(EggFont.bodyS)
                        .foregroundStyle(palette.onSurfaceVariant)
                }
            }
            .tint(palette.primary)
            if vm.reminderEnabled {
                DatePicker(
                    "",
                    selection: $vm.reminderDate,
                    in: Date()...,
                    displayedComponents: [.date, .hourAndMinute])
                    .labelsHidden()
                    .tint(palette.primary)
                    .environment(\.locale, Locale(identifier: "fr_FR"))
                    // Only a genuine user change flips reminderEdited; our own
                    // auto-derive (== lastAutoReminder) must not, or the reminder
                    // would stop following the RDV after the first date change.
                    .onChange(of: vm.reminderDate) { _, newValue in
                        if newValue != vm.lastAutoReminder { vm.reminderEdited = true }
                    }
            }
        }
    }

    private func field(_ label: String, placeholder: String, text: Binding<String>) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            MicroLabel(label.uppercased())
            TextField(placeholder, text: text)
                .font(.eggBody)
                .foregroundStyle(palette.onSurface)
                .padding(.horizontal, Spacing.m)
                .padding(.vertical, 10)
                .frame(minHeight: Metrics.touchTarget)
                .background(
                    palette.surfaceContainerHigh,
                    in: RoundedRectangle(cornerRadius: Radius.field, style: .continuous))
        }
    }

    // MARK: - Actions

    private func save() {
        guard let session = app.session else { return }
        Task {
            if await vm.save(session, entryId: entryId) {
                await app.refreshAppointmentReminders()
                dismiss()
            }
        }
    }

    private func delete(_ id: Int64) {
        guard let session = app.session else { return }
        Task {
            if await vm.delete(session, entryId: id) {
                await app.refreshAppointmentReminders()
                dismiss()
            }
        }
    }
}
