import SwiftUI
import TransitionCore

// ===========================================================================
// "Aujourd'hui" — onglet racine. Parité avec android TodayScreen/TodayViewModel.
//   • anneau de progression (doses faites / doses programmées aujourd'hui)
//   • liste cochable des doses du jour (cocher = logDose + advance + notifs)
//   • carte héro "prochaine prise" (ou état vide + CTA)
//   • CTA humeur avec sparkline + lien vers le journal & la corrélation
//   • carte "Rappels à venir" agrégée (plannings médoc futurs + labReminders)
//   • FAB de saisie rapide (Humeur / Prise / Photo / Voix)
// ===========================================================================

// Une dose programmée aujourd'hui, cochée ou non.
struct TodayDoseItem: Identifiable {
    let id: Int64                 // scheduleId
    let medication: Medication
    let scheduledAtMs: Int64
    let done: Bool
}

// Une ligne de la carte "Rappels à venir" (médicament ou rappel labo/photo/voix).
struct TodayUpcoming: Identifiable {
    enum Source { case medication(id: Int64), lab(kind: String) }
    let id: String
    let title: String
    let subtitle: String
    let dueAtMs: Int64
    let source: Source

    var systemImage: String {
        switch source {
        case .medication: return "cross.vial"
        case .lab(let kind): return LabReminderKind.systemImage(kind)
        }
    }
}

@MainActor
final class TodayViewModel: ObservableObject {
    @Published var loading = true
    @Published var error: String?

    @Published var items: [TodayDoseItem] = []          // doses programmées aujourd'hui
    @Published var moodTrend: [Double] = []             // humeurs récentes (ancien→récent)
    @Published var medReminders: [TodayUpcoming] = []   // plannings médoc futurs
    @Published var hasMedications = false

    var doneCount: Int { items.filter { $0.done }.count }
    var totalCount: Int { items.count }
    var nextItem: TodayDoseItem? { items.first { !$0.done } }

    func load(_ session: VaultService, gates: FeaturesStore) async {
        loading = true
        do {
            let cal = Calendar.current
            let startOfDay = cal.startOfDay(for: Date())
            let startOfTomorrow = cal.date(byAdding: .day, value: 1, to: startOfDay) ?? startOfDay
            let startMs = Int64(startOfDay.timeIntervalSince1970 * 1000)
            let tomorrowMs = Int64(startOfTomorrow.timeIntervalSince1970 * 1000)

            // --- État dérivé des médicaments (vide si la fonctionnalité est off) ---
            var todayItems: [TodayDoseItem] = []
            var futureMeds: [TodayUpcoming] = []
            if gates.medications {
                let meds = try await session.listMedications()
                hasMedications = !meds.isEmpty
                let medById = Dictionary(uniqueKeysWithValues: meds.map { ($0.id, $0) })
                let active = try await session.listActiveSchedules()

                // Doses programmées dont l'échéance tombe aujourd'hui.
                for s in active where s.nextDueAtMs >= startMs && s.nextDueAtMs < tomorrowMs {
                    guard let med = medById[s.medicationId] else { continue }
                    let doses = try await session.listDoses(medicationId: med.id, offset: 0, limit: 50)
                    let done = doses.contains { $0.takenAtMs >= startMs && $0.takenAtMs < tomorrowMs }
                    todayItems.append(TodayDoseItem(
                        id: s.id, medication: med, scheduledAtMs: s.nextDueAtMs, done: done))
                }
                todayItems.sort { $0.scheduledAtMs < $1.scheduledAtMs }

                // Plannings futurs (à partir de demain) pour la carte des rappels.
                for s in active where s.nextDueAtMs >= tomorrowMs {
                    guard let med = medById[s.medicationId] else { continue }
                    futureMeds.append(TodayUpcoming(
                        id: "med-\(s.id)",
                        title: med.name,
                        subtitle: NextDueCalculator.describe(s),
                        dueAtMs: s.nextDueAtMs,
                        source: .medication(id: med.id)))
                }
            } else {
                hasMedications = false
            }

            // --- Tendance d'humeur (seulement si le journal est activé) ---
            var mood: [Double] = []
            if gates.journal {
                let recent = try await session.listJournalEntries(offset: 0, limit: 14)
                // listJournalEntries renvoie du plus récent au plus ancien → on inverse
                // pour que la sparkline aille de gauche (ancien) à droite (récent).
                mood = Array(recent.compactMap { $0.mood.map { Double($0) } }.reversed())
            }

            self.items = todayItems
            self.moodTrend = mood
            self.medReminders = futureMeds
        } catch {
            self.error = describe(error)
        }
        loading = false
    }

