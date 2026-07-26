import SwiftUI
import TransitionCore

// Rendez-vous (§6.6) — a pushed screen, like everything but Accueil.
//
// The screen is built around the *next* consultation: how many days away it is,
// who it is with, and what you decided to ask. « À DEMANDER » is tickable right
// here, because the moment you remember a question is not the moment you are
// willing to open an edit form.
//
// The card below it, « Préparer ma consultation », is the **only** entry point to
// the doctor's report: the export left Réglages in the refonte (§2.4).

@MainActor
final class AppointmentsViewModel: ObservableObject {
    @Published var loading = true
    @Published var entries: [Appointment] = []
    @Published var error: String?
    /// Bumped after a tick is written, so the screen can play a haptic.
    @Published var savedTick = 0

    /// Frozen at load time. Read live, `next`, `later` and `past` would each see a
    /// slightly different "now" and could momentarily show one row twice.
    @Published private(set) var now: Int64 = Time.nowMs()

    func load(_ session: VaultService) async {
        loading = true
        do {
            now = Time.nowMs()
            entries = try await session.listAppointments(limit: 500).sorted { $0.atMs < $1.atMs }
        } catch {
            self.error = describe(error)
        }
        loading = false
    }

    /// The consultation to prepare: the soonest one still ahead.
    var next: Appointment? { entries.first { $0.atMs > now } }

    /// Everything after the next one, soonest first.
    var later: [Appointment] { entries.filter { $0.atMs > now }.dropFirst().map { $0 } }

    var past: [Appointment] { entries.filter { $0.atMs <= now }.sorted { $0.atMs > $1.atMs } }

    /// Writes a tick back into `Appointment.todo`, prefixing each line with
    /// `- [x] ` or `- [ ] `. No new table, no new column (§6.6).
    func toggleTodo(
        _ appointment: Appointment,
        at index: Int,
        session: VaultService
    ) async {
        var items = appointmentTodoItems(appointment.todo)
        guard items.indices.contains(index) else { return }
        items[index] = AppointmentTodo(label: items[index].label, done: !items[index].done)
        let rendered = renderAppointmentTodo(items)
        do {
            _ = try await session.updateAppointment(
                appointment.id,
                NewAppointment(
                    atMs: appointment.atMs,
                    place: appointment.place,
                    professionalName: appointment.professionalName,
                    professionalRole: appointment.professionalRole,
                    notes: appointment.notes,
                    todo: rendered,
                    reminderAtMs: appointment.reminderAtMs))
            savedTick += 1
            await load(session)
        } catch {
            self.error = describe(error)
        }
    }
}

