import ImageIO
import SwiftUI
import UIKit

// Everything the doctor's report is drawn *with*: geometry, ink, type styles and
// the formatting policy. Mirror of the Android `data/pdf/ReportStyle.kt`, so the
// two platforms produce the same page.
//
// Two rules govern this file.
//
//  1. **The prototype is 744 px wide, A4 is 595 pt.** Every length quoted in §7
//     of the handoff is a mock-up pixel, so it goes through `ReportGeo.px` before
//     it touches the page. The margins are the one exception — §7.1 states them
//     in points already. Hairlines are the other: 0.8 pt of ink survives a
//     photocopier, 0.64 pt does not, so rule weights are not rescaled either.
//  2. **The document is always the light palette**, whatever the app is wearing.
//     A report is photocopied, faxed and filed; it has one background, white.
//     The colours are still tokens — `Palette.lavenderLight` is the reference
//     light scheme, read here rather than transcribed.

/// Page geometry, in points.
enum ReportGeo {
    /// Mock-up pixels → PDF points.
    static func px(_ v: CGFloat) -> CGFloat { v * 595 / 744 }

    static let pageW: CGFloat = 595
    static let pageH: CGFloat = 842
    static let marginX: CGFloat = 42
    static let marginTop: CGFloat = 36
    static let marginBottom: CGFloat = 27

    static let contentL = marginX
    static let contentR = pageW - marginX
    static let contentW = contentR - contentL

    /// Banner: the line box top, then the 1.5 pt rule that closes it.
    static let bannerTop = marginTop + 1
    static let bannerRule = marginTop + 16

    /// The body never starts higher than this, nor runs past `bodyBottom`.
    static let bodyTop = bannerRule + 8
    static let footerRule = pageH - marginBottom - 24
    static let bodyBottom = footerRule
    static let footerTop = footerRule + px(14)

    /// Stat blocks and the chart beside them (§7.4.5, §7.5.2, §7.6.3).
    static let statW = px(186)
    static let statGap = px(30)
    static let chartW = contentW - statW - statGap

    /// Space above a section title, and below it before its first block.
    static let sectionTop = px(28)
    static let sectionTopFirst = px(26)
    static let sectionBottom = px(12)

    /// Anchors of the punctuality slot, already in points.
    static let punctH: CGFloat = 83.2
    static let punctGutter: CGFloat = 50.8
    static let punctTop: CGFloat = 10.4
    static let punctZero: CGFloat = 15.6
    static let punctMid: CGFloat = 36.4
    static let punctMax: CGFloat = 57.2
    static let punctSeparator: CGFloat = 65.0
    static let punctMissed: CGFloat = 72.8
    static let punctBottom: CGFloat = 78.0
    /// An hour-early intake must not be allowed to flatten the whole scale.
    static let earlyClampMin = 14

    /// Breathing room above a full-width chart, inside its own block.
    static let chartLead = px(6)
}

/// The document's ink. Section numbers and the main curve are the only things
/// allowed to be `primary`: one accent, everything else is grey (§7.1).
enum ReportInk {
    private static let s = Palette.lavenderLight
    static let page = UIColor(s.surfaceContainerLowest)
    static let onSurface = UIColor(s.onSurface)
    static let onSurfaceVariant = UIColor(s.onSurfaceVariant)
    static let outline = UIColor(s.outline)
    static let outlineVariant = UIColor(s.outlineVariant)
    static let primary = UIColor(s.primary)
    static let secondary = UIColor(s.secondary)
    static let tertiary = UIColor(s.tertiary)
    static let error = UIColor(s.error)
}

/// The type scale of §7.2, resolved to points.
enum ReportSizes {
    static let title = ReportGeo.px(27)
    static let subtitle = ReportGeo.px(15)
    static let micro = ReportGeo.px(12)
    static let cell = ReportGeo.px(14)
    static let big = ReportGeo.px(30)
    static let bigUnit = ReportGeo.px(16)
    static let bigConv = ReportGeo.px(14)
    static let note = ReportGeo.px(13)
    static let axis = ReportGeo.px(9)
    static let annotation = ReportGeo.px(8)
    static let identity = ReportGeo.px(16)
}

/// One text style: a font, an ink and a tracking. `tracking` is given in ems the
/// way the handoff states it and converted to the points `kern` wants.
struct ReportTextStyle {
    let font: UIFont
    let color: UIColor
    var tracking: CGFloat = 0

    init(size: CGFloat, weight: UIFont.Weight, color: UIColor, tracking: CGFloat = 0) {
        self.font = UIFont.systemFont(ofSize: size, weight: weight)
        self.color = color
        self.tracking = tracking
    }

