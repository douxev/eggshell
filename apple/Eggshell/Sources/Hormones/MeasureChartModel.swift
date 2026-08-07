import Foundation

// The geometry of the analyses curve, with no SwiftUI in it.
//
// The chart used to compute its scales inline in the `Canvas` draw closure,
// which is why it could not be reasoned about: the Y axis had no gradations to
// be wrong about, the X axis was two dates in an HStack under the plot, and
// "zoom" had nowhere to live because there was no notion of a visible window at
// all — every draw read `points[0]` and `points[count - 1]`.
//
// Splitting the maths out buys three things: the axis choices become nameable
// (see `niceTicks`), the viewport becomes a value that gestures transform
// rather than a pile of local floats, and both stay testable without a
// rendering pass. Mirrors `MeasureChartModel.kt` on Android deliberately — the
// two platforms should not disagree about where a dot goes.

/// Which slice of the time range is on screen, as fractions of the full range.
///
/// Held as `scale` + `start` rather than as two instants so a zoom survives the
/// arrival of a new reading: the window stays "the last tenth of history"
/// instead of pinning itself to dates the new range no longer bounds.
struct TimeViewport: Equatable {
    /// 1 = the whole range fits; 4 = a quarter of it is on screen.
    var scale: Double = 1
    /// Left edge, as a fraction of the full range.
    var start: Double = 0

    /// Past this the window holds less than a percent of the history, which on
    /// any realistic series is fewer than two readings — there is nothing left
    /// to resolve, and the curve becomes a line leaving the screen.
    static let maxScale: Double = 100

    var span: Double { 1 / scale }
    var end: Double { start + span }

    /// True when nothing is zoomed or panned — used to hide the reset button.
    var isIdentity: Bool { scale <= 1.0001 }

    /// Zoom by `factor`, keeping the data under `focus` (a 0…1 fraction of the
    /// plot's width) pinned to that same spot.
    ///
    /// Without the focal correction a pinch always zooms towards the left edge,
    /// which on a six-month curve means the gesture walks away from whatever
    /// the user put their fingers on.
    func zoomed(by factor: Double, focus: Double) -> TimeViewport {
        let newScale = min(max(scale * factor, 1), Self.maxScale)
        let anchored = start + focus * span
        return TimeViewport(scale: newScale, start: anchored - focus / newScale).clamped()
    }

    /// Pan by `delta` expressed as a fraction of the *visible* span.
    func panned(by delta: Double) -> TimeViewport {
        TimeViewport(scale: scale, start: start + delta * span).clamped()
    }

    /// Keep the window inside the range, and never narrower than the range.
    func clamped() -> TimeViewport {
        let s = min(max(scale, 1), Self.maxScale)
        let maxStart = max(1 - 1 / s, 0)
        return TimeViewport(scale: s, start: min(max(start, 0), maxStart))
    }
}

enum MeasureChartMath {
    /// Guards the `<=` on an accumulating float so the last tick isn't dropped.
    private static let epsilon = 1e-6

    /// Gradation values for an axis spanning `min…max`, at most `target` of them.
    ///
    /// Steps are the 1 / 2 / 5 × 10ⁿ ladder, so a gradation always lands on a
    /// number a reader can hold in their head. That is also what stops the Y
    /// axis jittering while a pan rescales it: the step only changes when the
    /// span crosses a decade, not on every frame.
    ///
    /// Returns a single tick when the series is flat — a zero-span axis has no
    /// gradations to give, and dividing by it is how the old code produced NaN
    /// coordinates for someone with two identical readings.
    static func niceTicks(min lo: Double, max hi: Double, target: Int = 4) -> [Double] {
        guard lo.isFinite, hi.isFinite else { return [] }
        let span = hi - lo
        guard span > 0, target >= 1 else { return [lo] }

        let rawStep = span / Double(target)
        let magnitude = pow(10, floor(log10(rawStep)))
        let normalized = rawStep / magnitude
        let step: Double
        switch normalized {
        case ...1: step = magnitude
        case ...2: step = magnitude * 2
        case ...2.5: step = magnitude * 2.5
        case ...5: step = magnitude * 5
        default: step = magnitude * 10
        }
        guard step > 0 else { return [lo] }

        var out: [Double] = []
        var tick = (lo / step).rounded(.up) * step
        var guardCount = 0
        while tick <= hi + step * epsilon, guardCount <= target * 4 {
            // Snap accumulated float error back onto the step so a tick reads
            // "40" rather than "39,999999999999996" once MeasureFormat quotes it.
            out.append(abs(tick) < step * epsilon ? 0 : tick)
            tick += step
            guardCount += 1
        }
        return out
    }

