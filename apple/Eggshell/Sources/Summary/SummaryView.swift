import SwiftUI
import TransitionCore

// ===========================================================================
// PUSHED screen (Route.summary) — the weekly/monthly "résumé". Compares the
// current period against the equal-length window just before it: average mood,
// doses logged vs planned (estimated), custom symptom sliders, journal count —
// with an encouraging headline. On-device only, no LLM. Mirrors the android
// SummaryRepository + SummaryScreen.
//
// Honesty notes baked in (same as Android): there is no stored "missed dose"
// (a dose row exists only on a Pris/Passer tap), so `expected` is ESTIMATED by
// replaying each active schedule's cadence, and the current period is compared
// against the same elapsed duration ending at the previous period boundary.
// ===========================================================================

enum SummaryPeriod: String, CaseIterable { case week, month }

struct SummaryCustomMetric: Identifiable {
    let id: Int64
    let label: String
    let current: Double?
    let previous: Double?
}

struct SummaryResult {
    var moodCurrent: Double?
    var moodPrevious: Double?
    var journalCountCurrent = 0
    var journalCountPrevious = 0
    var takenCurrent = 0
    var takenPrevious = 0
    var skippedCurrent = 0
    var skippedPrevious = 0
    var expectedCurrent = 0
    var expectedPrevious = 0
    var customMetrics: [SummaryCustomMetric] = []
    var hasMedications = false
    var hasJournal = false

    var missedCurrent: Int { max(0, expectedCurrent - takenCurrent - skippedCurrent) }
    var missedPrevious: Int { max(0, expectedPrevious - takenPrevious - skippedPrevious) }
    var hasData: Bool {
        journalCountCurrent > 0 || journalCountPrevious > 0 ||
        takenCurrent > 0 || takenPrevious > 0 || expectedCurrent > 0 || expectedPrevious > 0
    }
}

private struct WindowStats {
    var mood: Double?
    var journalCount = 0
    var taken = 0
    var skipped = 0
    var expected = 0
    var custom: [Int64: Double] = [:]
}

@MainActor
final class SummaryViewModel: ObservableObject {
    @Published var loading = true
    @Published var result: SummaryResult?

    func compute(_ session: VaultService, period: SummaryPeriod, gates: FeaturesStore) async {
        loading = true
        let cal = Calendar.current
        let now = Date()
        let periodStart: Date = {
            switch period {
            case .week: return cal.dateInterval(of: .weekOfYear, for: now)?.start ?? now
            case .month: return cal.dateInterval(of: .month, for: now)?.start ?? now
            }
        }()
        let elapsed = max(0, now.timeIntervalSince(periodStart))
        let prevStart = periodStart.addingTimeInterval(-elapsed)

        let schedules = (try? await session.listActiveSchedules()) ?? []
        let customDefs = ((try? await session.listMetricDefinitions(domain: "journal")) ?? [])
            .filter { !$0.builtin }
        let journalAll = await journalEntries(session, since: prevStart)
        let doseAll = (try? await session.listDoseEventsBetween(
            fromMs: Int64(prevStart.timeIntervalSince1970 * 1000),
            toMs: Int64(now.timeIntervalSince1970 * 1000))) ?? []

        let current = await windowStats(session, from: periodStart, to: now,
                                        journal: journalAll, doses: doseAll, schedules: schedules, customDefs: customDefs)
        let previous = await windowStats(session, from: prevStart, to: periodStart,
                                         journal: journalAll, doses: doseAll, schedules: schedules, customDefs: customDefs)

        let custom = customDefs.compactMap { def -> SummaryCustomMetric? in
            let c = current.custom[def.id], p = previous.custom[def.id]
            guard c != nil || p != nil else { return nil }
            return SummaryCustomMetric(id: def.id, label: def.label, current: c, previous: p)
        }

        var r = SummaryResult()
        r.moodCurrent = current.mood; r.moodPrevious = previous.mood
        r.journalCountCurrent = current.journalCount; r.journalCountPrevious = previous.journalCount
        r.takenCurrent = current.taken; r.takenPrevious = previous.taken
        r.skippedCurrent = current.skipped; r.skippedPrevious = previous.skipped
        r.expectedCurrent = current.expected; r.expectedPrevious = previous.expected
        r.customMetrics = custom
        r.hasMedications = gates.medications
        r.hasJournal = gates.journal
        result = r
        loading = false
    }

