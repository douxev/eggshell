import SwiftUI
import TransitionCore

// ===========================================================================
// Médics — the treatment list (handoff §6.4).
//
// Every punctuality figure on this screen comes from `PlannedDoses.window`: the
// row subtitles, the three headline numbers and the chart are three views of
// the same pairing, so they can never contradict each other. When the window
// holds no planned time at all the card shows the empty state rather than a
// misleading « 0 % » — these figures end up in a document handed to a doctor.
// ===========================================================================

@MainActor
final class MedicationListViewModel: ObservableObject {

    enum Filter: Int, CaseIterable {
        case active, all, archived
        static let labels = ["Actifs", "Tous", "Archivés"]
    }

    /// One treatment, with its own regularity over the window.
    struct Row: Identifiable {
        let med: Medication
        /// Nil when this treatment planned nothing over the window (D2).
        let adherencePercent: Int?
        let meanDelayMin: Int?
        var id: Int64 { med.id }
    }

    /// Which sentence the card ends on.
    enum Insight { case good, late, missed, both }

    /// The action the sentence proposes. It is always about a real schedule.
    enum Advice {
        case none
        /// Move the reminder that is chronically answered late.
        case shift(medicationId: Int64, medicationName: String, hour: Int, minute: Int)
        /// Make the reminder that keeps being missed harder to walk past.
        case prioritize(medicationId: Int64, medicationName: String)
    }

    struct Regularity {
        let adherencePercent: Int
        let meanDelayMin: Int
        let missedCount: Int
        let lateCount: Int
        let onTimeCount: Int
        let plannedCount: Int
        let points: [DosePoint]
        let insight: Insight
        let advice: Advice
    }

    @Published var loading = true
    @Published var filterIndex = Filter.active.rawValue
    @Published var rows: [Row] = []
    @Published var hasAnyMedication = false
    /// Nil = nothing to compare over the window; the card shows its empty state.
    @Published var regularity: Regularity?
    @Published var error: String?

    /// The card's period, and the only window the list ever reads.
    static let windowMs: Int64 = 30 * 24 * 60 * 60 * 1000

    var filter: Filter { Filter(rawValue: filterIndex) ?? .active }

    func load(_ session: VaultService) async {
        loading = true
        error = nil
        let all: [Medication]
        do {
            all = try await session.listMedications(includeArchived: true)
        } catch {
            self.error = describe(error)
            loading = false
            return
        }

        let visible: [Medication]
        switch filter {
        case .active:   visible = all.filter { !$0.archived }
        case .all:      visible = all
        case .archived: visible = all.filter { $0.archived }
        }

        let now = Time.nowMs()
        let window = await PlannedDoses.window(
            session: session, fromMs: now - Self.windowMs, toMs: now)
        let occurrences = window.occurrences

        rows = visible.map { med in
            let own = occurrences.filter { $0.medicationId == med.id }
            // Same function as the card's headline figures, so a row can never
            // round differently from the card above it.
            let stats = Punctuality.stats(
                plannedCount: own.count,
                points: own.map {
                    DosePoint(atMs: $0.event?.takenAtMs ?? $0.plannedAtMs, deltaMin: $0.deltaMin)
                })
            return Row(
                med: med,
                adherencePercent: own.isEmpty ? nil : stats.adherencePercent,
                meanDelayMin: own.contains(where: { $0.deltaMin != nil }) ? stats.meanDelayMin : nil)
        }
        hasAnyMedication = !all.isEmpty
        regularity = occurrences.isEmpty ? nil : regularityOf(window, meds: all)
        loading = false
    }

