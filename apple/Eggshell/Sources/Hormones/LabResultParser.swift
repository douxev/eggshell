import Foundation

// ===========================================================================
// Swift port of the Android `LabResultParser.kt`.
//
// Heuristic parser that extracts hormone values from the raw OCR text of a
// lab-result document.
//
// Design constraints (kept identical to Android):
//  - Only emits hormones the rest of the app already supports
//    (HormoneCatalog.kinds), so an import never produces a measurement the
//    user couldn't have typed manually.
//  - Ignores reference-range mentions, footnotes and units that don't match
//    the canonical clinical set.
//  - Returns an empty list rather than guessing when the layout is too
//    free-form; the user can still type the value by hand.
//
// Pipeline:
//   1. Walk the OCR text line-by-line.
//   2. For each line, check whether any known hormone alias appears.
//   3. If so, look for the first `value + unit` pair on the same line; if
//      missing, peek at the next 1–2 lines (multi-line lab layouts).
//   4. Dedupe by hormone kind (only keep the first sighting — typically the
//      patient's actual value, before any "ref: X–Y" range).
// ===========================================================================

/// One parsed measurement. `atMs` is the document-level draw date when one
/// could be detected; nil otherwise (callers default to "now").
struct ParsedMeasurement {
    let hormone: String
    let value: Double
    let unit: String
    let atMs: Int64?
}

enum LabResultParser {

    // MARK: - Hormone aliases

    /// Maps a HormoneCatalog kind id to the regex patterns we look for to
    /// recognise that hormone in OCR text. Case-insensitive. Order matters:
    /// matchHormone returns the first kind whose any pattern hits.
    private static let hormonePatterns: [(kind: String, patterns: [NSRegularExpression])] = {
        let raw: [(String, [String])] = [
            ("estradiol", [
                "œstradiol", "oestradiol", "estradiol", "estradiol\\s*\\(e2\\)", "\\bE2\\b",
            ]),
            ("testosterone", [
                "testostérone\\s*totale", "testosterone\\s*total", "testostérone", "testosterone",
            ]),
            ("progesterone", ["progestérone", "progesterone"]),
            ("lh", [
                "\\bLH\\b", "hormone\\s*lut[ée]inisante", "luteinizing\\s*hormone",
            ]),
            ("fsh", [
                "\\bFSH\\b", "hormone\\s*folliculo[\\- ]?stimulante", "follicle[\\- ]?stimulating\\s*hormone",
            ]),
            ("prolactin", ["prolactine", "prolactin"]),
            ("shbg", ["\\bSHBG\\b", "sex\\s*hormone\\s*binding\\s*globulin"]),
        ]
        return raw.map { kind, pats in
            (kind, pats.compactMap { compile($0) })
        }
    }()

    /// A number (decimal point or comma) followed by an allowed unit token.
    private static let valueUnitRegex: NSRegularExpression? = compile(
        "(\\d{1,5}[.,]?\\d{0,3})\\s*" +
        "(pg/mL|pg/ml|pmol/L|pmol/l|ng/dL|ng/dl|nmol/L|nmol/l|ng/mL|ng/ml|" +
        "mIU/mL|mIU/ml|miu/ml|µIU/mL|µIU/ml|uIU/mL|uIU/ml|UI/L|ui/l|IU/L|iu/l)"
    )

    /// Numeric dates: 27/05/2026, 27-05-2026, 27.05.2026, 2026-05-27, etc.
    /// Both 2- and 4-digit years; both DMY and YMD orderings.
    private static let numericDateRegex: NSRegularExpression? = compile(
        "(?<!\\d)(\\d{1,4})([/.\\-])(\\d{1,2})\\2(\\d{1,4})(?!\\d)"
    )

    /// Textual dates: "27 mai 2026", "27 May 2026", "27 mai 26".
    private static let textDateRegex: NSRegularExpression? = compile(
        "(\\d{1,2})\\s+([a-zéûôA-ZÉÛÔ]+)\\.?\\s+(\\d{2,4})"
    )

    private static let textMonthsFr: [String: Int] = [
        "janvier": 0, "février": 1, "fevrier": 1, "mars": 2, "avril": 3,
        "mai": 4, "juin": 5, "juillet": 6, "août": 7, "aout": 7,
        "septembre": 8, "octobre": 9, "novembre": 10, "décembre": 11, "decembre": 11,
    ]
    private static let textMonthsEn: [String: Int] = [
        "january": 0, "february": 1, "march": 2, "april": 3, "may": 4,
        "june": 5, "july": 6, "august": 7, "september": 8, "october": 9,
        "november": 10, "december": 11,
        "jan": 0, "feb": 1, "mar": 2, "apr": 3, "jun": 5, "jul": 6,
        "aug": 7, "sep": 8, "sept": 8, "oct": 9, "nov": 10, "dec": 11,
    ]
    private static let allMonths: [String: Int] = textMonthsFr.merging(textMonthsEn) { a, _ in a }

    // MARK: - Public API

