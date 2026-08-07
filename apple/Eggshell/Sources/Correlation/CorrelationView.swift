import SwiftUI
import TransitionCore

// Corrélations — the third segment of Ressenti (§6.7), and `Route.correlation`
// for anything that links straight to it.
//
// One shared time axis, the graphic vocabulary of §5.1 and nothing else: mood is
// the main curve in `primary` (the only one that gets a gradient area), an
// intake logged on time is a `tertiary` dot, a late one a `secondary` cross, a
// skipped one an `error` cross, a treatment change a dashed `secondary`
// vertical, a bleeding day an `error` tick at the bottom. The legend is carried
// by the axis gradations, never by a row under the plot.
//
// Everything here is descriptive. The screen says so out loud, and it never
// implies a cause.

@MainActor
final class CorrelationViewModel: ObservableObject {
    @Published var loading = true
    @Published var error: String?

    @Published var days: Int = 90
    @Published var fromMs: Int64 = 0
    @Published var toMs: Int64 = 0
    @Published var moodPoints: [(t: Int64, v: Int)] = []
    /// Logged intakes, split by §5.1's three states.
    @Published var onTimeDoses: [Int64] = []
    @Published var lateDoses: [Int64] = []
    @Published var missedDoses: [Int64] = []
    @Published var treatmentChanges: [Int64] = []
    @Published var bleedingDays: [Int64] = []
    /// Nights a dream was recorded, placed by night_ms and never by when the
    /// entry was typed — a dream written this morning about last week belongs
    /// last week, and a lane drawn from the writing time would sit next to the
    /// wrong doses.
    @Published var dreamNights: [Int64] = []
    @Published var lucidNights: [Int64] = []
    /// Ranked links between doses, sleep and mood. Strongest first.
    @Published var insights: [Insight] = []

    /// Average mood on the days with a logged intake vs. the other days, and the
    /// same split for bleeding days. Nil until each side has enough entries to
    /// be worth stating.
    @Published var doseSplit: (with: Double, without: Double)?
    @Published var bleedingSplit: (with: Double, without: Double)?

    /// Below this many entries on a side, an average is an anecdote.
    private let minimumForSplit = 3

    func load(_ session: VaultService, bleedingEnabled: Bool) async {
        loading = true
        error = nil
        let now = Time.nowMs()
        let from = now - Int64(days) * 86_400_000
        let cal = Calendar.current
        do {
            let entries = try await session.listJournalEntries(limit: 1000)
                .filter { $0.atMs >= from && $0.atMs <= now && $0.mood != nil }
                .sorted { $0.atMs < $1.atMs }
            let mood = entries.map { (t: $0.atMs, v: Int($0.mood ?? 0)) }

            let doses = try await session.listDoseEventsBetween(fromMs: from, toMs: now)
            var onTime: [Int64] = []
            var late: [Int64] = []
            var missed: [Int64] = []
            for dose in doses {
                guard dose.status == "taken" else {
                    missed.append(dose.takenAtMs)
                    continue
                }
                // D2: an intake with no planned time is counted as logged and
                // kept out of the punctuality split — no time is ever invented.
                guard let planned = dose.scheduledAtMs else {
                    onTime.append(dose.takenAtMs)
                    continue
                }
                let delta = Int((dose.takenAtMs - planned) / 60_000)
                if Punctuality.timing(delta) == .late {
                    late.append(dose.takenAtMs)
                } else {
                    onTime.append(dose.takenAtMs)
                }
            }

            let changes = try await session.listTreatmentChanges(fromMs: from, toMs: now)
                .map { $0.atMs }

            var bleeds: [Int64] = []
            if bleedingEnabled {
                bleeds = try await session.listBleedingEntries(limit: 1000)
                    .filter { $0.atMs >= from && $0.atMs <= now }
                    .map { $0.atMs }
            }

            let dreams = try await session.listDreamsBetween(fromMs: from, toMs: now)

            // Local midnights are computed here and handed down: the core
            // cannot know the timezone, and a DST day is not 86 400 000 ms
            // long, so dividing the range arithmetically would drift an hour
            // twice a year and file entries against the wrong day.
            var dayStarts: [Int64] = []
            var cursor = cal.startOfDay(for: Date(timeIntervalSince1970: Double(from) / 1000))
            let end = Date(timeIntervalSince1970: Double(now) / 1000)
            while cursor <= end {
                dayStarts.append(Int64(cursor.timeIntervalSince1970 * 1000))
                guard let next = cal.date(byAdding: .day, value: 1, to: cursor) else { break }
                cursor = next
            }
            let found = (try? await session.insights(
                fromMs: from, toMs: now, dayStartsMs: dayStarts)) ?? []

            let doseDays = Set((onTime + late).map { day($0, cal) })
            let bleedDays = Set(bleeds.map { day($0, cal) })

            fromMs = from
            toMs = now
            moodPoints = mood
            onTimeDoses = onTime
            lateDoses = late
            missedDoses = missed
            treatmentChanges = changes
            bleedingDays = bleeds
            dreamNights = dreams.map { $0.nightMs }
            // Lucid rides its own lane rather than a flag on the first: it is
            // rare, and a marker that only sometimes means something is one the
            // eye learns to ignore.
            lucidNights = dreams.filter { $0.lucid }.map { $0.nightMs }
            insights = found
            doseSplit = split(entries, marked: doseDays, cal: cal)
            bleedingSplit = bleedingEnabled ? split(entries, marked: bleedDays, cal: cal) : nil
        } catch {
            self.error = describe(error)
        }
        loading = false
    }

