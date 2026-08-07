import SwiftUI

/// The analyses curve, rebuilt around `MeasureChartModel`.
///
/// What the previous version could not do, and why each is here:
///
/// - **The Y axis carried no numbers.** Three evenly-spaced grey lines were
///   drawn at fixed quarters of the plot and left unlabelled, so the curve
///   showed a *shape* and never a level — the one thing a blood result is
///   consulted for. Gradations now come from `niceTicks` and are written out.
/// - **The X axis was two dates in an HStack underneath.** With no gradations
///   between them, a reading in the middle of a two-year history could not be
///   dated at all.
/// - **There was no viewport.** Every draw spanned the first reading to the
///   last, so a decade of history was permanently squeezed into one card width
///   and the recent weeks — the part anyone actually reads — were a few pixels
///   wide. Pinch and drag now move a `TimeViewport`.
/// - **Nothing could be interrogated.** Tapping the plot now pins the nearest
///   reading and states its date and value, which is also what makes the chart
///   answerable to VoiceOver beyond a single summary sentence.
///
/// The Y axis re-fits the *visible* window rather than the whole series: zoomed
/// into a stable stretch, a curve scaled against a two-year outlier would be a
/// flat line pinned to the bottom of the plot, which is exactly the reading the
/// zoom was performed to escape.
struct MeasureChart: View {
    @Environment(\.palette) private var palette

    let points: [MeasurePoint]
    var unit: String = ""
    var doseMarkers: [Int64] = []
    var treatmentChanges: [Int64] = []
    var height: CGFloat = 180
    var accessibilityText: String = ""

    @State private var viewport = TimeViewport()
    @State private var selected: MeasurePoint?
    /// Viewport at the moment a pinch or drag began. SwiftUI reports gesture
    /// values as totals since the start, not deltas, so applying them to the
    /// live viewport would compound them and the curve would fly off screen.
    @State private var gestureBase: TimeViewport?

