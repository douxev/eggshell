import SwiftUI
import TransitionCore

// ===========================================================================
// Médics — create or edit a reminder (handoff §6.5, « Schémas de prise ·
// Ajouter »).
//
// The three cadences the core supports, and nothing invented on top:
//   • interval      — toutes les N heures / minutes
//   • daily         — chaque jour à HH:MM
//   • days_interval — tous les N jours à HH:MM, phase anchored on the chosen
//                     start day, so a 14-day injection cycle keeps its rhythm.
//
// Two hard-won behaviours are preserved verbatim in `buildSchedule()`: a
// cadence-preserving edit of an « interval » reminder keeps its running
// countdown (a label-only edit must not push an every-12-h reminder back), and
// the N-day cycle re-anchors on the current next-due day rather than on today.
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

    /// The three cadences, in the order the segmented selector shows them.
    private static let kinds = ["interval", "daily", "days_interval"]
    private static let kindLabels = ["Intervalle", "Chaque jour", "N jours"]

    @State private var kindIndex = 0
    // interval
    @State private var intervalUnitIndex = 0      // 0: heures, 1: minutes
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

    private var kind: String { Self.kinds[max(0, min(kindIndex, Self.kinds.count - 1))] }
    private var intervalUnitHours: Bool { intervalUnitIndex == 0 }
    private var isEditing: Bool { editScheduleId != nil }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Metrics.blockGap) {
                if let e = vm.error {
                    ErrorCardView(e)
                }

                SegmentedSelector(
                    options: Self.kindLabels,
                    selection: $kindIndex,
                    accessibilityLabel: "Rythme du rappel")

                cadenceBlock
                previewCard
                labelBlock
            }
            .padding(.horizontal, Metrics.screenMargin)
            .padding(.top, Spacing.xs)
            .padding(.bottom, Metrics.blockGap)
        }
        .medsScreen(isEditing ? "Modifier le rappel" : "Nouveau rappel")
        .eggActionBar {
            ActionBarButton(
                isEditing ? "Enregistrer" : "Programmer",
                systemImage: "bell.fill",
                enabled: !vm.isSubmitting && !(isEditing && !seeded)
            ) { save() }
        }
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
            vm.error = "Ce rappel est introuvable. Reviens en arrière et réessaie."
            return
        }
        loadedSchedule = s
        kindIndex = Self.kinds.firstIndex(of: s.kind) ?? 0
        if let mins = s.intervalMinutes {
            let m = Int(mins)
            intervalUnitIndex = m % 60 == 0 ? 0 : 1
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

    // MARK: - Blocks

    @ViewBuilder
    private var cadenceBlock: some View {
        switch kind {
        case "interval":
            MedsFormBlock("LA CADENCE") {
                SegmentedSelector(
                    options: ["Heures", "Minutes"],
                    selection: $intervalUnitIndex,
                    accessibilityLabel: "Unité de l'intervalle")
                MedsStepperRow(
                    label: intervalUnitHours ? "Toutes les … heures" : "Toutes les … minutes",
                    value: $intervalValue,
                    range: 1...(intervalUnitHours ? 168 : 1440))
            }
        case "daily":
            MedsFormBlock("LA CADENCE") {
                DatePicker("À quelle heure", selection: $time, displayedComponents: .hourAndMinute)
                    .font(.eggBody)
            }
        default:
            MedsFormBlock(
                "LA CADENCE",
                footnote: "Le cycle part de la date que tu choisis : c'est elle qui donne le rythme."
            ) {
                MedsStepperRow(label: "Tous les … jours", value: $days, range: 1...365)
                DatePicker("À quelle heure", selection: $time, displayedComponents: .hourAndMinute)
                    .font(.eggBody)
                DatePicker("À partir du", selection: $startDate, displayedComponents: .date)
                    .font(.eggBody)
            }
        }
    }

    /// The cadence read back in the words the treatment's detail card will use,
    /// with the first time it will actually ring — so nothing is a surprise
    /// after you tap « Programmer ».
    private var previewCard: some View {
        let draft = buildSchedule()
        return EggCard(variant: .primary, paddingH: 18, paddingV: 16, spacing: 2) {
            MicroLabel("CE QUE ÇA DONNE", color: palette.onPrimaryContainer.opacity(0.75))
            Text(MedFormat.cadence(
                kind: draft.kind,
                intervalMinutes: draft.intervalMinutes.map { Int($0) },
                dailyHour: draft.dailyHour.map { Int($0) },
                dailyMinute: draft.dailyMinute.map { Int($0) },
                intervalDays: draft.intervalDays.map { Int($0) }))
                .font(EggFont.titleS)
            Text("Premier rappel : " + MedFormat.dayAndTime(draft.nextDueAtMs))
                .font(EggFont.bodyS)
                .opacity(0.82)
        }
    }

    private var labelBlock: some View {
        MedsFormBlock("LE TEXTE DU RAPPEL", footnote: labelFootnote) {
            MedsField(
                placeholder: "Ex. : aller chercher le traitement",
                text: $label,
                maxLength: 60)
        }
    }

    /// In generic mode the notification says nothing about the treatment, so a
    /// custom text would never be shown: say so instead of pretending.
    private var labelFootnote: String {
        NotifPrefs.contentMode == .generic
            ? "Affiché à la place du nom du traitement. Tes rappels sont en mode générique pour l'instant : ce texte ne s'affichera nulle part tant que tu n'auras pas changé de mode dans Réglages → Rappels."
            : "Affiché à la place du nom du traitement, dans le rappel."
    }

    // MARK: - Saving

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
