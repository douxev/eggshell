import SwiftUI

// Charts of the refonte. One graphic vocabulary for the whole app (§5.1):
// primary = the main curve, tertiary = an on-time intake, secondary = a late
// one, error = a missed one, `chartGrid` = the grid. The legend is carried by
// the axis gradations, never by a separate row under the plot.

/// Progress ring of the dose card. The 600 ms `cubic-bezier(.2,0,0,1)` curve is
/// the one the handoff specifies for the ring, not a generic easing.
struct ProgressRingView<Center: View>: View {
    @Environment(\.palette) private var palette

    let progress: Double            // 0…1
    var diameter: CGFloat = 64
    var lineWidth: CGFloat = 6
    var tint: Color? = nil
    var track: Color? = nil
    @ViewBuilder var center: () -> Center

    @State private var shown: Double = 0

    var body: some View {
        ZStack {
            Circle()
                .stroke(track ?? palette.surfaceContainerHighest, lineWidth: lineWidth)
            Circle()
                .trim(from: 0, to: shown)
                .stroke(
                    tint ?? palette.primary,
                    style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
                .rotationEffect(.degrees(-90))
            center()
        }
        .frame(width: diameter, height: diameter)
        .onAppear { withAnimation(Self.curve) { shown = clamped } }
        .onChange(of: progress) { _, _ in
            withAnimation(Self.curve) { shown = clamped }
        }
    }

    private var clamped: Double { min(1, max(0, progress)) }
    private static var curve: Animation { .timingCurve(0.2, 0, 0, 1, duration: 0.6) }
}

/// The punctuality chart (§5.2) — new to this refonte, shared by Médics and the
/// doctor report.
///
/// One dot per intake, X proportional to time (never the index). Y is the
/// offset from the prescribed time with `y = 0` at the top; the scale clamps to
/// the largest delay **of the period**. Under a dashed separator sits the band
/// of the missed doses, with its own axis label.
struct PunctualityChartView: View {
    @Environment(\.palette) private var palette

    let points: [DosePoint]
    var height: CGFloat = 96
    var onTimeToleranceMin: Int = Punctuality.onTimeToleranceMin

    /// Width of the axis-label column, left of the plot.
    private let gutter: CGFloat = 62
    /// Height of the band the missed doses live in.
    private let missedBand: CGFloat = 18

    var body: some View {
        let axis = Punctuality.axis(points)
        Canvas { context, size in
            draw(axis: axis, in: context, size: size)
        }
        .frame(height: height)
        .frame(maxWidth: .infinity)
        .accessibilityElement()
        .accessibilityLabel(accessibilityText(axis))
    }

    private func draw(axis: PunctualityAxis, in context: GraphicsContext, size: CGSize) {
        let plotLeft = gutter + 8
        let plotRight = max(plotLeft + 1, size.width)
        let plotWidth = plotRight - plotLeft

        let separatorY = size.height - missedBand
        let missedCenterY = size.height - missedBand / 2
        // A sliver of headroom above the zero line so an early dose sits just
        // above it instead of escaping the plot.
        let earlyHeadroom: CGFloat = axis.maxEarlyMin > 0 ? 10 : 4
        let zeroY = earlyHeadroom
        let maxY = max(separatorY - 6, zeroY + 1)

        func yFor(_ deltaMin: Int) -> CGFloat {
            let raw: CGFloat
            if deltaMin >= 0 {
                raw = zeroY + CGFloat(deltaMin) / CGFloat(axis.maxDelayMin) * (maxY - zeroY)
            } else if axis.maxEarlyMin > 0 {
                raw = zeroY - CGFloat(-deltaMin) / CGFloat(axis.maxEarlyMin) * earlyHeadroom
            } else {
                raw = zeroY
            }
            return min(maxY, max(0, raw))
        }

        let dashed = StrokeStyle(lineWidth: 1, dash: [4, 4])
        let tickColors = [palette.tertiary, palette.secondary, palette.secondary]

        // Gradations. Zero is the dashed tertiary line, the other two are grid.
        for (index, tick) in axis.ticks.enumerated() {
            let y = yFor(tick)
            var line = Path()
            line.move(to: CGPoint(x: plotLeft, y: y))
            line.addLine(to: CGPoint(x: plotRight, y: y))
            context.stroke(
                line,
                with: .color(index == 0 ? palette.tertiary : palette.chartGrid),
                style: index == 0 ? dashed : StrokeStyle(lineWidth: 1))
            context.draw(
                Text(Punctuality.text(Punctuality.axisLabel(tick)))
                    .font(EggFont.micro)
                    .foregroundStyle(tickColors[min(index, tickColors.count - 1)]),
                at: CGPoint(x: gutter - 5, y: y),
                anchor: .trailing)
        }

        // Separator + the missed band's own axis label.
        var separator = Path()
        separator.move(to: CGPoint(x: plotLeft, y: separatorY))
        separator.addLine(to: CGPoint(x: plotRight, y: separatorY))
        context.stroke(separator, with: .color(palette.chartGrid), style: dashed)
        context.draw(
            Text(Punctuality.missedAxisText(axis.missedCount))
                .font(EggFont.micro)
                .foregroundStyle(palette.error),
            at: CGPoint(x: gutter - 5, y: missedCenterY),
            anchor: .trailing)

        guard !points.isEmpty else { return }

        // X is proportional to time, never to the index (§5.1).
        let firstMs = points.map(\.atMs).min() ?? 0
        let lastMs = points.map(\.atMs).max() ?? firstMs
        let spanMs = max(1, lastMs - firstMs)
        let radius: CGFloat = 3.1

        for point in points {
            let x = plotLeft + CGFloat(Double(point.atMs - firstMs) / Double(spanMs)) * plotWidth
            let timing = Punctuality.timing(point.deltaMin, onTimeToleranceMin: onTimeToleranceMin)
            let y: CGFloat
            let color: Color
            switch timing {
            case .missed:
                y = missedCenterY
                color = palette.error
            case .onTime:
                y = yFor(point.deltaMin ?? 0)
                color = palette.tertiary
            case .late:
                y = yFor(point.deltaMin ?? 0)
                color = palette.secondary
            }
            let dot = Path(ellipseIn: CGRect(
                x: x - radius, y: y - radius, width: radius * 2, height: radius * 2))
            context.fill(dot, with: .color(color))
        }
    }

    /// The chart's text alternative (§10): a canvas has no readable content of
    /// its own, so the whole thing is announced as one sentence.
    private func accessibilityText(_ axis: PunctualityAxis) -> String {
        let onTime = points.filter {
            Punctuality.timing($0.deltaMin, onTimeToleranceMin: onTimeToleranceMin) == .onTime
        }.count
        let late = points.filter {
            Punctuality.timing($0.deltaMin, onTimeToleranceMin: onTimeToleranceMin) == .late
        }.count
        return "Écart à l'heure prévue : \(onTime) à l'heure, \(late) en retard, "
            + "\(axis.missedCount) oubliées."
    }
}