    func selectWindow(_ value: Int, session: VaultService?, bleedingEnabled: Bool) async {
        days = value
        guard let session else { return }
        await load(session, bleedingEnabled: bleedingEnabled)
    }

    private func day(_ ms: Int64, _ cal: Calendar) -> Date {
        cal.startOfDay(for: Date(timeIntervalSince1970: Double(ms) / 1000))
    }

    /// Mean mood inside vs. outside a set of days. Nil unless both sides carry
    /// `minimumForSplit` entries: two numbers built on one entry each would look
    /// like a finding and be none.
    private func split(
        _ entries: [JournalEntry], marked: Set<Date>, cal: Calendar
    ) -> (with: Double, without: Double)? {
        var inside: [Double] = []
        var outside: [Double] = []
        for entry in entries {
            guard let mood = entry.mood else { continue }
            if marked.contains(day(entry.atMs, cal)) {
                inside.append(Double(mood))
            } else {
                outside.append(Double(mood))
            }
        }
        guard inside.count >= minimumForSplit, outside.count >= minimumForSplit else { return nil }
        return (inside.reduce(0, +) / Double(inside.count),
                outside.reduce(0, +) / Double(outside.count))
    }

    var loggedCount: Int { onTimeDoses.count + lateDoses.count }

    var averageMood: Double? {
        guard !moodPoints.isEmpty else { return nil }
        return Double(moodPoints.reduce(0) { $0 + $1.v }) / Double(moodPoints.count)
    }

    var hasGraphData: Bool {
        moodPoints.count >= 2 || loggedCount > 0 || !missedDoses.isEmpty
    }
}

// MARK: - Segment

/// The body of the `Corrélations` segment.
struct CorrelationSection: View {
    @EnvironmentObject private var app: AppState
    @EnvironmentObject private var features: FeaturesStore
    @Environment(\.palette) private var palette
    @StateObject private var vm = CorrelationViewModel()

    let reloadTick: Int

    init(reloadTick: Int = 0) {
        self.reloadTick = reloadTick
    }

    private static let windows = [30, 90, 180]