    /// Reserve for the widest Y label MeasureFormat produces at a sane
    /// magnitude, and for the X gradations under the plot.
    private let gutter: CGFloat = 52
    private let bottomAxis: CGFloat = 22
    private let rightInset: CGFloat = 4

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ZStack(alignment: .topTrailing) {
                Canvas { context, size in
                    draw(in: context, size: size)
                }
                .frame(height: height)
                .frame(maxWidth: .infinity)
                .contentShape(Rectangle())
                .gesture(zoomGesture)
                .simultaneousGesture(panGesture)
                .onTapGesture { location in pick(at: location) }
                .accessibilityElement()
                .accessibilityLabel(accessibilityText)

                if !viewport.isIdentity {
                    Button("Tout voir") {
                        withAnimation(.easeOut(duration: 0.2)) { viewport = TimeViewport() }
                    }
                    .font(EggFont.labelS)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 5)
                    .background(palette.surfaceContainerHigh, in: Capsule())
                    .padding(6)
                }
            }

            readout
            legend
        }
        // A reading added or removed while zoomed leaves the pinned point
        // dangling and the window describing a range that no longer exists.
        .onChange(of: points) { _, _ in
            selected = nil
            viewport = TimeViewport()
        }
    }

    // MARK: - Gestures

    private var zoomGesture: some Gesture {
        MagnifyGesture()
            .onChanged { value in
                let base = gestureBase ?? viewport
                if gestureBase == nil { gestureBase = base }
                // The pinch centre in plot coordinates, so the curve does not
                // walk away from the fingers holding it.
                let focus = min(max(value.startLocation.x / max(plotWidthGuess, 1), 0), 1)
                viewport = base.zoomed(by: Double(value.magnification), focus: Double(focus))
            }
            .onEnded { _ in gestureBase = nil }
    }

    private var panGesture: some Gesture {
        DragGesture(minimumDistance: 8)
            .onChanged { value in
                let base = gestureBase ?? viewport
                if gestureBase == nil { gestureBase = base }
                viewport = base.panned(by: Double(-value.translation.width / max(plotWidthGuess, 1)))
            }
            .onEnded { _ in gestureBase = nil }
    }

    /// Gestures report positions in the view's own space, and `Canvas` does not
    /// hand its size to them. Screen width less the gutter is close enough for
    /// a focal point and a pan ratio — both are fractions, and being a few
    /// points out shifts the anchor imperceptibly.
    private var plotWidthGuess: CGFloat {
        UIScreen.main.bounds.width - gutter - rightInset - 36
    }

    private func pick(at location: CGPoint) {
        let plotWidth = max(plotWidthGuess, 1)
        let fraction = min(max((location.x - gutter) / plotWidth, 0), 1)
        let hit = MeasureChartMath.nearest(
            in: points, viewport: viewport, fraction: Double(fraction))
        // Tapping the pinned reading again unpins it, so the readout is
        // dismissable without hunting for empty space.
        selected = (hit == selected) ? nil : hit
    }

    // MARK: - Readout and legend

    /// Keeps its height whether or not a reading is pinned — a row that appears
    /// and disappears would shove the legend and every card below it up and
    /// down on each tap.
    private var readout: some View {
        HStack(spacing: 8) {
            if let selected {
                Text(MeasureFormat.fullDate(selected.atMs))
                    .font(EggFont.labelS)
                    .foregroundStyle(palette.onSurfaceVariant)
                Text("\(MeasureFormat.value(selected.value)) \(unit)")
                    .font(EggFont.labelL)
                    .fontWeight(.semibold)
                    .foregroundStyle(palette.onSurface)
            } else {
                MicroLabel("Pincez pour zoomer, glissez pour parcourir. Touchez un point pour le détail.")
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, selected == nil ? 0 : 12)
        .padding(.vertical, selected == nil ? 0 : 5)
        .background(
            selected == nil ? Color.clear : palette.surfaceContainerHigh,
            in: Capsule())
        .frame(height: 30, alignment: .leading)
        .padding(.top, 6)
    }

    /// Every series is named in words as well as coloured, so nothing here is
    /// told by hue alone (§5.1) — including the curve itself, which the previous
    /// legend left unlabelled while naming its two overlays.
    private var legend: some View {
        HStack(spacing: 12) {
            MeasureAxisKey(
                label: unit.isEmpty ? "Taux" : "Taux (\(unit))",
                color: palette.primary,
                dashed: false)
            if !doseMarkers.isEmpty {
                MeasureAxisKey(label: "Prise notée", color: palette.tertiary, dashed: false)
            }
            if !treatmentChanges.isEmpty {
                MeasureAxisKey(label: "Changement de dose", color: palette.secondary, dashed: true)
            }
            Spacer(minLength: 0)
        }
        .padding(.top, 2)
    }

    // MARK: - Drawing

    private func draw(in context: GraphicsContext, size: CGSize) {
        guard points.count >= 2 else { return }

        let plotLeft = gutter
        let plotRight = size.width - rightInset
        let plotWidth = max(plotRight - plotLeft, 1)
        let plotTop: CGFloat = 8
        let plotBottom = max(size.height - bottomAxis, plotTop + 1)
        let plotHeight = plotBottom - plotTop

        let firstMs = points[0].atMs
        let lastMs = points[points.count - 1].atMs
        let totalMs = max(lastMs - firstMs, 1)

        let windowStart = firstMs + Int64(Double(totalMs) * viewport.start)
        let windowEnd = firstMs + Int64(Double(totalMs) * viewport.end)
        let windowMs = max(windowEnd - windowStart, 1)

        func xFor(_ ms: Int64) -> CGFloat {
            plotLeft + plotWidth * CGFloat(Double(ms - windowStart) / Double(windowMs))
        }

        // Y fits the readings that are visible, plus the segment endpoints just
        // outside the window — otherwise a curve entering from off-screen would
        // be scaled against values it does not reach and would leave the plot.
        let visible = points.filter { $0.atMs >= windowStart && $0.atMs <= windowEnd }
        var spanning = visible.map(\.value)
        if points.contains(where: { $0.atMs < windowStart }) {
            spanning.append(MeasureChartMath.interpolate(points, at: windowStart))
        }
        if points.contains(where: { $0.atMs > windowEnd }) {
            spanning.append(MeasureChartMath.interpolate(points, at: windowEnd))
        }
        let range = MeasureChartMath.valueRange(spanning.isEmpty ? points.map(\.value) : spanning)
        let vSpan = (range.max - range.min) > 0 ? (range.max - range.min) : 1

        func yFor(_ v: Double) -> CGFloat {
            plotBottom - plotHeight * CGFloat((v - range.min) / vSpan)
        }

        // -- Y gradations: a line across the plot, its value in the gutter ----
        for tick in MeasureChartMath.niceTicks(min: range.min, max: range.max) {
            let y = yFor(tick)
            guard y >= plotTop - 1, y <= plotBottom + 1 else { continue }
            var line = Path()
            line.move(to: CGPoint(x: plotLeft, y: y))
            line.addLine(to: CGPoint(x: plotRight, y: y))
            context.stroke(line, with: .color(palette.chartGrid), lineWidth: 1)

            let text = Text(MeasureFormat.value(tick))
                .font(EggFont.labelS)
                .foregroundStyle(palette.onSurfaceVariant)
            context.draw(context.resolve(text), at: CGPoint(x: plotLeft - 6, y: y), anchor: .trailing)
        }

        // -- The curve, clipped so a pan cannot paint over the gutter ---------
        var clipped = context
        clipped.clip(to: Path(CGRect(
            x: plotLeft, y: 0, width: plotWidth, height: size.height)))

        var curve = Path()
        var area = Path()
        for (i, point) in points.enumerated() {
            let x = xFor(point.atMs)
            let y = yFor(point.value)
            if i == 0 {
                curve.move(to: CGPoint(x: x, y: y))
                area.move(to: CGPoint(x: x, y: plotBottom))
                area.addLine(to: CGPoint(x: x, y: y))
            } else {
                curve.addLine(to: CGPoint(x: x, y: y))
                area.addLine(to: CGPoint(x: x, y: y))
            }
        }
        area.addLine(to: CGPoint(x: xFor(lastMs), y: plotBottom))
        area.closeSubpath()

        clipped.fill(
            area,
            with: .linearGradient(
                Gradient(colors: [palette.primary.opacity(0.30), palette.primary.opacity(0)]),
                startPoint: CGPoint(x: 0, y: plotTop),
                endPoint: CGPoint(x: 0, y: plotBottom)))
        clipped.stroke(
            curve,
            with: .color(palette.primary),
            style: StrokeStyle(lineWidth: 2.4, lineCap: .round, lineJoin: .round))

        // Treatment changes: a dashed vertical to line up against the bend.
        let dashed = StrokeStyle(lineWidth: 1.5, dash: [4, 4])
        for at in treatmentChanges where at >= windowStart && at <= windowEnd {
            var line = Path()
            line.move(to: CGPoint(x: xFor(at), y: plotTop))
            line.addLine(to: CGPoint(x: xFor(at), y: plotBottom))
            clipped.stroke(line, with: .color(palette.secondary.opacity(0.8)), style: dashed)
        }

        // Doses ride the interpolated curve.
        for at in doseMarkers where at >= windowStart && at <= windowEnd {
            let center = CGPoint(x: xFor(at), y: yFor(MeasureChartMath.interpolate(points, at: at)))
            clipped.fill(circle(center: center, radius: 3.2), with: .color(palette.tertiary))
        }

        // Each reading gets a dot once zoomed in enough that they do not merge
        // into a bead chain — at full range a two-year weekly series would be a
        // solid stripe.
        if visible.count <= 40 {
            for point in visible {
                let center = CGPoint(x: xFor(point.atMs), y: yFor(point.value))
                clipped.fill(circle(center: center, radius: 3), with: .color(palette.primary))
            }
        }

        // The pinned reading: haloed, and dropped to the axis so the date under
        // it is unambiguous.
        if let selected {
            let x = xFor(selected.atMs)
            let y = yFor(selected.value)
            var line = Path()
            line.move(to: CGPoint(x: x, y: plotTop))
            line.addLine(to: CGPoint(x: x, y: plotBottom))
            clipped.stroke(line, with: .color(palette.primary.opacity(0.45)), lineWidth: 1)
            clipped.fill(
                circle(center: CGPoint(x: x, y: y), radius: 9),
                with: .color(palette.primary.opacity(0.22)))
            clipped.fill(
                circle(center: CGPoint(x: x, y: y), radius: 5), with: .color(palette.primary))
        }

        // -- X gradations, under the plot -------------------------------------
        var axis = Path()
        axis.move(to: CGPoint(x: plotLeft, y: plotBottom))
        axis.addLine(to: CGPoint(x: plotRight, y: plotBottom))
        context.stroke(axis, with: .color(palette.chartGrid), lineWidth: 1)

        // The visible window decides the cadence: "12 mars" is noise across four
        // years, "mars 24" is useless across three weeks.
        let visibleMs = Double(totalMs) * viewport.span
        var lastRight: CGFloat = -.greatestFiniteMagnitude
        for ms in MeasureChartMath.timeTicks(from: windowStart, to: windowEnd) {
            let x = xFor(ms)
            guard x >= plotLeft - 1, x <= plotRight + 1 else { continue }
            let label = visibleMs > Double(MeasureChartMath.yearMs)
                ? MeasureFormat.monthYear(ms)
                : MeasureFormat.dayMonth(ms)
            let resolved = context.resolve(
                Text(label).font(EggFont.labelS).foregroundStyle(palette.onSurfaceVariant))
            let width = resolved.measure(in: CGSize(width: 200, height: 40)).width
            let left = min(max(x - width / 2, plotLeft), plotRight - width)
            // Drop a label that would collide with the previous one rather than
            // overprinting: an unreadable date is worse than a missing gradation.
            guard left >= lastRight + 6 else { continue }
            lastRight = left + width

            var tickMark = Path()
            tickMark.move(to: CGPoint(x: x, y: plotBottom))
            tickMark.addLine(to: CGPoint(x: x, y: plotBottom + 3))
            context.stroke(tickMark, with: .color(palette.chartGrid), lineWidth: 1)
            context.draw(resolved, at: CGPoint(x: left, y: plotBottom + 6), anchor: .topLeading)
        }
    }

    private func circle(center: CGPoint, radius: CGFloat) -> Path {
        Path(ellipseIn: CGRect(
            x: center.x - radius, y: center.y - radius,
            width: radius * 2, height: radius * 2))
    }
}
