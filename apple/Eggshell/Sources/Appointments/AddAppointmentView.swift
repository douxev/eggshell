import SwiftUI
import TransitionCore

// ===========================================================================
// PUSHED screen — create or edit an appointment ("RDV"). Fields: date+time,
// place, professional name + role, notes, a to-do/done checklist (free text,
// one item per line), and an optional reminder. The reminder is mirrored to a
// local notification via AppState.refreshAppointmentReminders after save.
// Mirrors android AddAppointmentScreen.
// ===========================================================================

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
    @Published var todo = ""
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
                todo = a.todo ?? ""
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

    func save(_ session: VaultService, entryId: Int64?) async -> Bool {
        do {
            func trimmed(_ s: String) -> String? {
                let t = s.trimmingCharacters(in: .whitespacesAndNewlines)
                return t.isEmpty ? nil : t
            }
            let reminderMs: Int64? = reminderEnabled ? Int64(reminderDate.timeIntervalSince1970 * 1000) : nil
            let entry = NewAppointment(
                atMs: Int64(date.timeIntervalSince1970 * 1000),
                place: trimmed(place),
                professionalName: trimmed(proName),
                professionalRole: trimmed(proRole),
                notes: trimmed(notes),
                todo: trimmed(todo),
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

    init(entryId: Int64?) {
        self.entryId = entryId
    }

    var body: some View {
        ScrollView {
            VStack(spacing: Spacing.l) {
                if vm.loading {
                    ProgressView().tint(palette.primary).frame(maxWidth: .infinity).padding()
                } else {
                    dateCard
                    detailsCard
                    todoCard
                    notesCard
                    reminderCard
                }
                if let e = vm.error { ErrorBanner(message: e) }
            }
            .padding(Spacing.l)
        }
        .navigationTitle(entryId == nil ? "Nouveau rendez-vous" : "Modifier le rendez-vous")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("Enregistrer") { save() }.disabled(vm.loading)
            }
            if let id = entryId {
                ToolbarItem(placement: .destructiveAction) {
                    Button("Supprimer", role: .destructive) { delete(id) }
                }
            }
        }
        .task { if let s = app.session { await vm.load(s, entryId: entryId) } }
    }

    private var dateCard: some View {
        SectionCard {
            Text("Date et heure du RDV").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
            DatePicker("RDV le", selection: $vm.date, displayedComponents: [.date, .hourAndMinute])
                .labelsHidden()
                .tint(palette.primary)
                .environment(\.locale, Locale(identifier: "fr"))
                .onChange(of: vm.date) { _, _ in vm.appointmentDateChanged() }
        }
    }

    private var detailsCard: some View {
        SectionCard {
            field("Lieu", text: $vm.place)
            field("Professionnel·le", text: $vm.proName)
            field("Spécialité (ex : médecin généraliste)", text: $vm.proRole)
        }
    }

    private var todoCard: some View {
        SectionCard {
            Text("À faire / fait").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
            TextField("Une tâche par ligne (ex : prise de sang, appeler Dr X).", text: $vm.todo, axis: .vertical)
                .font(.eggBody).foregroundStyle(palette.onSurface).lineLimit(3...8)
        }
    }

    private var notesCard: some View {
        SectionCard {
            Text("Notes").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
            TextField("Notes libres…", text: $vm.notes, axis: .vertical)
                .font(.eggBody).foregroundStyle(palette.onSurface).lineLimit(2...6)
        }
    }

    private var reminderCard: some View {
        SectionCard {
            Toggle(isOn: $vm.reminderEnabled) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Me rappeler").font(.eggCallout).foregroundStyle(palette.onSurface)
                    Text("Une notification avant le rendez-vous.")
                        .font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.6))
                }
            }
            .tint(palette.primary)
            if vm.reminderEnabled {
                DatePicker("Rappel le", selection: $vm.reminderDate, in: Date()..., displayedComponents: [.date, .hourAndMinute])
                    .labelsHidden()
                    .tint(palette.primary)
                    .environment(\.locale, Locale(identifier: "fr"))
                    // Only a genuine user change flips reminderEdited; our own
                    // auto-derive (== lastAutoReminder) must not, or the reminder
                    // would stop following the RDV after the first date change.
                    .onChange(of: vm.reminderDate) { _, newValue in
                        if newValue != vm.lastAutoReminder { vm.reminderEdited = true }
                    }
            }
        }
    }

    private func field(_ label: String, text: Binding<String>) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label).font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
            TextField(label, text: text)
                .font(.eggBody).foregroundStyle(palette.onSurface)
                .padding(Spacing.m)
                .background(palette.surfaceContainerHigh, in: RoundedRectangle(cornerRadius: Corner.medium, style: .continuous))
        }
    }

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