    private func regularityOf(_ window: PlannedWindow, meds: [Medication]) -> Regularity {
        let stats = window.stats
        let tolerance = Punctuality.onTimeToleranceMin
        let late = window.occurrences.filter { ($0.deltaMin ?? 0) > tolerance }
        let missed = window.occurrences.filter { $0.event == nil }
        let planned = window.occurrences.count

        // Two thresholds rather than "any late dose at all": a single late
        // evening in a month is not a pattern, and calling it one would train
        // you to ignore the sentence.
        let lateHeavy = late.count * 5 >= planned
        let missedHeavy = missed.count * 10 >= planned && !missed.isEmpty
        let insight: Insight
        switch (lateHeavy, missedHeavy) {
        case (true, true):   insight = .both
        case (true, false):  insight = .late
        case (false, true):  insight = .missed
        case (false, false): insight = .good
        }

        var byName: [Int64: String] = [:]
        for med in meds { byName[med.id] = med.name }

        let advice: Advice
        switch insight {
        case .good:
            advice = .none
        case .late, .both:
            advice = shiftAdvice(late, byName: byName)
        case .missed:
            if let worst = mostFrequentMedication(missed), let name = byName[worst] {
                advice = .prioritize(medicationId: worst, medicationName: name)
            } else {
                advice = .none
            }
        }

        return Regularity(
            adherencePercent: stats.adherencePercent,
            meanDelayMin: stats.meanDelayMin,
            missedCount: missed.count,
            lateCount: late.count,
            onTimeCount: planned - missed.count - late.count,
            plannedCount: planned,
            points: window.points,
            insight: insight,
            advice: advice)
    }

    /// « Décaler le rappel à … » : take the treatment that runs late most often,
    /// the time of day it runs late at, and slide that time by the median of its
    /// own delays. The median, not the mean — one forgotten evening at 3 a.m.
    /// must not drag the proposal into the night.
    private func shiftAdvice(
        _ late: [PlannedOccurrence],
        byName: [Int64: String],
        calendar: Calendar = .current
    ) -> Advice {
        guard let worst = mostFrequentMedication(late), let name = byName[worst] else { return .none }
        let own = late.filter { $0.medicationId == worst }

        var slots: [Int: Int] = [:]   // minutes-of-day → how many late doses
        for occurrence in own {
            let at = PlannedDoses.date(occurrence.plannedAtMs)
            let comps = calendar.dateComponents([.hour, .minute], from: at)
            let key = (comps.hour ?? 0) * 60 + (comps.minute ?? 0)
            slots[key, default: 0] += 1
        }
        guard let slot = slots.max(by: { $0.value < $1.value })?.key else { return .none }

        let deltas = own.compactMap(\.deltaMin).sorted()
        guard !deltas.isEmpty else { return .none }
        let median = deltas[deltas.count / 2]
        // Round to five minutes: a reminder at 21:37 reads like a machine wrote it.
        let shifted = (slot + (median / 5) * 5) % (24 * 60)
        return .shift(
            medicationId: worst,
            medicationName: name,
            hour: shifted / 60,
            minute: shifted % 60)
    }

    private func mostFrequentMedication(_ occurrences: [PlannedOccurrence]) -> Int64? {
        var counts: [Int64: Int] = [:]
        for occurrence in occurrences { counts[occurrence.medicationId, default: 0] += 1 }
        return counts.max(by: { $0.value < $1.value })?.key
    }

    /// `dose · voie · observance · écart moyen` — the subtitle grammar of §6.4.
    func subtitle(_ row: Row) -> String {
        var parts: [String] = []
        if let dose = MedFormat.doseWithUnit(row.med.defaultDose, row.med.defaultDoseUnit) {
            parts.append(dose)
        }
        parts.append(MedCatalog.routeLabel(row.med.route))
        if let adherence = row.adherencePercent { parts.append(MedFormat.percent(adherence)) }
        if let mean = row.meanDelayMin {
            let label = Punctuality.text(Punctuality.exactLabel(mean))
            // Within tolerance the segment just reads « à l'heure »: "+0 min en
            // moyenne" would be noise dressed up as a measurement.
            parts.append(mean <= Punctuality.onTimeToleranceMin ? label : "\(label) en moyenne")
        }
        if row.med.archived { parts.append("Archivé") }
        return parts.joined(separator: MedFormat.sep)
    }
}

