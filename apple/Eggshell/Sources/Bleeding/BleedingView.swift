import SwiftUI
import TransitionCore

// Menstruations — the `Menstruations` segment of the Ressenti screen (§6.7)
// and the pushed
// screen of `Route.bleeding`, which shows exactly the same thing.
//
// Descriptive only: eggshell writes down what happened and never predicts a
// cycle (§11). Nothing was dropped on the way — the indicator editor of the
// « bleeding » domain is the line at the top of the segment (D5).

@MainActor
final class BleedingViewModel: ObservableObject {
    @Published var loading = true
    @Published var entries: [BleedingEntry] = []
    @Published var error: String?

    /// A stretch of consecutive logged days, so a period reads as one thing
    /// rather than as six separate rows.
    struct Run: Identifiable {
        let start: Date
        let end: Date
        let dayCount: Int
        var id: Date { start }
    }
    @Published var runs: [Run] = []

    func load(_ session: VaultService) async {
        loading = true
        error = nil
        do {
            let loaded = try await session.listBleedingEntries(limit: 500)
                .sorted { $0.atMs > $1.atMs }
            entries = loaded
            runs = Self.runs(loaded, cal: Calendar.current)
        } catch {
            self.error = describe(error)
        }
        loading = false
    }

    /// Groups the logged days into continuous runs, most recent first. Two
    /// entries on the same day count once: the run is about days, not rows.
    static func runs(_ entries: [BleedingEntry], cal: Calendar) -> [Run] {
        let days = Set(entries.map {
            cal.startOfDay(for: Date(timeIntervalSince1970: Double($0.atMs) / 1000))
        })
        var out: [Run] = []
        var start: Date?
        var previous: Date?

        func close() {
            guard let start, let previous else { return }
            let span = (cal.dateComponents([.day], from: start, to: previous).day ?? 0) + 1
            out.append(Run(start: start, end: previous, dayCount: span))
        }

        for day in days.sorted() {
            if let last = previous,
               let next = cal.date(byAdding: .day, value: 1, to: last),
               cal.isDate(next, inSameDayAs: day) {
                previous = day
                continue
            }
            close()
            start = day
            previous = day
        }
        close()
        return Array(out.reversed())
    }
}

// MARK: - Segment

/// The body of the `Menstruations` segment. Owns its own query so it can live
/// either
/// inside Ressenti or under its own route without either copy knowing.
struct BleedingSection: View {
    @EnvironmentObject private var app: AppState
    @EnvironmentObject private var router: Router
    @Environment(\.palette) private var palette
    @StateObject private var vm = BleedingViewModel()

    let search: String
    let reloadTick: Int

    init(search: String = "", reloadTick: Int = 0) {
        self.search = search
        self.reloadTick = reloadTick
    }

    var body: some View {
        VStack(alignment: .leading, spacing: Metrics.blockGap) {
            ListGroup {
                ListRowView(
                    title: "Tes indicateurs",
                    subtitle: "Abondance, douleur, crampes — et les tiens",
                    systemImage: "slider.horizontal.3",
                    iconContainer: palette.primaryContainer,
                    iconTint: palette.onPrimaryContainer,
                    showsChevron: true,
                    action: { router.push(.metricEditor(domain: "bleeding")) })
            }

            if let run = vm.runs.first, search.isEmpty {
                lastRunCard(run)
            }

            SectionTitleView("Ce que tu as noté", prominent: true)

            if vm.loading {
                SkeletonBlock(height: 74, cornerRadius: Radius.card)
                SkeletonBlock(height: 74, cornerRadius: Radius.card)
            } else if let message = vm.error {
                ErrorCardView(message, retryLabel: "Réessayer") { reload() }
            } else if visibleEntries.isEmpty {
                if search.isEmpty {
                    EmptyStateView(
                        "Rien de noté pour l'instant. Quand ça arrive, note-le ici — "
                            + "tu verras la bande apparaître sur ton calendrier.",
                        systemImage: "drop",
                        actionLabel: "Noter mes menstruations",
                        action: { router.push(.addBleeding(id: nil)) })
                } else {
                    EmptyStateView(
                        "Rien ne correspond à « \(search) ».",
                        systemImage: "magnifyingglass")
                }
            } else {
                ForEach(visibleEntries, id: \.id) { entry in
                    entryCard(entry)
                }
                Text("eggshell ne devine rien et ne prédit aucun cycle : "
                        + "il garde seulement la trace de ce que tu vis.")
                    .font(EggFont.bodyS)
                    .foregroundStyle(palette.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, Spacing.xs)
            }
        }
        .task(id: reloadTick) { await reloadAsync() }
    }