    /// Page the journal newest-first until we've covered everything since
    /// `since`, so a heavy logger doesn't lose the older window to a page cap.
    private func journalEntries(_ session: VaultService, since: Date) async -> [JournalEntry] {
        let sinceMs = Int64(since.timeIntervalSince1970 * 1000)
        var out: [JournalEntry] = []
        var offset: Int64 = 0
        let page: Int64 = 500
        var iter = 0
        while iter < 200 {
            iter += 1
            let batch = (try? await session.listJournalEntries(offset: offset, limit: page)) ?? []
            if batch.isEmpty { break }
            out += batch
            if batch.count < Int(page) || (batch.last?.atMs ?? 0) < sinceMs { break }
            offset += page
        }
        return out
    }

    private func windowStats(
        _ session: VaultService, from: Date, to: Date,
        journal: [JournalEntry], doses: [DoseEvent],
        schedules: [DoseSchedule], customDefs: [MetricDefinition]
    ) async -> WindowStats {
        let fromMs = Int64(from.timeIntervalSince1970 * 1000)
        let toMs = Int64(to.timeIntervalSince1970 * 1000)
        let entries = journal.filter { $0.atMs >= fromMs && $0.atMs < toMs }

        let moods = entries.compactMap { $0.mood.map { Int($0) } }
        let mood = moods.isEmpty ? nil : Double(moods.reduce(0, +)) / Double(moods.count)

        var sums: [Int64: (Double, Int)] = [:]
        if !customDefs.isEmpty {
            let ids = Set(customDefs.map { $0.id })
            for e in entries {
                let vals = (try? await session.listMetricValues(entryDomain: "journal", entryId: e.id)) ?? []
                for v in vals where ids.contains(v.metricId) {
                    let (s, c) = sums[v.metricId] ?? (0, 0)
                    sums[v.metricId] = (s + Double(v.value), c + 1)
                }
            }
        }
        let custom = sums.mapValues { $0.0 / Double($0.1) }

        let windowDoses = doses.filter { $0.takenAtMs >= fromMs && $0.takenAtMs < toMs }
        let taken = windowDoses.filter { $0.status == "taken" }.count
        let skipped = windowDoses.filter { $0.status == "skipped" }.count
        let expected = expectedDoses(schedules, from: from, to: to)

        return WindowStats(mood: mood, journalCount: entries.count, taken: taken, skipped: skipped, expected: expected, custom: custom)
    }

    /// Estimate scheduled doses in [from, to] by replaying each schedule's
    /// cadence (clamped to when it was created). For "days_interval" the live
    /// next-due is rolled back to before the window first, so past occurrences
    /// aren't skipped.
    private func expectedDoses(_ schedules: [DoseSchedule], from: Date, to: Date) -> Int {
        let cal = Calendar.current
        var total = 0
        for s in schedules where s.active {
            let created = Date(timeIntervalSince1970: Double(s.createdAtMs) / 1000)
            let effFrom = max(from, created)
            if effFrom >= to { continue }
            switch s.kind {
            case "interval":
                let mins = Double(s.intervalMinutes ?? 0)
                if mins <= 0 { continue }
                total += max(0, Int(to.timeIntervalSince(effFrom) / (mins * 60)))
            case "daily":
                var occ = firstDaily(Int(s.dailyHour ?? 0), Int(s.dailyMinute ?? 0), onOrAfter: effFrom, cal: cal)
                var iter = 0
                while occ < to && iter < 10000 {
                    total += 1
                    occ = cal.date(byAdding: .day, value: 1, to: occ) ?? occ.addingTimeInterval(86400)
                    iter += 1
                }
            case "days_interval":
                let days = max(1, Int(s.intervalDays ?? 1))
                var anchor = Date(timeIntervalSince1970: Double(s.nextDueAtMs) / 1000)
                var roll = 0
                while anchor > effFrom && roll < 100000 {
                    anchor = cal.date(byAdding: .day, value: -days, to: anchor) ?? anchor.addingTimeInterval(-Double(days) * 86400)
                    roll += 1
                }
                var occ = anchor
                var iter = 0
                while occ < to && iter < 100000 {
                    if occ >= effFrom { total += 1 }
                    occ = cal.date(byAdding: .day, value: days, to: occ) ?? occ.addingTimeInterval(Double(days) * 86400)
                    iter += 1
                }
            default:
                break
            }
        }
        return total
    }

    private func firstDaily(_ h: Int, _ m: Int, onOrAfter date: Date, cal: Calendar) -> Date {
        var comps = cal.dateComponents([.year, .month, .day], from: date)
        comps.hour = h; comps.minute = m; comps.second = 0
        let today = cal.date(from: comps) ?? date
        return today >= date ? today : (cal.date(byAdding: .day, value: 1, to: today) ?? date.addingTimeInterval(86400))
    }
}

struct SummaryView: View {
    @EnvironmentObject private var app: AppState
    @EnvironmentObject private var features: FeaturesStore
    @Environment(\.palette) private var palette
    @StateObject private var vm = SummaryViewModel()
    @AppStorage("summary_period") private var periodRaw = SummaryPeriod.month.rawValue