struct MedicationListView: View {
    @EnvironmentObject private var app: AppState
    @EnvironmentObject private var router: Router
    @Environment(\.palette) private var palette
    @StateObject private var vm = MedicationListViewModel()
    @State private var query = ""
    /// Where this screen sits in the stack. A pushed screen's `.task` does not
    /// re-fire when something on top of it pops, so it watches the depth to know
    /// it is showing again — a treatment added or a dose noted has to appear.
    @State private var depth: Int?

    private var needle: String { query.trimmingCharacters(in: .whitespacesAndNewlines) }

    private var visibleRows: [MedicationListViewModel.Row] {
        guard !needle.isEmpty else { return vm.rows }
        return vm.rows.filter { $0.med.name.localizedCaseInsensitiveContains(needle) }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Metrics.blockGap) {
                SegmentedSelector(
                    options: MedicationListViewModel.Filter.labels,
                    selection: $vm.filterIndex,
                    accessibilityLabel: "Traitements affichés")

                if let error = vm.error {
                    ErrorCardView(error, retryLabel: "Réessayer") { reload() }
                }

                if vm.loading {
                    ForEach(0..<3, id: \.self) { _ in SkeletonBlock(height: 76) }
                } else if visibleRows.isEmpty {
                    emptyList
                } else {
                    ListGroup {
                        ForEach(Array(visibleRows.enumerated()), id: \.element.id) { pair in
                            medicationRow(pair.element, showsSeparator: pair.offset < visibleRows.count - 1)
                        }
                    }
                }

                if vm.hasAnyMedication && !vm.loading {
                    regularityHeader
                    if let regularity = vm.regularity {
                        regularityCard(regularity)
                    } else {
                        // D2: no planned time over the period means nothing to
                        // compare — never a fabricated 0 %.
                        EmptyStateView(
                            "Aucune heure prévue sur les 30 derniers jours : je n'ai rien à comparer. Programme un rappel et tes prises se placeront toutes seules ici.",
                            systemImage: "clock.badge.questionmark")
                    }
                }
            }
            .padding(.horizontal, Metrics.screenMargin)
            .padding(.top, Spacing.xs)
            .padding(.bottom, Metrics.blockGap)
        }
        .medsScreen("Médics")
        .searchable(text: $query, prompt: "Nom du traitement")
        .eggActionBar {
            ActionBarButton("Ajouter un traitement", systemImage: "plus") {
                router.push(.addMedication)
            }
        }
        .onChange(of: vm.filterIndex) { _, _ in reload() }
        .onAppear { if depth == nil { depth = router.path.count } }
        .onChange(of: router.path.count) { _, current in
            if current == depth { reload() }
        }
        .task { reload() }
    }

    // MARK: - Rows

    private func medicationRow(
        _ row: MedicationListViewModel.Row,
        showsSeparator: Bool
    ) -> some View {
        // A per-treatment colour is your choice, so it wins over the default
        // tile; the glyph then carries the same hue so the pair stays readable.
        let accent: Color? = row.med.color.map { MedColor.color(fromArgb: $0) }
        return ListRowView(
            title: row.med.name,
            subtitle: vm.subtitle(row),
            badge: MedCatalog.kindLabel(row.med.kind),
            systemImage: MedFormat.routeIcon(row.med.route),
            iconContainer: accent?.opacity(0.18) ?? palette.primaryContainer,
            iconTint: accent ?? palette.onPrimaryContainer,
            showsChevron: true,
            showsSeparator: showsSeparator,
            action: { router.push(.medicationDetail(id: row.med.id)) })
    }

    @ViewBuilder
    private var emptyList: some View {
        if !needle.isEmpty {
            EmptyStateView("Rien ne correspond à « \(needle) ».", systemImage: "magnifyingglass")
        } else if vm.filter == .archived {
            EmptyStateView(
                "Rien d'archivé pour l'instant. Un traitement archivé se retrouve ici, avec tout son historique.",
                systemImage: "archivebox")
        } else {
            EmptyStateView(
                "Tu n'as encore aucun traitement ici. Ajoute-en un et je m'occupe de te rappeler tes prises.",
                systemImage: "pills",
                actionLabel: "Ajouter un traitement") {
                    router.push(.addMedication)
                }
        }
    }

    // MARK: - « Régularité · 30 jours »

    /// The period on the right is a statement, not an action — the card only
    /// ever covers 30 days — so it is not a tap target.
    private var regularityHeader: some View {
        HStack {
            Text("Régularité")
                .font(EggFont.titleS)
                .foregroundStyle(palette.onSurface)
            Spacer(minLength: Spacing.s)
            MicroLabel("30 JOURS")
        }
        .frame(minHeight: 24)
        .padding(.top, Spacing.xs)
    }

    private func regularityCard(_ r: MedicationListViewModel.Regularity) -> some View {
        let meanLabel = Punctuality.text(Punctuality.exactLabel(r.meanDelayMin))
        return EggCard(variant: .low, paddingH: 18, paddingV: 18, spacing: 0) {
            HStack(alignment: .center, spacing: 0) {
                statCell(value: MedFormat.percent(r.adherencePercent), label: "prises notées")
                statDivider
                statCell(value: meanLabel, label: "retard moyen")
                statDivider
                statCell(
                    value: "\(r.missedCount)",
                    label: "oubliées",
                    valueColor: palette.error)
            }

            MicroLabel("ÉCART À L'HEURE PRÉVUE · 30 DERNIÈRES PRISES")
                .padding(.top, 18)

            PunctualityChartView(points: r.points, height: 96)
                .padding(.top, Spacing.xs)

            CardRule().padding(.top, 14)

            Text(insightSentence(r))
                .font(EggFont.bodyS)
                .foregroundStyle(palette.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, Spacing.m)

            adviceLine(r.advice)
        }
    }

    /// What the figures mean, in one sentence, computed from the window — never
    /// the prototype's demonstration copy.
    private func insightSentence(_ r: MedicationListViewModel.Regularity) -> String {
        switch r.insight {
        case .good:
            return "Tout roule : \(r.onTimeCount) prises sur \(r.plannedCount) notées dans les temps."
        case .late:
            return "Tu n'oublies presque rien, mais tu prends souvent tard : \(r.lateCount) prises sur \(r.plannedCount) avec plus d'un quart d'heure de retard."
        case .missed:
            return "Quand tu prends, c'est à l'heure — mais \(r.missedCount) prises sur \(r.plannedCount) sont passées à la trappe."
        case .both:
            return "Sur \(r.plannedCount) prises prévues, \(r.missedCount) sont oubliées et \(r.lateCount) arrivent en retard."
        }
    }

    @ViewBuilder
    private func adviceLine(_ advice: MedicationListViewModel.Advice) -> some View {
        switch advice {
        case .none:
            Text("Rien à changer pour l'instant.")
                .font(EggFont.bodyS)
                .foregroundStyle(palette.onSurfaceVariant)
                .padding(.top, 2)
        case let .shift(medicationId, name, hour, minute):
            adviceButton(
                "Décaler le rappel de \(name) à \(MedFormat.time(hour: hour, minute: minute)) ?",
                medicationId: medicationId)
        case let .prioritize(medicationId, name):
            adviceButton("Rendre le rappel de \(name) plus visible ?", medicationId: medicationId)
        }
    }

    private func adviceButton(_ label: String, medicationId: Int64) -> some View {
        Button { router.push(.medicationDetail(id: medicationId)) } label: {
            Text(label)
                .font(EggFont.label)
                .foregroundStyle(palette.primary)
                .multilineTextAlignment(.leading)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
                .frame(minHeight: Metrics.touchTarget, alignment: .leading)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func statCell(value: String, label: String, valueColor: Color? = nil) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(value)
                .font(.system(size: 22, weight: .semibold))
                .foregroundStyle(valueColor ?? palette.onSurface)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
            Text(label)
                .font(EggFont.bodyS)
                .foregroundStyle(palette.onSurfaceVariant)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var statDivider: some View {
        Rectangle()
            .fill(palette.outlineVariant)
            .frame(width: 1, height: 34)
            .padding(.horizontal, 7)
    }

    // MARK: - Loading

    private func reload() {
        guard let session = app.session else {
            vm.loading = false
            return
        }
        Task { await vm.load(session) }
    }
}