    private var visibleEntries: [BleedingEntry] {
        let query = search.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return vm.entries }
        let needle = query.folding(
            options: [.diacriticInsensitive, .caseInsensitive],
            locale: Locale(identifier: "fr_FR"))
        return vm.entries.filter { entry in
            (entry.freeText ?? "")
                .folding(
                    options: [.diacriticInsensitive, .caseInsensitive],
                    locale: Locale(identifier: "fr_FR"))
                .contains(needle)
        }
    }

    private func reload() { Task { await reloadAsync() } }

    private func reloadAsync() async {
        guard let session = app.session else { return }
        await vm.load(session)
    }

    // MARK: Cards

    /// The most recent stretch, stated plainly. A count of days is a fact; a
    /// forecast would not be one.
    private func lastRunCard(_ run: BleedingViewModel.Run) -> some View {
        EggCard(variant: .low, spacing: Spacing.xs) {
            MicroLabel("DERNIÈRE PÉRIODE NOTÉE")
            Text(Self.runLabel(run))
                .font(EggFont.titleL)
                .foregroundStyle(palette.onSurface)
                .fixedSize(horizontal: false, vertical: true)
            Text(run.dayCount == 1 ? "Un jour noté." : "\(run.dayCount) jours notés d'affilée.")
                .font(EggFont.bodyS)
                .foregroundStyle(palette.onSurfaceVariant)
        }
    }

    private func entryCard(_ entry: BleedingEntry) -> some View {
        EggCard(
            variant: .low,
            spacing: Spacing.s,
            action: { router.push(.addBleeding(id: entry.id)) }
        ) {
            HStack(alignment: .firstTextBaseline, spacing: Spacing.s) {
                Text(Self.dayLabel(entry.atMs))
                    .font(EggFont.titleS)
                    .foregroundStyle(palette.onSurface)
                Spacer(minLength: Spacing.s)
                kindPill(entry.isSpotting)
            }
            if let text = entry.freeText, !text.isEmpty {
                Text(text)
                    .font(.eggBody)
                    .foregroundStyle(palette.onSurfaceVariant)
                    .lineLimit(2)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .accessibilityElement(children: .combine)
    }

    /// Glyph + colour + word, all three: nothing is carried by colour alone (§10).
    @ViewBuilder
    private func kindPill(_ isSpotting: Bool?) -> some View {
        switch isSpotting {
        case .some(false):
            StatusPillView(
                "Menstruations", systemImage: "drop.fill",
                container: palette.errorContainer, content: palette.onErrorContainer)
        case .some(true):
            StatusPillView(
                "Spotting", systemImage: "drop",
                container: palette.tertiaryContainer, content: palette.onTertiaryContainer)
        case .none:
            StatusPillView(
                "Non précisé", systemImage: "questionmark",
                container: palette.surfaceContainerHighest, content: palette.onSurfaceVariant)
        }
    }

    // MARK: Formatting

    /// « Du 12 au 17 juillet », « Le 3 juin ».
    static func runLabel(_ run: BleedingViewModel.Run) -> String {
        let cal = Calendar.current
        let day = DateFormatter()
        day.locale = Locale(identifier: "fr_FR")
        let full = DateFormatter()
        full.locale = Locale(identifier: "fr_FR")
        full.dateFormat = cal.isDate(run.end, equalTo: Date(), toGranularity: .year)
            ? "d MMMM" : "d MMMM yyyy"

        if cal.isDate(run.start, inSameDayAs: run.end) {
            return "Le \(full.string(from: run.start))"
        }
        if cal.isDate(run.start, equalTo: run.end, toGranularity: .month) {
            day.dateFormat = "d"
        } else {
            day.dateFormat = "d MMMM"
        }
        return "Du \(day.string(from: run.start)) au \(full.string(from: run.end))"
    }

    /// « Aujourd'hui », « Hier », « Samedi 12 juillet ».
    static func dayLabel(_ ms: Int64, now: Date = Date()) -> String {
        let date = Date(timeIntervalSince1970: Double(ms) / 1000)
        let cal = Calendar.current
        if cal.isDate(date, inSameDayAs: now) { return "Aujourd'hui" }
        if let yesterday = cal.date(byAdding: .day, value: -1, to: now),
           cal.isDate(date, inSameDayAs: yesterday) {
            return "Hier"
        }
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "fr_FR")
        formatter.dateFormat = cal.isDate(date, equalTo: now, toGranularity: .year)
            ? "EEEE d MMMM" : "d MMMM yyyy"
        let text = formatter.string(from: date)
        return text.prefix(1).uppercased() + text.dropFirst()
    }
}

// MARK: - Écran poussé

/// `Route.bleeding` — the same segment, reached directly. Ressenti is where it
/// normally lives, but a deep link (or a tile) must still land somewhere sane.
struct BleedingView: View {
    @EnvironmentObject private var router: Router
    @Environment(\.palette) private var palette

    @State private var search = ""
    @State private var reloadTick = 0
    /// Where this screen sits in the stack, so it can reload when what was
    /// pushed on top of it pops back.
    @State private var depth: Int?

    var body: some View {
        ScrollView {
            BleedingSection(search: search, reloadTick: reloadTick)
                .padding(.horizontal, Metrics.screenMargin)
                .padding(.top, Spacing.s)
                .padding(.bottom, Metrics.blockGap)
        }
        .background(palette.surface.ignoresSafeArea())
        .navigationTitle("Menstruations")
        .navigationBarTitleDisplayMode(.inline)
        .searchable(text: $search, prompt: "Rechercher dans tes notes")
        .eggActionBar {
            ActionBarButton("Noter mes menstruations", systemImage: "plus") {
                router.push(.addBleeding(id: nil))
            }
        }
        .onAppear { if depth == nil { depth = router.path.count } }
        .onChange(of: router.path.count) { _, current in
            if current == depth { reloadTick += 1 }
        }
    }
}
