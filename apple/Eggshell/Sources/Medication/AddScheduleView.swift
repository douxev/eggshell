import SwiftUI
import TransitionCore

// ===========================================================================
// PUSHED screen — create a new dose schedule for a medication.
//   • kind picker via ChoiceChip (interval / daily / days_interval)
//   • interval     : minutes / heures
//   • daily        : heure (DatePicker)
//   • days_interval: heure (DatePicker) + intervalle en jours + date de départ
//                    → firstDue is computed FROM the chosen start date so the
//                      cadence phase is anchored on that day.
//   Asks for notification authorization, persists with addSchedule, refreshes
//   the scheduled reminders, then dismisses. Parity with Android AddSchedule.
// ===========================================================================

@MainActor
final class AddScheduleViewModel: ObservableObject {
    @Published var status: FormStatus = .idle
    @Published var error: String?

    var isSubmitting: Bool {
        if case .submitting = status { return true }
        return false
    }

    func save(_ schedule: NewDoseSchedule, session: VaultService) async -> Bool {
        status = .submitting
        error = nil
        do {
            _ = try await session.addSchedule(schedule)
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

    init(medId: Int64) {
        self.medId = medId
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Spacing.l) {
                kindCard
                fieldsCard
                if let e = vm.error { ErrorBanner(message: e) }
                saveButton
            }
            .padding(Spacing.l)
        }
        .navigationTitle("Nouveau planning")
        .task { _ = await NotificationManager.requestAuthorization() }
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
        .disabled(vm.isSubmitting)
    }

    private func save() {
        guard let session = app.session else { return }
        let schedule = buildSchedule()
        Task {
            let ok = await vm.save(schedule, session: session)
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

        let mins: UInt32? = kind == "interval"
            ? UInt32(intervalUnitHours ? intervalValue * 60 : intervalValue)
            : nil
        let dh: UInt32?   = timed ? UInt32(hour) : nil
        let dm: UInt32?   = timed ? UInt32(minute) : nil
        let dInt: UInt32? = kind == "days_interval" ? UInt32(days) : nil

        var schedule = NewDoseSchedule(
            medicationId: medId,
            kind: kind,
            intervalMinutes: mins,
            dailyHour: dh,
            dailyMinute: dm,
            intervalDays: dInt,
            nextDueAtMs: 0)

        // days_interval anchors its first occurrence on the chosen start day;
        // the other kinds anchor on "now".
        if kind == "days_interval" {
            // Combine the chosen start date with the chosen time of day.
            var dc = cal.dateComponents([.year, .month, .day], from: startDate)
            dc.hour = hour
            dc.minute = minute
            dc.second = 0
            let anchor = cal.date(from: dc) ?? startDate
            schedule.nextDueAtMs = NextDueCalculator.firstDue(schedule, from: anchor)
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