    /// Coche une dose : enregistre une prise + avance le planning + replanifie.
    func markTaken(_ item: TodayDoseItem, session: VaultService) async -> Bool {
        do {
            let med = item.medication
            _ = try await session.logDose(NewDoseEvent(
                medicationId: med.id,
                takenAtMs: Time.nowMs(),
                dose: med.defaultDose,
                doseUnit: med.defaultDoseUnit,
                route: med.route,
                injectionSite: nil,
                notes: nil,
                status: "taken",
                scheduledAtMs: item.scheduledAtMs,
                scheduleId: item.id))
            // On a besoin du DoseSchedule complet pour calculer la prochaine échéance.
            let active = try await session.listActiveSchedules()
            if let sched = active.first(where: { $0.id == item.id }) {
                try await session.setScheduleNextDue(item.id, NextDueCalculator.advance(sched))
            }
            return true
        } catch {
            self.error = describe(error)
            return false
        }
    }
}

struct TodayView: View {
    @EnvironmentObject private var app: AppState
    @EnvironmentObject private var features: FeaturesStore
    @EnvironmentObject private var labReminders: LabReminderStore
    @EnvironmentObject private var router: Router
    @Environment(\.palette) private var palette
    @StateObject private var vm = TodayViewModel()
    @State private var showQuickLog = false

    var body: some View {
        TabScaffold(title: "Accueil") {
            if vm.loading {
                ProgressView().tint(palette.primary).frame(maxWidth: .infinity).padding()
            } else {
                if let e = vm.error { ErrorBanner(message: e) }
                if features.medications {
                    heroCard
                    if !vm.items.isEmpty { doseListCard }
                }
                if features.journal { journalCTA }
                if features.journal || features.medications { summaryCTA }
                remindersCard
            }
        }
        .overlay(alignment: .bottomTrailing) {
            Button { showQuickLog = true } label: {
                Image(systemName: "plus").font(.title2.weight(.semibold)).frame(width: 60, height: 60)
            }
            .glassProminentButton().tint(palette.primary)
            .clipShape(Circle())
            .padding(Spacing.xl)
        }
        .sheet(isPresented: $showQuickLog) {
            QuickLogSheet().presentationDetents([.medium])
        }
        .task { await reload() }
    }

    private func reload() async {
        if let s = app.session { await vm.load(s, gates: features) }
    }