    /// The value range an axis should cover, padded so the curve does not graze
    /// the top and bottom edges of the plot.
    ///
    /// A flat series gets a synthetic span around its value: without one the
    /// curve would sit on the baseline with no indication that it is flat, and
    /// the axis would carry a single repeated gradation.
    static func valueRange(_ values: [Double], padFraction: Double = 0.08) -> (min: Double, max: Double) {
        let finite = values.filter(\.isFinite)
        guard let lo = finite.min(), let hi = finite.max() else { return (0, 1) }
        if lo == hi {
            let pad = lo == 0 ? 1 : abs(lo) * 0.1
            return (lo - pad, hi + pad)
        }
        let pad = (hi - lo) * padFraction
        return (lo - pad, hi + pad)
    }

    /// The reading nearest `fraction` (a 0…1 position across the *visible*
    /// window), or nil when the nearest one is further than `tolerance` away.
    ///
    /// Hit-testing on the time axis alone, not on 2-D distance to the drawn
    /// dot: the user is picking a date, and on a steep segment the dot they
    /// mean can sit a long way from their finger vertically.
    static func nearest(
        in points: [MeasurePoint],
        viewport: TimeViewport,
        fraction: Double,
        tolerance: Double = 0.06
    ) -> MeasurePoint? {
        guard let first = points.first?.atMs, let last = points.last?.atMs else { return nil }
        let range = Double(Swift.max(last - first, 1))
        let target = viewport.start + fraction * viewport.span
        var best: MeasurePoint?
        var bestDistance = Double.greatestFiniteMagnitude
        for p in points {
            let d = abs(Double(p.atMs - first) / range - target)
            if d < bestDistance {
                bestDistance = d
                best = p
            }
        }
        return bestDistance <= tolerance * viewport.span ? best : nil
    }

    /// The curve's value at `ms`, linearly interpolated between its readings.
    static func interpolate(_ points: [MeasurePoint], at ms: Int64) -> Double {
        guard let firstValue = points.first?.value, let lastValue = points.last?.value else { return 0 }
        guard let i = points.lastIndex(where: { $0.atMs <= ms }) else { return firstValue }
        guard i < points.count - 1 else { return lastValue }
        let a = points[i]
        let b = points[i + 1]
        guard b.atMs != a.atMs else { return a.value }
        let f = Double(ms - a.atMs) / Double(b.atMs - a.atMs)
        return a.value + (b.value - a.value) * f
    }

    private static let dayMs: Int64 = 86_400_000
    static let yearMs: Int64 = 365 * 86_400_000

    /// Day, 2 days, week, fortnight, month, quarter, half-year, year, 2 / 5 years.
    private static let timeSteps: [Int64] = [
        dayMs, 2 * dayMs, 7 * dayMs, 14 * dayMs, 30 * dayMs,
        91 * dayMs, 182 * dayMs, yearMs, 2 * yearMs, 5 * yearMs,
    ]

    /// Instants to gradate the time axis at, on calendar-friendly steps.
    ///
    /// Round *durations* would put gradations mid-afternoon on arbitrary days;
    /// the ladder above keeps them on day, week, month and year boundaries,
    /// which is how the dates under them are read.
    static func timeTicks(from: Int64, to: Int64, target: Int = 4) -> [Int64] {
        let span = Swift.max(to - from, 1)
        let step = timeSteps.first { span / $0 <= Int64(target) } ?? timeSteps[timeSteps.count - 1]
        var out: [Int64] = []
        var t = (from / step) * step
        var guardCount = 0
        while t <= to, guardCount <= target * 6 {
            if t >= from { out.append(t) }
            t += step
            guardCount += 1
        }
        return out
    }
}
