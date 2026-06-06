import SwiftUI
import TransitionCore

// Pushed screen (Route.correlation). Plots mood over a 30/90/180-day window
// alongside dose markers (taken vs missed), treatment-change verticals and
// bleeding-day ticks. Descriptive only — explicitly NOT medical advice.

@MainActor
final class CorrelationViewModel: ObservableObject {
    @Published var loading = true
    @Published var error: String?

    @Published var days: Int = 90
    @Published var fromMs: Int64 = 0
    @Published var toMs: Int64 = 0
    @Published var moodPoints: [(t: Int64, v: Int)] = []
    @Published var takenDoses: [Int64] = []
    @Published var skippedDoses: [Int64] = []
    @Published var treatmentChanges: [Int64] = []
    @Published var bleedingDays: [Int64] = []

    func load(_ session: VaultService, bleedingEnabled: Bool) async {
        loading = true
        error = nil
        let now = Time.nowMs()
        let from = now - Int64(days) * 86_400_000
        do {
            let mood = try await session.listJournalEntries(limit: 1000)
                .filter { $0.atMs >= from && $0.atMs <= now && $0.mood != nil }
                .map { (t: $0.atMs, v: Int($0.mood ?? 0)) }
                .sorted { $0.t < $1.t }

            let doses = try await session.listDoseEventsBetween(fromMs: from, toMs: now)
            let taken = doses.filter { $0.status == "taken" }.map { $0.takenAtMs }
            let skipped = doses.filter { $0.status != "taken" }.map { $0.takenAtMs }

            let changes = try await session.listTreatmentChanges(fromMs: from, toMs: now)
                .map { $0.atMs }

            var bleeds: [Int64] = []
            if bleedingEnabled {
                bleeds = try await session.listBleedingEntries(limit: 1000)
                    .filter { $0.atMs >= from && $0.atMs <= now }
                    .map { $0.atMs }
            }

            fromMs = from
            toMs = now
            moodPoints = mood
            takenDoses = taken
            skippedDoses = skipped
            treatmentChanges = changes
            bleedingDays = bleeds
        } catch {
            self.error = describe(error)
        }
        loading = false
    }

    func selectWindow(_ d: Int, session: VaultService?, bleedingEnabled: Bool) async {
        days = d
        guard let session else { return }
        await load(session, bleedingEnabled: bleedingEnabled)
    }

    var averageMoodLabel: String {
        guard !moodPoints.isEmpty else { return "—" }
        let avg = Double(moodPoints.reduce(0) { $0 + $1.v }) / Double(moodPoints.count)
        return String(format: "%.1f", avg)
    }

    var hasGraphData: Bool {
        moodPoints.count >= 2 || !takenDoses.isEmpty || !skippedDoses.isEmpty
    }
}

