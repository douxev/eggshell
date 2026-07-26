import CoreGraphics
import Foundation

// The doctor's report, as a value.
//
// Nothing here knows about a graphics context: the model is assembled from the
// vault by `DoctorReportBuilder` and painted by `DoctorReportRenderer`. That
// seam is what lets the document's *content* be reasoned about — which sections
// survived, what each figure says — without a device, and it is the only way
// the Android and the iOS renderers can be held to the same document.
//
// Sections carry no number. They are numbered at paint time, after the empty
// ones have been dropped, so the numbering always reads 1..n with no gap (§7.7).

struct ReportDocument {
    /// « Période du 26 avril au 26 juillet 2026 ».
    let title: String
    /// Day count · edition date · provenance of the figures.
    let subtitle: String
    /// Printed only when the app actually holds these two fields. It never does
    /// today (D3): writing a name and a date of birth in clear next to a decoy
    /// mode would be a step backwards, so the branch exists and stays unused.
    let identity: ReportIdentity?
    let sections: [ReportSection]
    let disclaimerLead: String
    let disclaimerBody: String
    /// `suivi-AAAA-MM-JJ_AAAA-MM-JJ.pdf` — no person's name, never the word
    /// « transition »: a filename shows up in a share sheet.
    let fileName: String
}

struct ReportIdentity {
    let name: String?
    let birthDate: String?

    var isEmpty: Bool {
        (name?.isEmpty ?? true) && (birthDate?.isEmpty ?? true)
    }
}

/// A numbered section. Dropped upstream when it has nothing to say.
struct ReportSection {
    let title: String
    let blocks: [ReportBlock]
}

struct ReportColumn {
    let title: String
    let weight: CGFloat
    var alignRight = false
    /// The identification column, and the analyte values, are weight 600.
    var strong = false
    var muted = false
}

struct ReportStat {
    let label: String
    let value: String
}

struct ReportHeadValue {
    let caption: String
    let value: String
    let unit: String
    /// « · 470 pmol/L » — omitted when there is nothing to convert to.
    let conversion: String?
}

struct ReportLegendItem {
    let label: String
    let dashed: Bool
    let secondary: Bool
}

struct ReportPhotoTile {
    let date: String
    let bytes: Data
}

enum ReportBlock {
    /// A framing sentence at cell size, or a grey note at note size.
    case paragraph(text: String, note: Bool)
    /// An all-caps over-title above a chart or a values block.
    case caption(String)
    case table(columns: [ReportColumn], rows: [[String]], rowPad: CGFloat)
    /// §2's two-part list: a fixed date column and a flowing sentence.
    case datedList([(date: String, text: String)])
    /// The stat block, alone or beside a chart.
    case statChart(ReportStatChart)
    /// The two big numbers under « TAUX HORMONAUX ».
    case headValues(left: ReportHeadValue, right: ReportHeadValue?)
    /// A full-width chart with the legend row §7.5 gives it.
    case wideChart(chart: ReportChartSpec, legend: [ReportLegendItem], legendTail: String?)
    /// « Fatigue 21 j » — the count carries the weight.
    case chips([(label: String, count: String)])
    /// Empty squares the doctor ticks with a pen.
    case checklist([String])
    case photos([ReportPhotoTile])
}

struct ReportStatChart {
    let stats: [ReportStat]
    let caption: String?
    let chart: ReportChartSpec?
    let punctuality: ReportPunctualitySpec?
    let note: String?
    /// §5 centres the block on its chart; §3 and §7 top-align them.
    var centred = false
}

struct ReportTimedValue {
    let atMs: Int64
    let value: Double
}

struct ReportChartSeries {
    let points: [ReportTimedValue]
    let dashed: Bool
    /// A dot per sample; the last one is drawn slightly larger.
    let dots: Bool
    let secondary: Bool
    /// §5.1 asks every main curve to close on a filled, slightly larger point —
    /// it is what tells the reader where the series stops rather than where the
    /// plot does. `dots` already ends that way; this is for a bare line, and it
    /// stays off for the dashed secondary series, which has no terminal point of
    /// its own in §7.5.
    var terminalDot = false
}

/// A dashed vertical at a treatment change, labelled « ↑ dose 18/05 ».
struct ReportChartMarker {
    let atMs: Int64
    let label: String
}

/// One chart slot, in points, measured from the top-left of the slot. Fixed
/// heights are the point of the exercise: the layout engine reserves the block
/// whole and breaks the page *before* it rather than clipping it.
struct ReportChartSpec {
    let width: CGFloat
    let height: CGFloat
    /// Reserved on the left for the Y labels; 0 when the chart has none.
    var gutter: CGFloat = 0
    var inset: CGFloat = 0
    let plotTop: CGFloat
    let baseline: CGFloat
    var gridlines: [CGFloat] = []
    /// One label per gridline, top to bottom. Empty for an unlabelled chart.
    var yTickLabels: [String] = []
    let fromMs: Int64
    let toMs: Int64
    let yMin: Double
    let yMax: Double
    let series: [ReportChartSeries]
    var markers: [ReportChartMarker] = []
    /// « janv. 2026 » / « juil. 2026 » under the plot.
    var bounds: (left: String, right: String)?
}

/// The punctuality scatter of §3. It is not a line chart: the Y axis is an
/// offset in minutes, the band under the dashed separator holds the doses that
/// were never logged, and each dot is coloured by its band.
struct ReportPunctualitySpec {
    let width: CGFloat
    let points: [DosePoint]
    let axis: PunctualityAxis
    let fromMs: Int64
    let toMs: Int64
    /// Three gradations, top to bottom: on time, half the max, the max.
    let tickLabels: [String]
    let missedLabel: String
}