    var attributes: [NSAttributedString.Key: Any] {
        var out: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: color]
        if tracking != 0 { out[.kern] = tracking * font.pointSize }
        return out
    }

    /// Height of one line box — the unit every vertical measurement uses.
    var lineHeight: CGFloat { font.lineHeight }

    func width(_ text: String) -> CGFloat {
        (text as NSString).size(withAttributes: attributes).width
    }
}

/// One stroke: colour, weight and an optional dash pattern.
struct ReportStroke {
    let color: UIColor
    let width: CGFloat
    var dash: [CGFloat]? = nil
    var rounded = false
}

/// The full set of styles a document is painted with (§7.2, §7.3).
struct ReportStyles {
    // -- text ---------------------------------------------------------------
    let title = ReportTextStyle(
        size: ReportSizes.title, weight: .semibold, color: ReportInk.onSurface, tracking: -0.0111)
    let subtitle = ReportTextStyle(
        size: ReportSizes.subtitle, weight: .regular, color: ReportInk.onSurfaceVariant)
    let bannerLeft = ReportTextStyle(
        size: ReportSizes.micro, weight: .bold, color: ReportInk.onSurface, tracking: 0.1)
    let bannerRight = ReportTextStyle(
        size: ReportSizes.micro, weight: .regular, color: ReportInk.onSurfaceVariant, tracking: 0.0417)
    let footer = ReportTextStyle(
        size: ReportSizes.micro, weight: .regular, color: ReportInk.onSurfaceVariant)
    let sectionTitle = ReportTextStyle(
        size: ReportSizes.micro, weight: .bold, color: ReportInk.primary, tracking: 0.1)
    let tableHead = ReportTextStyle(
        size: ReportSizes.micro, weight: .bold, color: ReportInk.onSurfaceVariant, tracking: 0.0583)
    let cell = ReportTextStyle(
        size: ReportSizes.cell, weight: .regular, color: ReportInk.onSurface)
    let cellStrong = ReportTextStyle(
        size: ReportSizes.cell, weight: .semibold, color: ReportInk.onSurface)
    let cellMuted = ReportTextStyle(
        size: ReportSizes.cell, weight: .regular, color: ReportInk.onSurfaceVariant)
    let caption = ReportTextStyle(
        size: ReportSizes.micro, weight: .bold, color: ReportInk.onSurfaceVariant, tracking: 0.0583)
    let identityLabel = ReportTextStyle(
        size: ReportSizes.micro, weight: .bold, color: ReportInk.onSurfaceVariant, tracking: 0.075)
    let identityValue = ReportTextStyle(
        size: ReportSizes.identity, weight: .semibold, color: ReportInk.onSurface)
    let big = ReportTextStyle(
        size: ReportSizes.big, weight: .semibold, color: ReportInk.onSurface, tracking: -0.0133)
    let bigUnit = ReportTextStyle(
        size: ReportSizes.bigUnit, weight: .regular, color: ReportInk.onSurfaceVariant)
    let bigConv = ReportTextStyle(
        size: ReportSizes.bigConv, weight: .regular, color: ReportInk.onSurfaceVariant)
    let note = ReportTextStyle(
        size: ReportSizes.note, weight: .regular, color: ReportInk.onSurfaceVariant)
    let noteLead = ReportTextStyle(
        size: ReportSizes.note, weight: .bold, color: ReportInk.onSurface)
    let bound = ReportTextStyle(
        size: ReportSizes.micro, weight: .regular, color: ReportInk.onSurfaceVariant)
    let annotation = ReportTextStyle(
        size: ReportSizes.annotation, weight: .regular, color: ReportInk.onSurfaceVariant)

    func axis(_ color: UIColor) -> ReportTextStyle {
        ReportTextStyle(size: ReportSizes.axis, weight: .regular, color: color)
    }

    // -- strokes ------------------------------------------------------------
    // §7.3's three weights, kept at their stated size so print survives.
    let ruleStrong = ReportStroke(color: ReportInk.onSurface, width: 1.5)
    let ruleTable = ReportStroke(color: ReportInk.onSurface, width: 1.2)
    let ruleHair = ReportStroke(color: ReportInk.outlineVariant, width: 1)
    let ruleBaseline = ReportStroke(color: ReportInk.onSurface, width: 1)
    let ruleGrid = ReportStroke(color: ReportInk.outlineVariant, width: 0.8)
    let ruleAxis = ReportStroke(color: ReportInk.outline, width: 0.8)
    let boxOutline = ReportStroke(color: ReportInk.outline, width: 1)
    let boxOutlineFaint = ReportStroke(color: ReportInk.outlineVariant, width: 1)
    let checkbox = ReportStroke(color: ReportInk.onSurface, width: 1.2)
    let zeroLine = ReportStroke(color: ReportInk.tertiary, width: 1, dash: [2.4, 2.4])
    let missedSeparator = ReportStroke(
        color: ReportInk.outlineVariant, width: 0.8, dash: [1.6, 2.4])
    let markerLine = ReportStroke(color: ReportInk.onSurfaceVariant, width: 1, dash: [1.6, 2.4])
    let seriesMain = ReportStroke(color: ReportInk.primary, width: 1.6, rounded: true)
    let seriesSecondary = ReportStroke(
        color: ReportInk.tertiary, width: 1.6, dash: [4, 2.4], rounded: true)
}

