import SwiftUI
import TransitionCore

// ===========================================================================
// PUSHED screen — create or edit a dose schedule for a medication.
//   • kind picker via ChoiceChip (interval / daily / days_interval)
//   • interval     : minutes / heures
//   • daily        : heure (DatePicker)
//   • days_interval: heure (DatePicker) + intervalle en jours + date de départ
//                    → firstDue is computed FROM the chosen start date so the
//                      cadence phase is anchored on that day.
//   • label        : optional free text shown in notifications instead of the
//                    med name (never in generic mode), max 60 chars.
//   With editScheduleId set, the form is seeded from the existing reminder and
//   saved via updateSchedule (id stable; nextDue recomputed as if created now).
//   Asks for notification authorization, persists, refreshes the scheduled
//   reminders, then dismisses. Parity with Android AddSchedule.
// ===========================================================================

@MainActor
final class AddScheduleViewModel: ObservableObject {
    @Published var status: FormStatus = .idle
    @Published var error: String?

    var isSubmitting: Bool {
        if case .submitting = status { return true }
        return false
    }

    func save(_ schedule: NewDoseSchedule, editScheduleId: Int64?, session: VaultService) async -> Bool {
        status = .submitting
        error = nil
        do {
            if let id = editScheduleId {
                // In-place edit: id stays stable, active/createdAtMs untouched.
                _ = try await session.updateSchedule(id, schedule)
            } else {
                _ = try await session.addSchedule(schedule)
            }
            status = .done
            return true
        } catch {
            self.error = describe(error)
            status = .error(describe(error))
            return false
        }
    }
}

struct AddScheduleView: View {
    let medId: Int64
    /// When set, the screen edits this reminder instead of creating one.
    let editScheduleId: Int64?

    @EnvironmentObject private var app: AppState
    @Environment(\.palette) private var palette
    @Environment(\.dismiss) private var dismiss
    @StateObject private var vm = AddScheduleViewModel()

    @State private var kind = "interval"
    // interval
    @State private var intervalUnitHours = true   // true: heures, false: minutes
    @State private var intervalValue = 12
    // daily / days_interval time of day
    @State private var time = AddScheduleView.defaultTime()
    // days_interval
    @State private var days = 3
    @State private var startDate = Date()
    // Custom reminder text ("Aller chercher le traitement"…), max 60 chars.
    @State private var label = ""
    @State private var seeded = false
    /// The reminder being edited, kept so a cadence-preserving edit can keep
    /// its running countdown instead of recomputing from "now".
    @State private var loadedSchedule: DoseSchedule?

    init(medId: Int64, editScheduleId: Int64? = nil) {
        self.medId = medId
        self.editScheduleId = editScheduleId
    }