struct AppointmentsView: View {
    @EnvironmentObject private var app: AppState
    @EnvironmentObject private var router: Router
    @Environment(\.palette) private var palette
    @StateObject private var vm = AppointmentsViewModel()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Metrics.blockGap) {
                if let message = vm.error { ErrorCardView(message) }
                if vm.loading && vm.entries.isEmpty {
                    SkeletonBlock(height: 220, cornerRadius: Radius.card)
                    SkeletonBlock(height: 72, cornerRadius: Radius.card)
                } else {
                    if let next = vm.next {
                        NextAppointmentCard(
                            appointment: next,
                            onEdit: { router.push(.addAppointment(id: next.id)) },
                            onToggle: { index in toggle(next, index) })
                    } else {
                        EmptyStateView(
                            "Rien de prévu pour l'instant. Note ton prochain rendez-vous, tu pourras le préparer tranquillement.",
                            systemImage: "calendar",
                            actionLabel: "Ajouter un rendez-vous",
                            action: { router.push(.addAppointment(id: nil)) })
                    }

                    // Reachable even with an empty agenda: preparing a report has
                    // nothing to do with having booked the appointment yet.
                    prepareCard

                    if !vm.later.isEmpty {
                        SectionTitleView("Plus tard", prominent: true)
                        ListGroup {
                            ForEach(Array(vm.later.enumerated()), id: \.element.id) { index, entry in
                                ListRowView(
                                    title: Self.title(entry),
                                    subtitle: Self.subtitle(entry),
                                    systemImage: "person",
                                    showsChevron: true,
                                    showsSeparator: index != vm.later.count - 1,
                                    action: { router.push(.addAppointment(id: entry.id)) })
                            }
                        }
                    }

                    if !vm.past.isEmpty {
                        SectionTitleView("Déjà passés", prominent: true)
                        ListGroup {
                            ForEach(Array(vm.past.enumerated()), id: \.element.id) { index, entry in
                                ListRowView(
                                    title: Self.title(entry),
                                    subtitle: Self.subtitle(entry),
                                    systemImage: "clock.arrow.circlepath",
                                    showsChevron: true,
                                    showsSeparator: index != vm.past.count - 1,
                                    action: { router.push(.addAppointment(id: entry.id)) })
                            }
                        }
                    }
                }
                Color.clear.frame(height: Spacing.s)
            }
            .padding(.horizontal, Metrics.screenMargin)
            .padding(.top, Spacing.s)
        }
        .background(palette.surface.ignoresSafeArea())
        .navigationTitle("Rendez-vous")
        .navigationBarTitleDisplayMode(.inline)
        .eggActionBar {
            ActionBarButton("Ajouter un rendez-vous", systemImage: "plus") {
                router.push(.addAppointment(id: nil))
            }
        }
        .sensoryFeedback(.success, trigger: vm.savedTick)
        // `onAppear`, not only `.task`: the list must also reload when the pushed
        // add/edit/delete screen pops, which `.task` is not guaranteed to do.
        .onAppear { if let session = app.session { Task { await vm.load(session) } } }
    }

    private var prepareCard: some View {
        EggCard(
            variant: .low,
            paddingH: Spacing.l,
            paddingV: 14,
            spacing: 0,
            action: { router.push(.pdfExport) }
        ) {
            HStack(spacing: Spacing.m) {
                Image(systemName: "doc.richtext")
                    .font(.system(size: 20))
                    .foregroundStyle(palette.primary)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Préparer ma consultation")
                        .font(.eggBody)
                        .foregroundStyle(palette.onSurface)
                    Text("Rapport PDF · \(Self.periodLabel)")
                        .font(EggFont.bodyS)
                        .foregroundStyle(palette.onSurfaceVariant)
                }
                Spacer(minLength: Spacing.s)
                Image(systemName: "chevron.right")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(palette.outline)
            }
            .frame(minHeight: Metrics.touchTarget)
        }
    }

    private func toggle(_ appointment: Appointment, _ index: Int) {
        guard let session = app.session else { return }
        Task { await vm.toggleTodo(appointment, at: index, session: session) }
    }

    // MARK: - Copy

    /// The subtitle quotes the period the export will actually use, so the card
    /// can never promise « 6 derniers mois » while the screen is set to three.
    static var periodLabel: String {
        ReportPeriodResolver.origin(
            period: ReportPrefs.period,
            shortcut: ReportPrefs.shortcut,
            manual: ReportPrefs.customFromMs > 0 && ReportPrefs.customToMs > 0)
    }

    static func title(_ entry: Appointment) -> String {
        if let name = entry.professionalName, !name.isEmpty { return name }
        if let place = entry.place, !place.isEmpty { return place }
        return "Rendez-vous"
    }

    /// `<jour date> · <heure> · <praticien·ne ou lieu>`.
    static func subtitle(_ entry: Appointment) -> String {
        var parts = [dayLabel(entry.atMs), timeLabel(entry.atMs)]
        if let role = entry.professionalRole, !role.isEmpty { parts.append(role) }
        else if let place = entry.place, !place.isEmpty { parts.append(place) }
        return parts.joined(separator: " · ")
    }

    static func dayLabel(_ ms: Int64) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "fr_FR")
        f.dateFormat = "EEEE d MMMM"
        let raw = f.string(from: Date(timeIntervalSince1970: Double(ms) / 1000))
        guard let first = raw.first else { return raw }
        return String(first).uppercased(with: Locale(identifier: "fr_FR")) + String(raw.dropFirst())
    }

    static func timeLabel(_ ms: Int64) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "fr_FR")
        f.dateFormat = "HH:mm"
        return f.string(from: Date(timeIntervalSince1970: Double(ms) / 1000))
    }
}

// MARK: - The next consultation

/// `variant=tertiary`: the one card of the screen that is about a date rather
/// than about data.
private struct NextAppointmentCard: View {
    @Environment(\.palette) private var palette
    let appointment: Appointment
    let onEdit: () -> Void
    let onToggle: (Int) -> Void

    private var todo: [AppointmentTodo] { appointmentTodoItems(appointment.todo) }