    var body: some View {
        VStack(alignment: .leading, spacing: Metrics.blockGap) {
            windowPills

            if vm.loading {
                SkeletonBlock(height: 208, cornerRadius: Radius.card)
                SkeletonBlock(height: 88, cornerRadius: Radius.card)
            } else if let message = vm.error {
                ErrorCardView(message, retryLabel: "Réessayer") { reload() }
            } else if !vm.hasGraphData {
                EmptyStateView(
                    "Pas encore assez de données sur cette période. Continue à noter tes "
                        + "ressentis et tes prises : la vue se remplira d'elle-même.",
                    systemImage: "chart.xyaxis.line")
            } else {
                chartCard
                InsightsCard(insights: vm.insights)
                readingCard
            }

            Text("Corrélation ≠ causalité : ces courbes décrivent ce que tu as noté, "
                    + "elles n'expliquent rien et ne remplacent pas un avis médical.")
                .font(EggFont.bodyS)
                .foregroundStyle(palette.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
        }
        .task(id: reloadTick) { await reloadAsync() }
    }

    private var windowPills: some View {
        HStack(spacing: Spacing.s) {
            ForEach(Self.windows, id: \.self) { value in
                PillView("\(value) jours", selected: vm.days == value) {
                    Task {
                        await vm.selectWindow(
                            value, session: app.session, bleedingEnabled: features.bleeding)
                    }
                }
            }
        }
    }

    private func reload() { Task { await reloadAsync() } }

    private func reloadAsync() async {
        guard let session = app.session else { return }
        await vm.load(session, bleedingEnabled: features.bleeding)
    }

    // MARK: Chart

    private var chartCard: some View {
        EggCard(variant: .low, paddingH: 18, paddingV: 18, spacing: Spacing.m) {
            Canvas { context, size in
                draw(in: context, size: size)
            }
            .frame(height: 212)
            .accessibilityElement()
            .accessibilityLabel(accessibilityText)

            CardRule()
            HStack(spacing: 0) {
                Text(Self.dayLabel(vm.fromMs))
                Spacer(minLength: Spacing.s)
                Text(Self.dayLabel(vm.toMs))
            }
            .font(EggFont.micro)
            .foregroundStyle(palette.onSurfaceVariant)
        }
    }

    /// Width of the label column. The gradations *are* the legend (§5.1), so the
    /// gutter has to be wide enough for the widest word.
    private let gutter: CGFloat = 58

    private func draw(in context: GraphicsContext, size: CGSize) {
        let plotLeft = gutter + 8
        let plotRight = max(plotLeft + 1, size.width)
        let plotWidth = plotRight - plotLeft

        let moodTop: CGFloat = 9
        let moodBottom = size.height * 0.60
        let doseY = size.height * 0.775
        let bleedY = size.height * 0.945
        let span = CGFloat(max(1, vm.toMs - vm.fromMs))

        func xFor(_ ms: Int64) -> CGFloat {
            let fraction = CGFloat(ms - vm.fromMs) / span
            return plotLeft + min(max(fraction, 0), 1) * plotWidth
        }
        func moodY(_ value: Int) -> CGFloat {
            let fraction = CGFloat(min(max(value, 0), 10)) / 10
            return moodBottom - fraction * (moodBottom - moodTop)
        }
        func label(_ text: String, _ color: Color, at y: CGFloat) {
            context.draw(
                Text(text).font(EggFont.micro).foregroundStyle(color),
                at: CGPoint(x: gutter - 6, y: y), anchor: .trailing)
        }
        func rule(_ y: CGFloat, _ color: Color, dashed: Bool = false) {
            var path = Path()
            path.move(to: CGPoint(x: plotLeft, y: y))
            path.addLine(to: CGPoint(x: plotRight, y: y))
            context.stroke(
                path, with: .color(color),
                style: dashed ? StrokeStyle(lineWidth: 1, dash: [4, 4]) : StrokeStyle(lineWidth: 1))
        }

        // Gradations of the mood band, each carrying its own word.
        rule(moodY(10), palette.chartGrid)
        rule(moodY(5), palette.chartGrid)
        rule(moodY(0), palette.chartGrid)
        label("HUMEUR 10", palette.primary, at: moodY(10))
        label("5", palette.onSurfaceVariant, at: moodY(5))
        label("0", palette.onSurfaceVariant, at: moodY(0))

        // Treatment changes cross the whole plot: they are context for every
        // band at once, not a series of their own.
        for change in vm.treatmentChanges {
            let x = xFor(change)
            var path = Path()
            path.move(to: CGPoint(x: x, y: moodTop))
            path.addLine(to: CGPoint(x: x, y: bleedY + 6))
            context.stroke(
                path, with: .color(palette.secondary),
                style: StrokeStyle(lineWidth: 1.6, dash: [6, 6]))
        }

        // The main curve: polyline, gradient area, fatter terminal dot (§5.1).
        if vm.moodPoints.count >= 2 {
            var line = Path()
            for (index, point) in vm.moodPoints.enumerated() {
                let target = CGPoint(x: xFor(point.t), y: moodY(point.v))
                if index == 0 { line.move(to: target) } else { line.addLine(to: target) }
            }
            var area = line
            if let last = vm.moodPoints.last, let first = vm.moodPoints.first {
                area.addLine(to: CGPoint(x: xFor(last.t), y: moodBottom))
                area.addLine(to: CGPoint(x: xFor(first.t), y: moodBottom))
                area.closeSubpath()
            }
            context.fill(
                area,
                with: .linearGradient(
                    Gradient(colors: [palette.primary.opacity(0.26), palette.primary.opacity(0)]),
                    startPoint: CGPoint(x: 0, y: moodTop),
                    endPoint: CGPoint(x: 0, y: moodBottom)))
            context.stroke(
                line, with: .color(palette.primary),
                style: StrokeStyle(lineWidth: 2.4, lineCap: .round, lineJoin: .round))
        }
        for (index, point) in vm.moodPoints.enumerated() {
            let center = CGPoint(x: xFor(point.t), y: moodY(point.v))
            let radius: CGFloat = index == vm.moodPoints.count - 1 ? 4.2 : 2.8
            context.fill(
                Path(ellipseIn: CGRect(
                    x: center.x - radius, y: center.y - radius,
                    width: radius * 2, height: radius * 2)),
                with: .color(palette.primary))
        }

        // Intakes, on their own line under the curve.
        rule(doseY, palette.chartGrid, dashed: true)
        label(vm.lateDoses.isEmpty ? "PRISES" : "PRISES ↑", palette.tertiary, at: doseY)
        for dose in vm.onTimeDoses {
            let x = xFor(dose)
            context.fill(
                Path(ellipseIn: CGRect(x: x - 3.2, y: doseY - 3.2, width: 6.4, height: 6.4)),
                with: .color(palette.tertiary))
        }
        for dose in vm.lateDoses { cross(context, x: xFor(dose), y: doseY, color: palette.secondary) }
        for dose in vm.missedDoses { cross(context, x: xFor(dose), y: doseY, color: palette.error) }

        // Bleeding days: a tick row at the very bottom, only when there are any.
        if !vm.bleedingDays.isEmpty {
            label("MENSTRUATIONS", palette.error, at: bleedY)
            for bleed in vm.bleedingDays {
                let x = xFor(bleed)
                var tick = Path()
                tick.move(to: CGPoint(x: x, y: bleedY - 5))
                tick.addLine(to: CGPoint(x: x, y: bleedY + 5))
                context.stroke(tick, with: .color(palette.error), lineWidth: 2.6)
            }
        }
    }

    private func cross(_ context: GraphicsContext, x: CGFloat, y: CGFloat, color: Color) {
        var path = Path()
        path.move(to: CGPoint(x: x - 3.6, y: y - 3.6))
        path.addLine(to: CGPoint(x: x + 3.6, y: y + 3.6))
        path.move(to: CGPoint(x: x - 3.6, y: y + 3.6))
        path.addLine(to: CGPoint(x: x + 3.6, y: y - 3.6))
        context.stroke(path, with: .color(color), lineWidth: 2)
    }

    // MARK: Reading

    /// What the period says, in words. A plain sentence is the only part of a
    /// chart a screen reader — or a tired evening — can actually use.
    private var readingCard: some View {
        EggCard(variant: .low, spacing: Spacing.s) {
            MicroLabel("CE QUE ÇA RACONTE")
            Text(headline)
                .font(.eggBody)
                .foregroundStyle(palette.onSurface)
                .fixedSize(horizontal: false, vertical: true)
            if let sentence = doseSentence {
                Text(sentence)
                    .font(EggFont.bodyS)
                    .foregroundStyle(palette.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if let sentence = bleedingSentence {
                Text(sentence)
                    .font(EggFont.bodyS)
                    .foregroundStyle(palette.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private var headline: String {
        var parts: [String] = []
        if let mood = vm.averageMood {
            parts.append("humeur moyenne \(Self.oneDecimal(mood))/10")
        }
        if vm.loggedCount > 0 {
            var doses = "\(vm.loggedCount) \(vm.loggedCount == 1 ? "prise notée" : "prises notées")"
            if !vm.lateDoses.isEmpty { doses += " dont \(vm.lateDoses.count) en retard" }
            parts.append(doses)
        }
        if !vm.missedDoses.isEmpty {
            parts.append("\(vm.missedDoses.count) "
                + (vm.missedDoses.count == 1 ? "passée" : "passées"))
        }
        if !vm.treatmentChanges.isEmpty {
            parts.append("\(vm.treatmentChanges.count) "
                + (vm.treatmentChanges.count == 1
                    ? "changement de traitement" : "changements de traitement"))
        }
        guard !parts.isEmpty else {
            return "Sur les \(vm.days) derniers jours, tu n'as encore rien noté."
        }
        return "Sur les \(vm.days) derniers jours : " + parts.joined(separator: ", ") + "."
    }

    private var doseSentence: String? {
        guard let split = vm.doseSplit else { return nil }
        return "Les jours où tu as noté une prise, ton humeur est en moyenne à "
            + "\(Self.oneDecimal(split.with))/10 — les autres jours, "
            + "\(Self.oneDecimal(split.without))/10."
    }

    private var bleedingSentence: String? {
        guard let split = vm.bleedingSplit else { return nil }
        return "Les jours de menstruations, ton humeur est en moyenne à "
            + "\(Self.oneDecimal(split.with))/10 — hors menstruations, "
            + "\(Self.oneDecimal(split.without))/10."
    }

    private var accessibilityText: String {
        var text = headline
        if let sentence = doseSentence { text += " " + sentence }
        if let sentence = bleedingSentence { text += " " + sentence }
        return text
    }

    // MARK: Formatting

    private static func oneDecimal(_ value: Double) -> String {
        String(format: "%.1f", value).replacingOccurrences(of: ".", with: ",")
    }

    private static func dayLabel(_ ms: Int64) -> String {
        guard ms > 0 else { return "" }
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "fr_FR")
        formatter.dateFormat = "d MMM"
        return formatter.string(from: Date(timeIntervalSince1970: Double(ms) / 1000))
    }
}

// MARK: - Écran poussé

/// `Route.correlation` — the same segment under its own title, for the links
/// that point straight at it.
struct CorrelationView: View {
    @Environment(\.palette) private var palette

    var body: some View {
        ScrollView {
            CorrelationSection()
                .padding(.horizontal, Metrics.screenMargin)
                .padding(.top, Spacing.s)
                .padding(.bottom, Metrics.blockGap)
        }
        .background(palette.surface.ignoresSafeArea())
        .navigationTitle("Corrélations")
        .navigationBarTitleDisplayMode(.inline)
    }
}