    // (1)+(3) Carte héro : anneau de progression + prochaine prise (ou état vide).
    private var heroCard: some View {
        let empty = vm.totalCount == 0
        return SectionCard {
            HStack(alignment: .center, spacing: Spacing.l) {
                ProgressRing(
                    progress: empty ? 0 : Double(vm.doneCount) / Double(vm.totalCount),
                    lineWidth: 8,
                    size: 76,
                    center: empty ? "—" : "\(vm.doneCount)/\(vm.totalCount)")
                VStack(alignment: .leading, spacing: 4) {
                    Text("PROCHAINE PRISE")
                        .font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
                    if empty {
                        Text("Aucun planning")
                            .font(.eggHeadline).foregroundStyle(palette.onSurface)
                        Text(vm.hasMedications
                             ? "Programme une prise pour suivre tes doses."
                             : "Ajoute un traitement pour commencer.")
                            .font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.7))
                    } else if let next = vm.nextItem {
                        Text(next.medication.name)
                            .font(.eggHeadline).foregroundStyle(palette.onSurface)
                        Text(doseSubtitle(next.medication, at: next.scheduledAtMs))
                            .font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.7))
                    } else {
                        Text("Tout est pris")
                            .font(.eggHeadline).foregroundStyle(palette.onSurface)
                        Text("Bravo, rien d'autre aujourd'hui.")
                            .font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.7))
                    }
                }
                Spacer(minLength: 0)
            }
            if empty {
                NavigationLink(value: vm.hasMedications ? Route.medicationList : Route.addMedication) {
                    Label(vm.hasMedications ? "Programmer une prise" : "Ajouter un traitement",
                          systemImage: "calendar.badge.plus")
                        .font(.eggCallout)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, Spacing.s)
                }
                .glassProminentButton().tint(palette.primary)
            } else if let next = vm.nextItem {
                Button {
                    Task { await toggle(next) }
                } label: {
                    Label("Marquer comme pris", systemImage: "checkmark.circle.fill")
                        .font(.eggCallout)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, Spacing.s)
                }
                .glassProminentButton().tint(palette.primary)
            }
        }
    }

    // (2) Liste des doses du jour, cochables.
    private var doseListCard: some View {
        SectionCard {
            Text("Aujourd'hui").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
            ForEach(vm.items) { item in
                Button {
                    if !item.done { Task { await toggle(item) } }
                } label: {
                    HStack(spacing: Spacing.m) {
                        Image(systemName: item.done ? "checkmark.circle.fill" : "circle")
                            .font(.title3)
                            .foregroundStyle(item.done ? palette.primary : palette.outline)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(item.medication.name)
                                .font(.eggCallout)
                                .foregroundStyle(item.done
                                    ? palette.onSurface.opacity(0.55) : palette.onSurface)
                            let detail = doseAmount(item.medication)
                            if !detail.isEmpty {
                                Text(detail).font(.eggCaption)
                                    .foregroundStyle(palette.onSurface.opacity(0.6))
                            }
                        }
                        Spacer()
                        Text(timeLabel(item.scheduledAtMs))
                            .font(.eggLabel)
                            .foregroundStyle(item.done
                                ? palette.onSurface.opacity(0.5) : palette.onSurface)
                    }
                    .contentShape(Rectangle())
                    .padding(.vertical, Spacing.xs)
                }
                .buttonStyle(.plain)
                .disabled(item.done)
            }
        }
    }

    // (4) CTA humeur avec sparkline + liens journal & corrélation.
    private var journalCTA: some View {
        VStack(alignment: .leading, spacing: Spacing.s) {
            NavigationLink(value: Route.addJournal(id: nil)) {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Comment te sens-tu ?")
                            .font(.eggHeadline).foregroundStyle(palette.onTertiaryContainer)
                        Text("Note ton humeur & tes effets")
                            .font(.eggCaption).foregroundStyle(palette.onTertiaryContainer.opacity(0.8))
                    }
                    Spacer()
                    Image(systemName: "face.smiling")
                        .font(.title2).foregroundStyle(palette.onTertiaryContainer)
                }
                if vm.moodTrend.count >= 2 {
                    HStack(alignment: .bottom) {
                        Text("HUMEUR RÉCENTE")
                            .font(.eggCaption).foregroundStyle(palette.onTertiaryContainer.opacity(0.7))
                        Spacer()
                        Sparkline(values: vm.moodTrend, tint: palette.onTertiaryContainer, height: 32)
                            .frame(width: 130)
                    }
                    .padding(.top, Spacing.s)
                }
            }
            .buttonStyle(.plain)
            .padding(Spacing.l)
            .frame(maxWidth: .infinity)
            .background(palette.tertiaryContainer,
                        in: RoundedRectangle(cornerRadius: Corner.large, style: .continuous))

            NavigationLink(value: Route.correlation) {
                Label("Voir les corrélations", systemImage: "chart.xyaxis.line")
                    .font(.eggCallout).foregroundStyle(palette.primary)
            }
            .buttonStyle(.plain)
            .padding(.horizontal, Spacing.xs)
        }
    }

    // (4b) Carte d'accès au résumé hebdo/mensuel.
    private var summaryCTA: some View {
        NavigationLink(value: Route.summary) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Ton résumé")
                        .font(.eggHeadline).foregroundStyle(palette.onPrimaryContainer)
                    Text("Compare cette période à la précédente")
                        .font(.eggCaption).foregroundStyle(palette.onPrimaryContainer.opacity(0.8))
                }
                Spacer()
                Image(systemName: "chart.bar.xaxis")
                    .font(.title2).foregroundStyle(palette.onPrimaryContainer)
            }
            .padding(Spacing.l)
            .frame(maxWidth: .infinity)
            .background(palette.primaryContainer,
                        in: RoundedRectangle(cornerRadius: Corner.large, style: .continuous))
        }
        .buttonStyle(.plain)
    }

    // (5) Carte "Rappels à venir" agrégée : plannings médoc + rappels labo/photo/voix.
    private var remindersCard: some View {
        SectionCard {
            Text("Rappels à venir").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
            let all = upcomingReminders()
            if all.isEmpty {
                Text("Aucun rappel à venir")
                    .font(.eggCallout).foregroundStyle(palette.onSurface.opacity(0.6))
            } else {
                ForEach(all) { rem in
                    HStack(spacing: Spacing.m) {
                        Image(systemName: rem.systemImage)
                            .font(.title3).foregroundStyle(palette.primary)
                            .frame(width: 28)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(rem.title).font(.eggCallout).foregroundStyle(palette.onSurface)
                            if !rem.subtitle.isEmpty {
                                Text(rem.subtitle).font(.eggCaption)
                                    .foregroundStyle(palette.onSurface.opacity(0.6))
                            }
                        }
                        Spacer()
                        Text(relativeDueLabel(rem.dueAtMs))
                            .font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.6))
                    }
                    .contentShape(Rectangle())
                    .onTapGesture {
                        if case let .medication(id) = rem.source {
                            router.push(.medicationDetail(id: id))
                        }
                    }
                    .padding(.vertical, Spacing.xs)
                }
            }
        }
    }

    // --- Agrégation des rappels (médoc futurs + labReminders), triés, max 4 ---
    private func upcomingReminders() -> [TodayUpcoming] {
        var all = vm.medReminders
        let nowMs = Time.nowMs()
        for r in labReminders.upcoming() where r.nextDueMs >= nowMs {
            // Masque photo/voix si la fonctionnalité correspondante est désactivée.
            if r.kind == LabReminderKind.photo && !features.photos { continue }
            if r.kind == LabReminderKind.voice && !features.voice { continue }
            all.append(TodayUpcoming(
                id: "lab-\(r.id)",
                title: r.label,
                subtitle: r.intervalDays > 0 ? "Tous les \(r.intervalDays) j" : "",
                dueAtMs: r.nextDueMs,
                source: .lab(kind: r.kind)))
        }
        return Array(all.sorted { $0.dueAtMs < $1.dueAtMs }.prefix(4))
    }

    // --- Actions ---
    private func toggle(_ item: TodayDoseItem) async {
        guard let session = app.session else { return }
        if await vm.markTaken(item, session: session) {
            await app.refreshNotifications()
            await reload()
        }
    }

    // --- Helpers de formatage ---
    private func doseAmount(_ med: Medication) -> String {
        var parts: [String] = []
        if let d = med.defaultDose { parts.append(formatNumber(d)) }
        if let u = med.defaultDoseUnit, !u.isEmpty { parts.append(u) }
        return parts.joined(separator: " ")
    }

    private func doseSubtitle(_ med: Medication, at ms: Int64) -> String {
        let amount = doseAmount(med)
        let time = timeLabel(ms)
        if amount.isEmpty { return "à \(time)" }
        return "\(amount) · à \(time)"
    }

    private func timeLabel(_ ms: Int64) -> String {
        let date = Date(timeIntervalSince1970: Double(ms) / 1000)
        let f = DateFormatter()
        f.locale = Locale(identifier: "fr_FR")
        f.dateFormat = "HH:mm"
        return f.string(from: date)
    }

    private func formatNumber(_ v: Double) -> String {
        if v.rounded() == v { return String(Int(v)) }
        return String(v)
    }
}