    private var period: SummaryPeriod { SummaryPeriod(rawValue: periodRaw) ?? .month }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Spacing.m) {
                Picker("Période", selection: $periodRaw) {
                    Text("Semaine").tag(SummaryPeriod.week.rawValue)
                    Text("Mois").tag(SummaryPeriod.month.rawValue)
                }
                .pickerStyle(.segmented)

                if vm.loading {
                    // Show the spinner on every (re)compute, not just the first —
                    // otherwise switching Semaine/Mois leaves the previous period's
                    // numbers on screen under the new label until the async finishes.
                    ProgressView().tint(palette.primary).frame(maxWidth: .infinity).padding(.vertical, Spacing.xxl)
                } else if let r = vm.result, r.hasData {
                    headlineCard(r)
                    if r.hasJournal { moodCard(r) }
                    if r.hasMedications && (r.expectedCurrent > 0 || r.takenCurrent > 0 || r.expectedPrevious > 0) {
                        dosesCard(r)
                    }
                    if !r.customMetrics.isEmpty { symptomsCard(r) }
                    if r.hasJournal { journalCard(r) }
                    Text("Comparé à la même durée juste avant. À titre indicatif, sans lien de cause à effet.")
                        .font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.6))
                } else {
                    EmptyStateCard(
                        text: "Pas encore assez de données pour comparer. Continue à noter tes ressentis et tes prises, ton résumé arrivera bientôt 💛",
                        systemImage: "chart.bar")
                }
            }
            .padding(Spacing.l)
        }
        .background(palette.surface.ignoresSafeArea())
        .navigationTitle("Ton résumé")
        .navigationBarTitleDisplayMode(.inline)
        .task(id: periodRaw) {
            if let s = app.session { await vm.compute(s, period: period, gates: features) }
        }
    }

    private func headlineCard(_ r: SummaryResult) -> some View {
        let text: String
        if let m = r.moodCurrent, let p = r.moodPrevious, m >= p + 0.3 {
            text = "Belle énergie : ton humeur moyenne est en hausse ✨"
        } else if r.hasMedications && r.missedCurrent < r.missedPrevious {
            text = "Bravo — moins d'oublis que la période précédente 💪"
        } else if r.journalCountCurrent > 0 {
            text = "Tu prends le temps de noter — continue, ça compte 💛"
        } else {
            text = "Tu gardes le cap. Prends soin de toi 💛"
        }
        return SectionCard {
            Text(text).font(.eggHeadline).foregroundStyle(palette.onSurface)
        }
    }

    private func moodCard(_ r: SummaryResult) -> some View {
        SectionCard {
            Text("Humeur moyenne").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
            HStack(alignment: .firstTextBaseline, spacing: Spacing.s) {
                Text("\(fmt(r.moodCurrent)) / 10").font(.eggTitle).foregroundStyle(palette.primary)
                Text("vs \(fmt(r.moodPrevious)) avant").font(.eggCallout).foregroundStyle(palette.onSurface.opacity(0.6))
            }
        }
    }

    private func dosesCard(_ r: SummaryResult) -> some View {
        SectionCard {
            Text("Prises de traitement").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
            Text("\(r.takenCurrent) prises notées sur \(r.expectedCurrent) prévues")
                .font(.eggHeadline).foregroundStyle(palette.onSurface)
            Text("\(r.missedCurrent) oublis estimés (vs \(r.missedPrevious) avant)")
                .font(.eggCallout).foregroundStyle(palette.onSurface.opacity(0.7))
            Text("« Prévues » et « oublis » sont estimés à partir de tes rappels.")
                .font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.6))
        }
    }

    private func symptomsCard(_ r: SummaryResult) -> some View {
        SectionCard {
            Text("Symptômes (curseurs perso)").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
            ForEach(r.customMetrics) { m in
                HStack {
                    Text(m.label).font(.eggCallout).foregroundStyle(palette.onSurface)
                    Spacer()
                    Text("\(fmt(m.current)) (vs \(fmt(m.previous)) avant)")
                        .font(.eggCallout).foregroundStyle(palette.onSurface.opacity(0.7))
                }
            }
        }
    }

    private func journalCard(_ r: SummaryResult) -> some View {
        SectionCard {
            Text("Notes du journal").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
            Text("\(r.journalCountCurrent) entrées (vs \(r.journalCountPrevious) avant)")
                .font(.eggCallout).foregroundStyle(palette.onSurface)
        }
    }

    private func fmt(_ v: Double?) -> String {
        guard let v else { return "—" }
        return String(format: "%.1f", v)
    }
}
