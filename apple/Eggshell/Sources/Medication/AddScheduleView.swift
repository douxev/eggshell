import SwiftUI
import TransitionCore

// ===========================================================================
// PUSHED screen — create a new dose schedule for a medication.
//   • kind picker via ChoiceChip (interval / daily / days_interval)
//   • fields shown depend on the selected kind
//   • builds a NewDoseSchedule, computes its first due via NextDueCalculator,
//     then persists with session.addSchedule and dismisses.
// ===========================================================================

@MainActor
final class AddScheduleViewModel: ObservableObject {
    @Published var status: FormStatus = .idle
    @Published var error: String?

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

    @State private var submitting = false
    @State private var kind = "interval"
    @State private var hours = 12
    @State private var hour = 8
    @State private var minute = 0
    @State private var days = 7

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Spacing.l) {
                kindCard
                fieldsCard
                saveButton
                if let e = vm.error { ErrorBanner(message: e) }
            }
            .padding(Spacing.l)
        }
        .navigationTitle("Nouveau planning")
    }

    private var kindCard: some View {
        SectionCard {
            Text("Type de planning").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
            HStack(spacing: Spacing.s) {
                ChoiceChip(label: "Intervalle", selected: kind == "interval") { kind = "interval" }
                ChoiceChip(label: "Quotidien", selected: kind == "daily") { kind = "daily" }
                ChoiceChip(label: "Tous les N jours", selected: kind == "days_interval") { kind = "days_interval" }
            }
        }
    }

    private var fieldsCard: some View {
        SectionCard {
            switch kind {
            case "interval":
                stepperRow(label: "Heures", value: $hours, range: 1...168)
            case "daily":
                stepperRow(label: "Heure", value: $hour, range: 0...23)
                stepperRow(label: "Minute", value: $minute, range: 0...59)
            default:
                stepperRow(label: "Jours", value: $days, range: 1...365)
                stepperRow(label: "Heure", value: $hour, range: 0...23)
                stepperRow(label: "Minute", value: $minute, range: 0...59)
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
        Button("Enregistrer") {
            if let session = app.session {
                submitting = true
                Task {
                    var schedule = buildSchedule()
                    schedule.nextDueAtMs = NextDueCalculator.firstDue(schedule)
                    let ok = await vm.save(schedule, session: session)
                    submitting = false
                    if ok { dismiss() }
                }
            }
        }
        .glassProminentButton()
        .tint(palette.primary)
        .frame(maxWidth: .infinity)
        .disabled(submitting)
    }

    private func buildSchedule() -> NewDoseSchedule {
        // Explicitly-typed optionals: feeding nested `? UInt32(x) : nil` ternaries
        // straight into the initializer overloads the type solver and makes it
        // emit a misleading "extra argument" error.
        let timed = (kind == "daily" || kind == "days_interval")
        let intervalMinutes: UInt32? = kind == "interval" ? UInt32(hours * 60) : nil
        let dailyHour: UInt32? = timed ? UInt32(hour) : nil
        let dailyMinute: UInt32? = timed ? UInt32(minute) : nil
        let intervalDays: UInt32? = kind == "days_interval" ? UInt32(days) : nil

        var schedule = NewDoseSchedule(
            medicationId: medId,
            kind: kind,
            intervalMinutes: intervalMinutes,
            dailyHour: dailyHour,
            dailyMinute: dailyMinute,
            intervalDays: intervalDays,
            nextDueAtMs: 0)
        schedule.nextDueAtMs = NextDueCalculator.firstDue(schedule)
        return schedule
    }
}