// Feuille de saisie rapide (FAB de l'onglet Aujourd'hui). Les tuiles renvoient
// vers le bon écran/onglet. Photo et Voix sautent vers leur onglet (ce ne sont
// pas des routes poussables), corrigeant le bug qui les renvoyait vers la liste
// des médicaments.
struct QuickLogSheet: View {
    @EnvironmentObject private var features: FeaturesStore
    @EnvironmentObject private var router: Router
    @EnvironmentObject private var tabRouter: TabRouter
    @Environment(\.palette) private var palette
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.m) {
            Text("Saisie rapide").font(.eggTitle).foregroundStyle(palette.onSurface)
            LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 3), spacing: Spacing.m) {
                if features.journal {
                    tile("Humeur", "face.smiling") { router.push(.addJournal(id: nil)) }
                }
                if features.medications {
                    tile("Prise", "pills.fill") { router.push(.medicationList) }
                }
                if features.photos {
                    tile("Photo", "camera.fill") { tabRouter.select(.photos) }
                }
                if features.voice {
                    tile("Voix", "waveform") { tabRouter.select(.voice) }
                }
            }
        }
        .padding(Spacing.xl)
    }

    private func tile(_ label: String, _ icon: String, _ action: @escaping () -> Void) -> some View {
        Button {
            dismiss()
            action()
        } label: {
            VStack(spacing: Spacing.s) {
                Image(systemName: icon).font(.title2).foregroundStyle(palette.primary)
                Text(label).font(.eggLabel).foregroundStyle(palette.onSurface)
            }
            .frame(maxWidth: .infinity, minHeight: 84)
            .glassCard(cornerRadius: Corner.medium)
        }
        .buttonStyle(.plain)
    }
}