    static func parse(_ text: String) -> [ParsedMeasurement] {
        let lines = text
            .split(separator: "\n", omittingEmptySubsequences: false)
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
        if lines.isEmpty { return [] }

        let date = extractDate(text)
        // Dedup by hormone, preserve insertion order.
        var order: [String] = []
        var byHormone: [String: ParsedMeasurement] = [:]

        for (i, line) in lines.enumerated() {
            guard let hormone = matchHormone(line) else { continue }
            if byHormone[hormone] != nil { continue } // already captured earlier

            // Try same line first, then the next two lines for multi-line layouts.
            var candidate = matchValue(line)
            if candidate == nil, i + 1 < lines.count { candidate = matchValue(lines[i + 1]) }
            if candidate == nil, i + 2 < lines.count { candidate = matchValue(lines[i + 2]) }
            if let (value, unit) = candidate {
                order.append(hormone)
                byHormone[hormone] = ParsedMeasurement(hormone: hormone, value: value, unit: unit, atMs: date)
            }
        }
        return order.compactMap { byHormone[$0] }
    }

    // MARK: - Date extraction

    /// Scans the full text for the most plausible "lab draw date". Picks the
    /// first date within a reasonable window (≤ 3 years ago, ≤ 1 day future)
    /// on the theory that lab reports put the collection date near the top.
    static func extractDate(_ text: String, now: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) -> Int64? {
        let threeYearsMs: Int64 = 3 * 365 * 86_400_000
        func acceptable(_ ms: Int64) -> Bool { ms >= (now - threeYearsMs) && ms <= (now + 86_400_000) }

        let ns = text as NSString
        let full = NSRange(location: 0, length: ns.length)

        // Numeric dates first (most common on lab reports).
        if let rx = numericDateRegex {
            for m in rx.matches(in: text, options: [], range: full) {
                guard let a = intGroup(m, 1, ns),
                      let b = intGroup(m, 3, ns),
                      let c = intGroup(m, 4, ns) else { continue }
                if let ms = interpretNumericDate(a, b, c), acceptable(ms) { return ms }
            }
        }

        // Textual months (FR + EN).
        if let rx = textDateRegex {
            for m in rx.matches(in: text, options: [], range: full) {
                guard let day = intGroup(m, 1, ns) else { continue }
                let monthName = ns.substring(with: m.range(at: 2)).lowercased()
                guard let month = allMonths[monthName] else { continue }
                guard let yearRaw = intGroup(m, 3, ns) else { continue }
                let year = yearRaw < 100 ? 2000 + yearRaw : yearRaw
                if let ms = makeMs(year: year, month: month, day: day), acceptable(ms) { return ms }
            }
        }
        return nil
    }

    private static func interpretNumericDate(_ a: Int, _ b: Int, _ c: Int) -> Int64? {
        // Heuristics: if the first group is 4 digits → YMD; otherwise → DMY.
        let year: Int
        let month: Int
        let day: Int
        if a >= 1000 && a <= 9999 {
            year = a; month = b - 1; day = c             // 2026-05-27
        } else if c >= 1000 && c <= 9999 {
            year = c; month = b - 1; day = a             // 27/05/2026
        } else {
            year = c < 100 ? 2000 + c : c                // two-digit year
            month = b - 1; day = a
        }
        if month < 0 || month > 11 || day < 1 || day > 31 { return nil }
        return makeMs(year: year, month: month, day: day)
    }

    private static func makeMs(year: Int, month: Int, day: Int) -> Int64? {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone.current
        var comps = DateComponents()
        comps.year = year
        comps.month = month + 1   // DateComponents months are 1-based
        comps.day = day
        comps.hour = 12
        comps.minute = 0
        comps.second = 0
        guard let date = cal.date(from: comps) else { return nil }
        return Int64(date.timeIntervalSince1970 * 1000)
    }

    // MARK: - Matching helpers

    private static func matchHormone(_ line: String) -> String? {
        let ns = line as NSString
        let range = NSRange(location: 0, length: ns.length)
        for (kind, patterns) in hormonePatterns {
            for rx in patterns {
                if rx.firstMatch(in: line, options: [], range: range) != nil { return kind }
            }
        }
        return nil
    }

    private static func matchValue(_ line: String) -> (Double, String)? {
        guard let rx = valueUnitRegex else { return nil }
        let ns = line as NSString
        let range = NSRange(location: 0, length: ns.length)
        guard let m = rx.firstMatch(in: line, options: [], range: range) else { return nil }
        let rawNum = ns.substring(with: m.range(at: 1)).replacingOccurrences(of: ",", with: ".")
        guard let value = Double(rawNum) else { return nil }
        let unit = normalizeUnit(ns.substring(with: m.range(at: 2)))
        return (value, unit)
    }

    private static func normalizeUnit(_ raw: String) -> String {
        switch raw.lowercased() {
        case "pg/ml":  return "pg/mL"
        case "pmol/l": return "pmol/L"
        case "ng/dl":  return "ng/dL"
        case "nmol/l": return "nmol/L"
        case "ng/ml":  return "ng/mL"
        case "miu/ml": return "mIU/mL"
        // µIU/mL and IU/L are equivalent to mIU/mL for LH/FSH/prolactin in
        // clinical practice — normalise so the catalog only has one bucket.
        case "µiu/ml", "uiu/ml", "iu/l", "ui/l": return "mIU/mL"
        default: return raw
        }
    }

    // MARK: - Regex utilities

    private static func compile(_ pattern: String) -> NSRegularExpression? {
        try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive])
    }

    private static func intGroup(_ m: NSTextCheckingResult, _ idx: Int, _ ns: NSString) -> Int? {
        let r = m.range(at: idx)
        guard r.location != NSNotFound else { return nil }
        return Int(ns.substring(with: r))
    }
}
