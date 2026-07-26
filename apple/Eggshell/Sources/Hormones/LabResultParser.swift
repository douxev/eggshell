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
    /// The exact substring the number was read from, kept verbatim so the
    /// preview can quote what the document actually said.
    let raw: String
    /// True when the raw token mixed letters into the number and we had to
    /// repair it to parse. The value is a guess: the preview shows `raw` in
    /// `error` and starts with the row switched off.
    let doubtful: Bool

    init(
        hormone: String,
        value: Double,
        unit: String,
        atMs: Int64?,
        raw: String = "",
        doubtful: Bool = false
    ) {
        self.hormone = hormone
        self.value = value
        self.unit = unit
        self.atMs = atMs
        self.raw = raw
        self.doubtful = doubtful
    }
}

enum LabResultParser {

    /// Everything one OCR pass managed to pull out of a document.
    struct ParseResult {
        let values: [ParsedMeasurement]
        /// Best-guess date of the draw. Nil when none was recognised; callers
        /// default to "now" and say so.
        let dateMs: Int64?
        /// Laboratory named on the letterhead, when we recognise one. Feeds the
        /// reading's provenance so the doctor report can tell an import from a
        /// value typed in by hand. Nil when nothing was recognised.
        let labName: String?
    }

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
            // NFS values, tracked alongside hormone draws for HRT/testo follow-up.
            // The negative lookaheads keep "Hémoglobine glyquée" (HbA1c) out —
            // it's a different analyte, in %, that would otherwise shadow Hb.
            ("hemoglobin", [
                "h[ée]moglobine(?!\\s*(?:glyqu|a1c))", "hemoglobin(?!\\s*a1c)", "\\bHGB\\b", "\\bHb\\b(?!\\s*A1c)",
            ]),
            ("hematocrit", ["h[ée]matocrite", "hematocrit", "\\bHCT\\b", "\\bHte\\b"]),
            // Pseudo-kind: expanded into bp_systolic + bp_diastolic by parse().
            // (?<!hyper) keeps "suivi pour hypertension artérielle" (a history
            // note, no reading) from triggering the BP branch.
            (bpKind, [
                "(?<!hyper)tension\\s*art[ée]rielle", "pression\\s*art[ée]rielle", "blood\\s*pressure",
            ]),
        ]
        return raw.map { kind, pats in
            (kind, pats.compactMap { compile($0) })
        }
    }()

    /// Pseudo-kind for the paired blood-pressure reading.
    private static let bpKind = "bp"

    /// The unit tokens a reading may legitimately carry, as a regex alternation.
    private static let unitAlternation =
        "(pg/mL|pg/ml|pmol/L|pmol/l|ng/dL|ng/dl|nmol/L|nmol/l|ng/mL|ng/ml|" +
        "mIU/mL|mIU/ml|miu/ml|µIU/mL|µIU/ml|uIU/mL|uIU/ml|UI/L|ui/l|IU/L|iu/l|" +
        "g/dL|g/dl|g/100\\s*mL|g/100\\s*ml|%)"

    /// A number (decimal point or comma) followed by an allowed unit token.
    ///
    /// The lookbehind refuses a number that begins in the middle of a possibly
    /// misread one. Without it « 1O,2 ng/mL » yields a perfectly clean-looking
    /// 2 and « I2,5 mIU/mL » a clean 2,5 — a fabricated value, silently stored,
    /// in a document a doctor will read. Refusing them here sends the line to
    /// the doubtful pass below, where the user gets to see the raw string.
    private static let valueUnitRegex: NSRegularExpression? = compile(
        "(?<![\\d.,OoIiLlSsB])(\\d{1,5}[.,]?\\d{0,3})\\s*" + unitAlternation
    )

    /// Same shape, but tolerant of the glyphs OCR keeps confusing with digits
    /// (O→0, l/I→1, S→5, B→8). Only tried once the strict pass has come up
    /// empty, and anything it finds is flagged doubtful.
    ///
    /// The leading lookbehind refuses a token glued to a word, which is what
    /// stops the tail of « Œstradiol » from being read as a number.
    private static let sloppyValueUnitRegex: NSRegularExpression? = compile(
        "(?<![\\p{L}\\d.,])([\\dOoIiLlSsB]{1,5}[.,]?[\\dOoIiLlSsB]{0,3})\\s*" + unitAlternation
    )

    /// "128 / 82", "12,8/8,2 cmHg" — the systolic/diastolic pair. The
    /// lookarounds refuse a third "/NNNN" segment and a leading "NN/" so a
    /// date (27/05/2026) can never be consumed as a reading; `matchBp` then
    /// applies plausibility windows and unit preference on top.
    private static let bpPairRegex: NSRegularExpression? = compile(
        "(?<![\\d/.,])(\\d{1,3}(?:[.,]\\d)?)\\s*/\\s*(\\d{1,3}(?:[.,]\\d)?)(?!\\s*/\\s*\\d)\\s*(mm\\s*Hg|mmHg|cm\\s*Hg|cmHg)?"
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

    static func parse(_ text: String) -> ParseResult {
        let lines = text
            .split(separator: "\n", omittingEmptySubsequences: false)
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
        if lines.isEmpty { return ParseResult(values: [], dateMs: nil, labName: nil) }

        let date = extractDate(text)
        // Dedup by hormone, preserve insertion order.
        var order: [String] = []
        var byHormone: [String: ParsedMeasurement] = [:]

        for (i, line) in lines.enumerated() {
            guard let hormone = matchHormone(line) else { continue }

            if hormone == bpKind {
                // Blood pressure is a paired reading — expand to two rows so
                // each side charts independently.
                if byHormone["bp_systolic"] != nil { continue }
                var pair = matchBp(line)
                if pair == nil, i + 1 < lines.count { pair = matchBp(lines[i + 1]) }
                if pair == nil, i + 2 < lines.count { pair = matchBp(lines[i + 2]) }
                if let (sys, dia) = pair {
                    order.append("bp_systolic")
                    byHormone["bp_systolic"] = ParsedMeasurement(
                        hormone: "bp_systolic", value: sys, unit: "mmHg", atMs: date,
                        raw: trimNumber(sys))
                    order.append("bp_diastolic")
                    byHormone["bp_diastolic"] = ParsedMeasurement(
                        hormone: "bp_diastolic", value: dia, unit: "mmHg", atMs: date,
                        raw: trimNumber(dia))
                }
                continue
            }

            if byHormone[hormone] != nil { continue } // already captured earlier

            // Try same line first, then the next two lines for multi-line layouts.
            var candidate = matchValue(line, kind: hormone)
            if candidate == nil, i + 1 < lines.count { candidate = matchValue(lines[i + 1], kind: hormone) }
            if candidate == nil, i + 2 < lines.count { candidate = matchValue(lines[i + 2], kind: hormone) }
            if let hit = candidate {
                order.append(hormone)
                byHormone[hormone] = ParsedMeasurement(
                    hormone: hormone,
                    value: hit.value,
                    unit: hit.unit,
                    atMs: date,
                    raw: hit.raw,
                    doubtful: hit.doubtful)
            }
        }
        return ParseResult(
            values: order.compactMap { byHormone[$0] },
            dateMs: date,
            labName: detectLabName(lines))
    }

    // MARK: - Laboratory on the letterhead

    /// Lab chains common enough on French reports to be worth naming exactly.
    /// Anything else falls back to the letterhead's « Laboratoire … » line.
    private static let labChains = [
        "Biogroup", "Cerballiance", "Synlab", "Eurofins", "Unilabs", "Bioclinic",
        "Labazur", "Inovie", "Dyomedea", "Biopath", "Oriade", "Novescia",
        "Labosud", "Laborizon", "Bioesterel", "Biomnis", "Labcorp",
        "Quest Diagnostics",
    ]

    /// « Laboratoire de biologie médicale Saint-Roch », « LABORATOIRE DUPONT »…
    private static let labLineRegex: NSRegularExpression? = compile(
        "(laboratoire(?:\\s+de\\s+biologie(?:\\s+m[ée]dicale)?)?[^,;|(]{0,40})"
    )

    /// Reads the laboratory off the letterhead. Only the top of the document is
    /// scanned: further down, « laboratoire » shows up in footnotes and in
    /// reference-range disclaimers, which name nothing useful.
    static func detectLabName(_ lines: [String]) -> String? {
        let head = lines.prefix(20)
        for line in head {
            if let chain = labChains.first(where: {
                line.range(of: $0, options: .caseInsensitive) != nil
            }) {
                return chain
            }
        }
        guard let rx = labLineRegex else { return nil }
        for line in head {
            let ns = line as NSString
            let range = NSRange(location: 0, length: ns.length)
            guard let m = rx.firstMatch(in: line, options: [], range: range) else { continue }
            let hit = ns.substring(with: m.range(at: 1))
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .trimmingCharacters(in: CharacterSet(charactersIn: "-–:."))
                .trimmingCharacters(in: .whitespacesAndNewlines)
            // A bare "Laboratoire" with nothing behind it names nobody.
            if hit.count > 14 { return String(hit.prefix(48)) }
        }
        return nil
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

    /// Units each analyte may legitimately carry. Without this, the 2-line
    /// lookahead would happily hand "Hémoglobine" the "42,3 %" of the
    /// Hématocrite line below it in column-split OCR layouts — % is the most
    /// common unit-like token on a lab report.
    private static let hormoneUnits: Set<String> = ["pg/mL", "pmol/L", "ng/dL", "nmol/L", "ng/mL", "mIU/mL"]
    private static let allowedUnits: [String: Set<String>] = [
        "hemoglobin": ["g/dL"],
        "hematocrit": ["%"],
    ]

    /// One reading pulled off a line, with what the document literally showed.
    private struct ValueHit {
        let value: Double
        let unit: String
        let raw: String
        let doubtful: Bool
    }

    private static func matchValue(_ line: String, kind: String) -> ValueHit? {
        let allowed = allowedUnits[kind] ?? hormoneUnits
        let ns = line as NSString
        let range = NSRange(location: 0, length: ns.length)

        if let rx = valueUnitRegex {
            for m in rx.matches(in: line, options: [], range: range) {
                let token = ns.substring(with: m.range(at: 1))
                guard let value = Double(token.replacingOccurrences(of: ",", with: ".")) else { continue }
                let unit = normalizeUnit(ns.substring(with: m.range(at: 2)))
                if allowed.contains(unit) {
                    return ValueHit(value: value, unit: unit, raw: token, doubtful: false)
                }
            }
        }

        // Nothing clean here. Retry tolerating the classic OCR confusions, and
        // hand the result back marked doubtful: we would rather show the user
        // « 1O,2 » and let them decide than silently store a fabricated 10,2.
        if let rx = sloppyValueUnitRegex {
            for m in rx.matches(in: line, options: [], range: range) {
                let token = ns.substring(with: m.range(at: 1))
                // "ol", "Il", "SS" — a scrap of a word, not a misread number.
                if !token.contains(where: { $0.isNumber }) { continue }
                let repaired = repairDigits(token).replacingOccurrences(of: ",", with: ".")
                guard let value = Double(repaired) else { continue }
                let unit = normalizeUnit(ns.substring(with: m.range(at: 2)))
                if allowed.contains(unit) {
                    return ValueHit(value: value, unit: unit, raw: token, doubtful: true)
                }
            }
        }
        return nil
    }

    /// Undo the glyph confusions OCR makes inside a number. Keep the character
    /// set in step with `sloppyValueUnitRegex`.
    private static func repairDigits(_ token: String) -> String {
        String(token.map { (c: Character) -> Character in
            switch c {
            case "O", "o": return "0"
            case "I", "i", "L", "l": return "1"
            case "S", "s": return "5"
            case "B": return "8"
            default: return c
            }
        })
    }

    /// "128.0" → "128", for the raw string we quote back at the user.
    private static func trimNumber(_ v: Double) -> String {
        let s = String(v)
        return s.hasSuffix(".0") ? String(s.dropLast(2)) : s
    }

    /// Systolic/diastolic pair. A unit-bearing pair always wins over a bare
    /// one, and the ×10 "cmHg habit" rescale (12,8/8,2 → 128/82) only applies
    /// when the unit is explicit — otherwise any dd/mm date with a small day
    /// would rescale into a perfectly plausible fabricated reading.
    private static func matchBp(_ line: String) -> (Double, Double)? {
        guard let rx = bpPairRegex else { return nil }
        let ns = line as NSString
        let range = NSRange(location: 0, length: ns.length)
        var bare: (Double, Double)?
        for m in rx.matches(in: line, options: [], range: range) {
            let rawSys = ns.substring(with: m.range(at: 1)).replacingOccurrences(of: ",", with: ".")
            let rawDia = ns.substring(with: m.range(at: 2)).replacingOccurrences(of: ",", with: ".")
            guard var sys = Double(rawSys), var dia = Double(rawDia) else { continue }
            let unitRange = m.range(at: 3)
            let hasUnit = unitRange.location != NSNotFound && unitRange.length > 0
            if hasUnit && sys < 30 && dia < 20 { sys *= 10; dia *= 10 }
            let plausible = sys >= 60 && sys <= 260 && dia >= 30 && dia <= 160 && sys > dia
            if !plausible { continue }
            if hasUnit { return (sys, dia) }
            if bare == nil { bare = (sys, dia) }
        }
        return bare
    }

    private static func normalizeUnit(_ raw: String) -> String {
        switch raw.lowercased().replacingOccurrences(of: " ", with: "") {
        case "pg/ml":  return "pg/mL"
        case "pmol/l": return "pmol/L"
        case "ng/dl":  return "ng/dL"
        case "nmol/l": return "nmol/L"
        case "ng/ml":  return "ng/mL"
        case "miu/ml": return "mIU/mL"
        // µIU/mL and IU/L are equivalent to mIU/mL for LH/FSH/prolactin in
        // clinical practice — normalise so the catalog only has one bucket.
        case "µiu/ml", "uiu/ml", "iu/l", "ui/l": return "mIU/mL"
        // NFS: g/100 mL is the older notation for g/dL — one bucket.
        case "g/dl", "g/100ml": return "g/dL"
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