    /// Convenience spelling used by callers that carry the core's field name
    /// (e.g. RemindersView editing a schedule from its `medicationId`).
    init(medicationId: Int64, editScheduleId: Int64? = nil) {
        self.init(medId: medicationId, editScheduleId: editScheduleId)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Spacing.l) {
                kindCard
                fieldsCard
                labelCard
                if let e = vm.error { ErrorBanner(message: e) }
                saveButton
            }
            .padding(Spacing.l)
        }
        .navigationTitle(editScheduleId == nil ? "Nouveau planning" : "Modifier le rappel")
        .task {
            // Seed FIRST — the permission prompt can hold the task open for
            // seconds, and the edit form must not sit on default values with
            // a live Save button meanwhile.
            await seedFromExistingSchedule()
            _ = await NotificationManager.requestAuthorization()
        }
    }

    /// Editing: seed the form from the existing reminder, once. A failed load
    /// keeps `seeded` false, which keeps Save disabled — saving an unseeded
    /// form would silently rewrite the real reminder as "interval / 12 h".
    private func seedFromExistingSchedule() async {
        guard let id = editScheduleId, !seeded, let session = app.session else { return }
        let schedules = (try? await session.listSchedulesForMedication(medId, includeInactive: true)) ?? []
        guard let s = schedules.first(where: { $0.id == id }) else {
            vm.error = "Rappel introuvable."
            return
        }
        loadedSchedule = s
        kind = s.kind
        if let mins = s.intervalMinutes {
            let m = Int(mins)
            intervalUnitHours = m % 60 == 0
            intervalValue = m % 60 == 0 ? m / 60 : m
        }
        let cal = Calendar.current
        time = cal.date(bySettingHour: Int(s.dailyHour ?? 8),
                        minute: Int(s.dailyMinute ?? 0),
                        second: 0, of: Date()) ?? time
        if let d = s.intervalDays { days = Int(d) }
        label = s.label ?? ""
        // Anchor the N-day cycle on the current next-due day, not today — a
        // label-only edit must not shift the phase of a 14-day injection cycle.
        startDate = cal.startOfDay(for: Date(timeIntervalSince1970: Double(s.nextDueAtMs) / 1000.0))
        seeded = true
    }

    private var kindCard: some View {
        SectionCard {
            Text("Type de planning").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
            FlowChips {
                ChoiceChip(label: "Intervalle", selected: kind == "interval") { kind = "interval" }
                ChoiceChip(label: "Quotidien", selected: kind == "daily") { kind = "daily" }
                ChoiceChip(label: "Tous les N jours", selected: kind == "days_interval") { kind = "days_interval" }
            }
        }
    }

    @ViewBuilder
    private var fieldsCard: some View {
        switch kind {
        case "interval":
            SectionCard {
                Text("Intervalle").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
                HStack(spacing: Spacing.s) {
                    ChoiceChip(label: "Heures", selected: intervalUnitHours) { intervalUnitHours = true }
                    ChoiceChip(label: "Minutes", selected: !intervalUnitHours) { intervalUnitHours = false }
                }
                stepperRow(label: intervalUnitHours ? "Toutes les … heures" : "Toutes les … minutes",
                           value: $intervalValue,
                           range: 1...(intervalUnitHours ? 168 : 1440))
            }
        case "daily":
            SectionCard {
                Text("Heure").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
                DatePicker("Heure de prise", selection: $time, displayedComponents: .hourAndMinute)
                    .font(.eggBody)
                    .tint(palette.primary)
            }
        default:
            SectionCard {
                Text("Intervalle en jours").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
                stepperRow(label: "Tous les … jours", value: $days, range: 1...365)
            }
            SectionCard {
                Text("Heure").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
                DatePicker("Heure de prise", selection: $time, displayedComponents: .hourAndMinute)
                    .font(.eggBody)
                    .tint(palette.primary)
            }
            SectionCard {
                Text("Date de départ").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
                DatePicker("Date de départ", selection: $startDate, displayedComponents: .date)
                    .font(.eggBody)
                    .tint(palette.primary)
            }
        }
    }

    private var labelCard: some View {
        SectionCard {
            Text("Texte du rappel (optionnel)").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
            TextField("Ex. : aller chercher le traitement", text: $label)
                .font(.eggBody)
                .foregroundStyle(palette.onSurface)
                .onChange(of: label) { _, newValue in
                    if newValue.count > 60 { label = String(newValue.prefix(60)) }
                }
            Text("Affiché à la place du nom du traitement. Jamais montré en mode générique.")
                .font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.6))
        }
    }

    private func stepperRow(label: String, value: Binding<Int>, range: ClosedRange<Int>) -> some View {
        HStack {
            Text(label).font(.eggCallout).foregroundStyle(palette.onSurface)
            Spacer()
            TextField(label, value: value, format: .number)
                .keyboardType(.numberPad)
                .multilineTextAlignment(.trailing)
                .frame(width: 64)
                .font(.eggBody)
                .foregroundStyle(palette.onSurface)
            Stepper("", value: value, in: range).labelsHidden()
        }
    }

    private var saveButton: some View {
        Button {
            save()
        } label: {
            if vm.isSubmitting {
                ProgressView().tint(palette.onPrimary).frame(maxWidth: .infinity)
            } else {
                Text("Enregistrer").frame(maxWidth: .infinity)
            }
        }
        .glassProminentButton()
        .tint(palette.primary)
        // In edit mode, block Save until the reminder actually seeded — see
        // seedFromExistingSchedule().
        .disabled(vm.isSubmitting || (editScheduleId != nil && !seeded))
    }

    private func save() {
        guard let session = app.session else { return }
        let schedule = buildSchedule()
        Task {
            let ok = await vm.save(schedule, editScheduleId: editScheduleId, session: session)
            if ok {
                await app.refreshNotifications()
                dismiss()
            }
        }
    }

    private func buildSchedule() -> NewDoseSchedule {
        let cal = Calendar.current
        let comps = cal.dateComponents([.hour, .minute], from: time)
        let hour = comps.hour ?? 0
        let minute = comps.minute ?? 0
        let timed = (kind == "daily" || kind == "days_interval")

        // Clamp before the UInt32 conversions — a typed negative in the free
        // TextFields would otherwise trap at runtime; the core additionally
        // rejects zero cadences with a typed error.
        let mins: UInt32? = kind == "interval"
            ? UInt32(max(1, intervalUnitHours ? intervalValue * 60 : intervalValue))
            : nil
        let dh: UInt32?   = timed ? UInt32(max(0, hour)) : nil
        let dm: UInt32?   = timed ? UInt32(max(0, minute)) : nil
        let dInt: UInt32? = kind == "days_interval" ? UInt32(max(1, days)) : nil

        let trimmedLabel = label.trimmingCharacters(in: .whitespacesAndNewlines)
        var schedule = NewDoseSchedule(
            medicationId: medId,
            kind: kind,
            intervalMinutes: mins,
            dailyHour: dh,
            dailyMinute: dm,
            intervalDays: dInt,
            nextDueAtMs: 0,
            label: trimmedLabel.isEmpty ? nil : trimmedLabel)

        // days_interval anchors its first occurrence on the chosen start day;
        // "interval" keeps its running countdown across a cadence-preserving
        // edit; everything else anchors on "now".
        if kind == "days_interval" {
            // Anchor = chosen start day at HH:MM, kept as-is while still
            // ahead, else stepped N days at a time until after now (mirrors
            // Android daysIntervalNextDue). Editing seeds the start day from
            // the current next-due day, so a label-only edit keeps the phase.
            var dc = cal.dateComponents([.year, .month, .day], from: startDate)
            dc.hour = hour
            dc.minute = minute
            dc.second = 0
            let step = max(1, days)
            let now = Date()
            var next = cal.date(from: dc) ?? startDate
            while next <= now {
                next = cal.date(byAdding: .day, value: step, to: next)
                    ?? next.addingTimeInterval(Double(step) * 86_400)
            }
            schedule.nextDueAtMs = Int64(next.timeIntervalSince1970 * 1000)
        } else if kind == "interval",
                  let existing = loadedSchedule,
                  existing.kind == "interval",
                  existing.intervalMinutes == mins,
                  existing.nextDueAtMs > Time.nowMs() {
            // Keep the running countdown when the cadence didn't change — a
            // label-only edit must not push an "every 12 h" reminder back by
            // however far into the cycle the user happened to be.
            schedule.nextDueAtMs = existing.nextDueAtMs
        } else {
            schedule.nextDueAtMs = NextDueCalculator.firstDue(schedule)
        }
        return schedule
    }

    /// Default time-of-day: 08:00 today (only h/m are read back).
    private static func defaultTime() -> Date {
        let cal = Calendar.current
        return cal.date(bySettingHour: 8, minute: 0, second: 0, of: Date()) ?? Date()
    }
}

// Private layout helper: wraps the kind chips onto multiple lines if needed.
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
