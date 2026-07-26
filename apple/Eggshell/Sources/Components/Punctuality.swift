import Foundation

// Punctuality of medication intakes — the measure the refonte adds on top of
// plain "taken / missed". Straight port of the Android
// `punctuality/Punctuality.kt` so the phone, the chart and the doctor report
// can never disagree on what "à l'heure" means.
//
// The offset is never stored: it is derived from the planned time
// (`DoseEvent.scheduledAtMs`) and the real one (`DoseEvent.takenAtMs`).

/// One intake on the punctuality axis. `deltaMin == nil` means "missed".
struct DosePoint: Hashable {
    /// When the intake happened — the X axis is proportional to this.
    let atMs: Int64
    /// Offset from the prescribed time, in minutes. Negative = early.
    let deltaMin: Int?

    init(atMs: Int64, deltaMin: Int?) {
        self.atMs = atMs
        self.deltaMin = deltaMin
    }
}

/// How a delay is spoken. `Punctuality.text(_:)` turns it into French.
enum DeltaLabel: Hashable {
    case onTime
    case missed
    /// Early by `minutes` (always > 0).
    case early(minutes: Int)
    case minutes(Int)
    case hours(Int)
    case hoursMinutes(hours: Int, minutes: Int)
}

/// Which of the three punctuality states an intake is in.
enum DoseTiming: Hashable {
    case onTime, late, missed
}

/// The Y axis of the punctuality chart. `y = 0` sits at the top and the scale
/// clamps to the largest delay **of the period** — never to a constant, so a
/// well-behaved month doesn't look like a bad one.
struct PunctualityAxis: Hashable {
    /// Largest delay in the period, in minutes. Always ≥ `Punctuality.minSpanMin`.
    let maxDelayMin: Int
    /// Largest *early* offset in the period, in minutes (0 when none).
    let maxEarlyMin: Int
    /// How many intakes were missed — the band under the dashed separator.
    let missedCount: Int

    /// The three gradations: on time, half of the max, the max.
    var ticks: [Int] { [0, maxDelayMin / 2, maxDelayMin] }
}

/// Headline figures of the « Régularité » card and of §3 of the report.
struct PunctualityStats: Hashable {
    let plannedCount: Int
    let loggedCount: Int
    let missedCount: Int
    /// Logged over planned, 0…100. 0 when nothing was planned.
    let adherencePercent: Int
    /// Mean offset over the logged intakes that carry one, in minutes.
    let meanDelayMin: Int
}

enum Punctuality {
    /// The one tolerance of the app: inside ±15 min an intake is on time.
    /// Reused by the chart, the history pills and the observance figure so the
    /// three can never tell three different stories.
    static let onTimeToleranceMin = 15

    /// Below this, the axis would magnify noise into a wall of late doses.
    static let minSpanMin = 30

    static func axis(_ points: [DosePoint]) -> PunctualityAxis {
        let deltas = points.compactMap(\.deltaMin)
        let maxDelay = deltas.filter { $0 > 0 }.max() ?? 0
        let maxEarly = deltas.filter { $0 < 0 }.min().map { abs($0) } ?? 0
        return PunctualityAxis(
            maxDelayMin: niceMax(max(maxDelay, minSpanMin)),
            maxEarlyMin: maxEarly,
            missedCount: points.filter { $0.deltaMin == nil }.count)
    }

    /// Rounds the top of the axis up so that the max **and its half** both land
    /// on speakable values — otherwise a 30-minute span would put a "+20 min"
    /// label on the 15-minute line and the reader would misjudge every point on
    /// the chart.
    ///
    /// The quantum is twice the rounding step of `axisLabel`, which is what
    /// makes the middle gradation exact too: 10-minute steps under the hour,
    /// 30-minute steps up to three hours, whole hours beyond.
    private static func niceMax(_ minutes: Int) -> Int {
        let quantum: Int
        if minutes <= 60 {
            quantum = 20
        } else if minutes <= 180 {
            quantum = 60
        } else {
            quantum = 120
        }
        return ((minutes + quantum - 1) / quantum) * quantum
    }

    /// Axis gradation label: rounded to the hour above an hour, to the ten
    /// minutes below — `+1 h`, `+2 h`, `+40 min`.
    static func axisLabel(_ minutes: Int) -> DeltaLabel {
        if minutes == 0 { return .onTime }
        if minutes < 60 {
            return .minutes(max(10, Int((Double(minutes) / 10).rounded()) * 10))
        }
        // Round the remainder, never the hour: a gradation sitting at 90
        // minutes must not be labelled "+2 h", or every point on the chart is
        // misread by half an hour.
        let hours = minutes / 60
        let rest = Int((Double(minutes % 60) / 10).rounded()) * 10
        if rest == 0 { return .hours(hours) }
        if rest >= 60 { return .hours(hours + 1) }
        return .hoursMinutes(hours: hours, minutes: rest)
    }

    /// Exact label of one intake, as shown on its history pill — `à l'heure`,
    /// `+1 h 47`, `manquée`.
    static func exactLabel(
        _ deltaMin: Int?,
        onTimeToleranceMin: Int = Punctuality.onTimeToleranceMin
    ) -> DeltaLabel {
        guard let deltaMin else { return .missed }
        if abs(deltaMin) <= onTimeToleranceMin { return .onTime }
        if deltaMin < 0 { return .early(minutes: abs(deltaMin)) }
        if deltaMin >= 60 { return .hoursMinutes(hours: deltaMin / 60, minutes: deltaMin % 60) }
        return .minutes(deltaMin)
    }

    static func timing(
        _ deltaMin: Int?,
        onTimeToleranceMin: Int = Punctuality.onTimeToleranceMin
    ) -> DoseTiming {
        guard let deltaMin else { return .missed }
        return deltaMin > onTimeToleranceMin ? .late : .onTime
    }

    static func stats(plannedCount: Int, points: [DosePoint]) -> PunctualityStats {
        let logged = points.filter { $0.deltaMin != nil }.count
        let missed = points.filter { $0.deltaMin == nil }.count
        let deltas = points.compactMap(\.deltaMin)
        let adherence: Int
        if plannedCount <= 0 {
            adherence = 0
        } else {
            let raw = Int(((Double(logged) / Double(plannedCount)) * 100).rounded())
            adherence = min(100, max(0, raw))
        }
        let mean = deltas.isEmpty
            ? 0
            : Int((Double(deltas.reduce(0, +)) / Double(deltas.count)).rounded())
        return PunctualityStats(
            plannedCount: plannedCount,
            loggedCount: logged,
            missedCount: missed,
            adherencePercent: adherence,
            meanDelayMin: mean)
    }

    // MARK: - French copy
    // iOS has no localization layer (67 files, 210 inline French literals);
    // these stay hardcoded like everything else.

    static func text(_ label: DeltaLabel) -> String {
        switch label {
        case .onTime:                     return "à l'heure"
        case .missed:                     return "manquée"
        case .early(let m):               return "\(m) min en avance"
        case .minutes(let m):             return "+\(m) min"
        case .hours(let h):               return "+\(h) h"
        case .hoursMinutes(let h, let m): return m == 0 ? "+\(h) h" : "+\(h) h \(m)"
        }
    }

    /// The axis label of the band under the dashed separator.
    static func missedAxisText(_ count: Int) -> String { "oubliées · \(count)" }
}