    var body: some View {
        EggCard(variant: .tertiary, spacing: 0) {
            Button(action: onEdit) {
                VStack(alignment: .leading, spacing: 0) {
                    Text(Self.countdown(appointment.atMs))
                        .font(EggFont.micro)
                        .tracking(0.5)
                        .opacity(0.75)
                    Text(
                        "\(AppointmentsView.dayLabel(appointment.atMs)) · "
                            + AppointmentsView.timeLabel(appointment.atMs)
                    )
                    .font(EggFont.titleL)
                    .padding(.top, 4)
                    .fixedSize(horizontal: false, vertical: true)

                    if let pro = professional {
                        detail(icon: "person", title: pro.0, subtitle: pro.1)
                            .padding(.top, Spacing.l)
                    }
                    if let place = location {
                        detail(icon: "calendar", title: place.0, subtitle: place.1)
                            .padding(.top, Metrics.blockGap)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            // No to-do: the heading and its hairline disappear entirely rather
            // than leave an empty « À DEMANDER » on the card.
            if !todo.isEmpty {
                CardRule(opacity: 0.22)
                    .padding(.top, 18)
                // Written with the card's own ink, not `onSurfaceVariant`: this
                // label sits on a tertiary container.
                Text("À DEMANDER")
                    .font(EggFont.micro)
                    .tracking(0.5)
                    .opacity(0.75)
                    .padding(.top, Spacing.l)
                ForEach(Array(todo.enumerated()), id: \.offset) { index, item in
                    todoRow(item, index: index)
                }
            }
        }
    }

    private var professional: (String, String)? {
        let name = appointment.professionalName?.isEmpty == false ? appointment.professionalName : nil
        let role = appointment.professionalRole?.isEmpty == false ? appointment.professionalRole : nil
        guard name != nil || role != nil else { return nil }
        return (name ?? role ?? "", name == nil ? "" : (role ?? ""))
    }

    private var location: (String, String)? {
        guard let place = appointment.place, !place.isEmpty else { return nil }
        // A place typed on two lines keeps its second line as the detail.
        let lines = place.components(separatedBy: "\n")
        return (lines[0], lines.count > 1 ? lines.dropFirst().joined(separator: " ") : "")
    }

    private func detail(icon: String, title: String, subtitle: String) -> some View {
        HStack(alignment: .center, spacing: 10) {
            Image(systemName: icon)
                .font(.system(size: 17))
                .opacity(0.8)
            VStack(alignment: .leading, spacing: 1) {
                Text(title).font(.eggBody.weight(.semibold))
                if !subtitle.isEmpty {
                    Text(subtitle).font(EggFont.bodyS).opacity(0.78)
                }
            }
            Spacer(minLength: 0)
        }
    }

    /// The whole row is the target: the drawn circle is 17 pt, far under the
    /// 44 pt minimum, so the text has to carry the tap (§10).
    private func todoRow(_ item: AppointmentTodo, index: Int) -> some View {
        Button { onToggle(index) } label: {
            HStack(alignment: .center, spacing: 10) {
                checkbox(done: item.done)
                Text(item.label)
                    .font(.eggBody)
                    .strikethrough(item.done, color: nil)
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
        .accessibilityAddTraits(item.done ? [.isSelected] : [])
    }

    /// Drawn, not a glyph: SF Symbols' filled circle would not carry the 1.6 pt
    /// hairline the card's ink asks for.
    private func checkbox(done: Bool) -> some View {
        ZStack {
            Circle()
                .strokeBorder(.foreground.opacity(0.55), lineWidth: 1.6)
                .frame(width: 17, height: 17)
            if done {
                Circle()
                    .fill(.foreground)
                    .frame(width: 17, height: 17)
                Image(systemName: "checkmark")
                    .font(.system(size: 9, weight: .bold))
                    .foregroundStyle(palette.tertiaryContainer)
            }
        }
        .frame(width: 17, height: 17)
    }

    /// « DANS 17 JOURS » — uppercase inside the string, for every plural form, so
    /// a translation can decide otherwise (§3.3).
    static func countdown(_ atMs: Int64, now: Date = Date()) -> String {
        let calendar = Calendar.current
        let target = calendar.startOfDay(for: Date(timeIntervalSince1970: Double(atMs) / 1000))
        let today = calendar.startOfDay(for: now)
        let days = calendar.dateComponents([.day], from: today, to: target).day ?? 0
        if days <= 0 { return "AUJOURD'HUI" }
        if days == 1 { return "DEMAIN" }
        return "DANS \(days) JOURS"
    }
}