/// The document's number and date policy, in one place so the same value can
/// never print two ways. Rounding is *rounding*, never the silent truncation the
/// previous exporter did.
struct ReportFormats {
    private let locale: Locale
    private let zero: NumberFormatter
    private let one: NumberFormatter
    private let two: NumberFormatter

    init(locale: Locale = Locale(identifier: "fr_FR")) {
        self.locale = locale
        zero = ReportFormats.decimals(0, locale)
        one = ReportFormats.decimals(1, locale)
        two = ReportFormats.decimals(2, locale)
    }

    private static func decimals(_ max: Int, _ locale: Locale) -> NumberFormatter {
        let f = NumberFormatter()
        f.locale = locale
        f.numberStyle = .decimal
        f.usesGroupingSeparator = false
        f.maximumFractionDigits = max
        f.minimumFractionDigits = 0
        return f
    }

    /// Free-form value: at most two decimals, trailing zeros dropped.
    func number(_ v: Double) -> String { two.string(from: NSNumber(value: v)) ?? "\(v)" }

    /// Scores, weights, pitches: exactly the precision the reader can use.
    func score(_ v: Double) -> String { one.string(from: NSNumber(value: v)) ?? "\(v)" }

    func integer(_ v: Int) -> String { zero.string(from: NSNumber(value: v)) ?? "\(v)" }

    /// « +2,3 kg », « −38 Hz » — the sign is part of the reading.
    func signed(_ v: Double, _ unit: String, oneDecimal: Bool = true) -> String {
        let body = oneDecimal ? score(abs(v)) : integer(Int(abs(v).rounded()))
        let sign = v < 0 ? "−" : "+"
        return unit.isEmpty ? sign + body : sign + body + " " + unit
    }

    func value(_ v: Double, _ unit: String?) -> String {
        guard let unit, !unit.trimmingCharacters(in: .whitespaces).isEmpty else { return number(v) }
        return number(v) + " " + unit
    }

    private func formatter(_ pattern: String) -> DateFormatter {
        let f = DateFormatter()
        f.locale = locale
        f.dateFormat = pattern
        return f
    }

    private func date(_ atMs: Int64) -> Date { Date(timeIntervalSince1970: Double(atMs) / 1000) }

    func slashed(_ atMs: Int64) -> String { formatter("dd/MM/yyyy").string(from: date(atMs)) }
    func prose(_ atMs: Int64) -> String { formatter("d MMMM yyyy").string(from: date(atMs)) }
    func proseNoYear(_ atMs: Int64) -> String { formatter("d MMMM").string(from: date(atMs)) }
    func monthShort(_ atMs: Int64) -> String { formatter("MMM yyyy").string(from: date(atMs)) }
    func monthLong(_ atMs: Int64) -> String { formatter("MMMM yyyy").string(from: date(atMs)) }
    func dayMonth(_ atMs: Int64) -> String { formatter("dd/MM").string(from: date(atMs)) }

    /// ISO, for the file name only — never a locale-shaped date on disk.
    func iso(_ atMs: Int64) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd"
        return f.string(from: date(atMs))
    }

    func capitalise(_ s: String) -> String {
        guard let first = s.first else { return s }
        return String(first).uppercased(with: locale) + String(s.dropFirst())
    }
}

/// Decodes a photo down to what the page needs, never to full resolution: a
/// contact sheet of eight originals is a hundred megabytes for nothing.
func reportThumbnail(_ bytes: Data, maxPixel: CGFloat) -> UIImage? {
    guard let source = CGImageSourceCreateWithData(bytes as CFData, nil) else { return nil }
    let options: [CFString: Any] = [
        kCGImageSourceCreateThumbnailFromImageAlways: true,
        kCGImageSourceCreateThumbnailWithTransform: true,
        kCGImageSourceThumbnailMaxPixelSize: Int(max(1, maxPixel)),
    ]
    guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary)
    else { return nil }
    return UIImage(cgImage: image)
}