struct CorrelationView: View {
    @EnvironmentObject private var app: AppState
    @EnvironmentObject private var features: FeaturesStore
    @Environment(\.palette) private var palette
    @StateObject private var vm = CorrelationViewModel()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Spacing.m) {
                Text("Visualise ton humeur en regard de tes prises, changements de traitement et jours de saignement. Description uniquement, aucune relation de cause à effet.")
                    .font(.eggCaption)
                    .foregroundStyle(palette.onSurface.opacity(0.6))

                windowSelector

                if vm.loading {
                    ProgressView().tint(palette.primary).frame(maxWidth: .infinity).padding()
                } else {
                    graphCard
                    legend
                    summary
                    disclaimer
                }

                if let e = vm.error { ErrorBanner(message: e) }
            }
            .padding(Spacing.l)
        }
        .background(palette.surface.ignoresSafeArea())
        .navigationTitle("Corrélations")
        .navigationBarTitleDisplayMode(.inline)
        .task { if let s = app.session { await vm.load(s, bleedingEnabled: features.bleeding) } }
    }

    private var windowSelector: some View {
        HStack(spacing: Spacing.s) {
            ForEach([30, 90, 180], id: \.self) { d in
                ChoiceChip(label: "\(d) j", selected: vm.days == d) {
                    Task { await vm.selectWindow(d, session: app.session, bleedingEnabled: features.bleeding) }
                }
            }
        }
    }

    private var graphCard: some View {
        SectionCard {
            if !vm.hasGraphData {
                Text("Pas assez de données sur cette fenêtre.")
                    .font(.eggCallout)
                    .foregroundStyle(palette.onSurface.opacity(0.6))
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.vertical, Spacing.l)
            } else {
                Canvas { context, size in
                    drawGraph(in: context, size: size)
                }
                .frame(height: 240)
                .padding(.vertical, Spacing.s)
            }
        }
    }

    private func drawGraph(in context: GraphicsContext, size: CGSize) {
        let w = size.width
        let h = size.height
        let moodTop: CGFloat = 6
        let moodBottom = h * 0.6
        let doseY = h * 0.76
        let bleedY = h * 0.93
        let span = CGFloat(max(1, vm.toMs - vm.fromMs))

        func xFor(_ t: Int64) -> CGFloat {
            let frac = CGFloat(t - vm.fromMs) / span
            return min(max(frac * w, 0), w)
        }
        func moodYFor(_ v: Int) -> CGFloat {
            let clamped = CGFloat(min(max(v, 0), 10)) / 10.0
            return moodBottom - clamped * (moodBottom - moodTop)
        }

        // Horizontal guide lines at mood 0 / 5 / 10.
        let gridColor = palette.outlineVariant
        for g in [0, 5, 10] {
            let y = moodYFor(g)
            var line = Path()
            line.move(to: CGPoint(x: 0, y: y))
            line.addLine(to: CGPoint(x: w, y: y))
            context.stroke(line, with: .color(gridColor), lineWidth: 1)
        }

        // Treatment-change vertical markers (dashed, full height).
        let dashStyle = StrokeStyle(lineWidth: 2, dash: [8, 8])
        for t in vm.treatmentChanges {
            let x = xFor(t)
            var line = Path()
            line.move(to: CGPoint(x: x, y: 0))
            line.addLine(to: CGPoint(x: x, y: h))
            context.stroke(line, with: .color(palette.secondary), style: dashStyle)
        }

        // Mood polyline + dots.
        let moodColor = palette.primary
        if vm.moodPoints.count >= 2 {
            var path = Path()
            for (i, p) in vm.moodPoints.enumerated() {
                let pt = CGPoint(x: xFor(p.t), y: moodYFor(p.v))
                if i == 0 { path.move(to: pt) } else { path.addLine(to: pt) }
            }
            context.stroke(path, with: .color(moodColor), style: StrokeStyle(lineWidth: 3, lineCap: .round, lineJoin: .round))
        }
        for p in vm.moodPoints {
            let center = CGPoint(x: xFor(p.t), y: moodYFor(p.v))
            let dot = Path(ellipseIn: CGRect(x: center.x - 4, y: center.y - 4, width: 8, height: 8))
            context.fill(dot, with: .color(moodColor))
        }

        // Dose markers: taken dots, missed crosses.
        let takenColor = palette.tertiary
        for t in vm.takenDoses {
            let center = CGPoint(x: xFor(t), y: doseY)
            let dot = Path(ellipseIn: CGRect(x: center.x - 5, y: center.y - 5, width: 10, height: 10))
            context.fill(dot, with: .color(takenColor))
        }
        let missColor = palette.error
        for t in vm.skippedDoses {
            let x = xFor(t)
            var cross = Path()
            cross.move(to: CGPoint(x: x - 5, y: doseY - 5))
            cross.addLine(to: CGPoint(x: x + 5, y: doseY + 5))
            cross.move(to: CGPoint(x: x - 5, y: doseY + 5))
            cross.addLine(to: CGPoint(x: x + 5, y: doseY - 5))
            context.stroke(cross, with: .color(missColor), lineWidth: 2.5)
        }

        // Bleeding ticks at the very bottom.
        for t in vm.bleedingDays {
            let x = xFor(t)
            var tick = Path()
            tick.move(to: CGPoint(x: x, y: bleedY - 5))
            tick.addLine(to: CGPoint(x: x, y: bleedY + 5))
            context.stroke(tick, with: .color(palette.error), lineWidth: 3)
        }
    }

    private var legend: some View {
        VStack(alignment: .leading, spacing: Spacing.xs) {
            legendRow(palette.primary, "Humeur")
            legendRow(palette.tertiary, "Doses prises")
            legendRow(palette.error, "Doses manquées")
            legendRow(palette.secondary, "Changement de traitement")
            if !vm.bleedingDays.isEmpty {
                legendRow(palette.error, "Saignement")
            }
        }
    }

    private func legendRow(_ color: Color, _ label: String) -> some View {
        HStack(spacing: Spacing.s) {
            Circle().fill(color).frame(width: 12, height: 12)
            Text(label).font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.8))
        }
    }

    private var summary: some View {
        SectionCard {
            Text("Sur les \(vm.days) derniers jours : \(vm.takenDoses.count) doses prises, \(vm.skippedDoses.count) manquées, humeur moyenne \(vm.averageMoodLabel)/10, \(vm.treatmentChanges.count) changement(s) de traitement.")
                .font(.eggCallout)
                .foregroundStyle(palette.onSurface)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private var disclaimer: some View {
        Text("Ceci n'est pas un avis médical. Les tendances affichées sont purement descriptives et n'établissent aucun lien de cause à effet.")
            .font(.eggCaption)
            .foregroundStyle(palette.onSurface.opacity(0.6))
    }
}
