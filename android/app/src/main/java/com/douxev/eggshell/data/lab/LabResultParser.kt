package com.douxev.eggshell.data.lab

import java.util.Locale

/**
 * Heuristic parser that extracts hormone values from the raw OCR text of a
 * lab-result document.
 *
 * Design constraints:
 *  - Only emits hormones that the rest of the app already supports (the
 *    HormoneCatalog kinds), so the import never produces a measurement
 *    the user couldn't have typed manually.
 *  - Ignores reference-range mentions, footnotes and units that don't
 *    match the canonical clinical set.
 *  - Returns an empty list rather than guessing when the layout is too
 *    free-form; the user can still type the value by hand.
 *
 * The pipeline:
 *   1. Walk the OCR text line-by-line.
 *   2. For each line, check whether any known hormone alias appears.
 *   3. If so, look for the first `value + unit` pair on the same line; if
 *      missing, peek at the next 1–2 lines (multi-line lab layouts).
 *   4. Dedupe by hormone kind (only keep the first sighting — typically
 *      the patient's actual value, before any "ref: X–Y" range).
 */
object LabResultParser {

    /** Maps a HormoneCatalog kind id to the substrings/regexes we look for
     *  to recognise that hormone in OCR text. Match is case-insensitive. */
    private val HORMONE_PATTERNS: Map<String, List<Regex>> = mapOf(
        "estradiol" to listOf(
            "œstradiol", "oestradiol", "estradiol", "estradiol\\s*\\(e2\\)", "\\bE2\\b",
        ),
        "testosterone" to listOf(
            "testostérone\\s*totale", "testosterone\\s*total", "testostérone", "testosterone",
        ),
        "progesterone" to listOf("progestérone", "progesterone"),
        "lh" to listOf(
            "\\bLH\\b", "hormone\\s*lut[ée]inisante", "luteinizing\\s*hormone",
        ),
        "fsh" to listOf(
            "\\bFSH\\b", "hormone\\s*folliculo[\\- ]?stimulante", "follicle[\\- ]?stimulating\\s*hormone",
        ),
        "prolactin" to listOf("prolactine", "prolactin"),
        "shbg" to listOf("\\bSHBG\\b", "sex\\s*hormone\\s*binding\\s*globulin"),
        // NFS values, tracked alongside hormone draws for HRT/testo follow-up.
        // The negative lookaheads keep "Hémoglobine glyquée" (HbA1c) out —
        // it's a different analyte, in %, that would otherwise shadow Hb.
        "hemoglobin" to listOf(
            "h[ée]moglobine(?!\\s*(?:glyqu|a1c))", "hemoglobin(?!\\s*a1c)", "\\bHGB\\b", "\\bHb\\b(?!\\s*A1c)",
        ),
        "hematocrit" to listOf("h[ée]matocrite", "hematocrit", "\\bHCT\\b", "\\bHte\\b"),
        // Pseudo-kind: expanded into bp_systolic + bp_diastolic by parse().
        // (?<!hyper) keeps "suivi pour hypertension artérielle" (a history
        // note, no reading) from triggering the BP branch.
        BP to listOf(
            "(?<!hyper)tension\\s*art[ée]rielle", "pression\\s*art[ée]rielle", "blood\\s*pressure",
        ),
    ).mapValues { (_, patterns) -> patterns.map { Regex(it, RegexOption.IGNORE_CASE) } }

    /** Pseudo-kind for the paired blood-pressure reading. */
    private const val BP = "bp"

    /** The unit tokens a reading may legitimately carry, as a regex alternation. */
    private const val UNIT_ALTERNATION =
        "(pg/mL|pg/ml|pmol/L|pmol/l|ng/dL|ng/dl|nmol/L|nmol/l|ng/mL|ng/ml|" +
            "mIU/mL|mIU/ml|miu/ml|µIU/mL|µIU/ml|uIU/mL|uIU/ml|UI/L|ui/l|IU/L|iu/l|" +
            "g/dL|g/dl|g/100\\s*mL|g/100\\s*ml|%)"

    /**
     * A number (decimal point or comma) followed by an allowed unit token.
     *
     * The lookbehind refuses a number that begins in the middle of a possibly
     * misread one. Without it « 1O,2 ng/mL » yields a perfectly clean-looking
     * 2 and « I2,5 mIU/mL » a clean 2,5 — a fabricated value, silently stored,
     * in a document a doctor will read. Refusing them here sends the line to
     * the doubtful pass below, where the user gets to see the raw string.
     */
    private val VALUE_UNIT_REGEX = Regex(
        "(?<![\\d.,OoIiLlSsB])(\\d{1,5}[.,]?\\d{0,3})\\s*$UNIT_ALTERNATION",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Same shape as [VALUE_UNIT_REGEX], but tolerant of the glyphs OCR keeps
     * confusing with digits (O→0, l/I→1, S→5, B→8). Only tried once the strict
     * pass has come up empty, and anything it finds is flagged doubtful so the
     * preview shows the raw string and leaves the row switched off.
     *
     * The leading lookbehind refuses a token glued to a word, which is what
     * stops the tail of « Œstradiol » from being read as a number.
     */
    private val SLOPPY_VALUE_UNIT_REGEX = Regex(
        "(?<![\\p{L}\\d.,])([\\dOoIiLlSsB]{1,5}[.,]?[\\dOoIiLlSsB]{0,3})\\s*$UNIT_ALTERNATION",
        RegexOption.IGNORE_CASE,
    )

    /** "128 / 82", "12,8/8,2 cmHg" — the systolic/diastolic pair. The
     *  lookarounds refuse a third "/NNNN" segment and a leading "NN/" so a
     *  date (27/05/2026) can never be consumed as a reading; [matchBp] then
     *  applies plausibility windows and unit preference on top. */
    private val BP_PAIR_REGEX = Regex(
        "(?<![\\d/.,])(\\d{1,3}(?:[.,]\\d)?)\\s*/\\s*(\\d{1,3}(?:[.,]\\d)?)(?!\\s*/\\s*\\d)\\s*(mm\\s*Hg|mmHg|cm\\s*Hg|cmHg)?",
        RegexOption.IGNORE_CASE,
    )

    /** Numeric dates: 27/05/2026, 27-05-2026, 27.05.2026, 2026-05-27, etc.
     *  Both 2- and 4-digit years; both DMY and YMD orderings. */
    private val NUMERIC_DATE_REGEX = Regex(
        "(?<!\\d)(\\d{1,4})([/.\\-])(\\d{1,2})\\2(\\d{1,4})(?!\\d)",
    )

    /** Textual dates: "27 mai 2026", "27 May 2026", "27 mai 26". */
    private val TEXT_MONTHS_FR = mapOf(
        "janvier" to 0, "février" to 1, "fevrier" to 1, "mars" to 2, "avril" to 3,
        "mai" to 4, "juin" to 5, "juillet" to 6, "août" to 7, "aout" to 7,
        "septembre" to 8, "octobre" to 9, "novembre" to 10, "décembre" to 11, "decembre" to 11,
    )
    private val TEXT_MONTHS_EN = mapOf(
        "january" to 0, "february" to 1, "march" to 2, "april" to 3, "may" to 4,
        "june" to 5, "july" to 6, "august" to 7, "september" to 8, "october" to 9,
        "november" to 10, "december" to 11,
        "jan" to 0, "feb" to 1, "mar" to 2, "apr" to 3, "jun" to 5, "jul" to 6,
        "aug" to 7, "sep" to 8, "sept" to 8, "oct" to 9, "nov" to 10, "dec" to 11,
    )
    private val ALL_MONTHS = (TEXT_MONTHS_FR + TEXT_MONTHS_EN)
    private val TEXT_DATE_REGEX = Regex(
        "(\\d{1,2})\\s+([a-zéûôA-ZÉÛÔ]+)\\.?\\s+(\\d{2,4})",
    )

    data class ParsedValue(
        val hormone: String,
        val value: Double,
        val unit: String,
        /** The exact substring the number was read from, kept verbatim so the
         *  preview can quote what the document actually said. */
        val raw: String = "",
        /** True when the raw token mixed letters into the number and we had to
         *  repair it to parse. The value is a guess: the preview shows [raw] in
         *  `error` and starts with the row switched off. */
        val doubtful: Boolean = false,
    )

    /** Everything we managed to pull out of a single OCR pass. */
    data class ParseResult(
        val values: List<ParsedValue>,
        /** Best-guess "date of the lab draw". Null when no date could be
         *  recognised in the document; callers should default to "now". */
        val dateMs: Long?,
        /** Laboratory named on the letterhead, when we recognise one. Feeds the
         *  reading's provenance so the doctor report can tell an import from a
         *  value typed in by hand. Null when nothing was recognised. */
        val labName: String? = null,
    )

    fun parse(text: String): ParseResult {
        val lines = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return ParseResult(emptyList(), null, null)

        val results = LinkedHashMap<String, ParsedValue>() // dedup by hormone, preserve insertion order
        for ((i, line) in lines.withIndex()) {
            val hormone = matchHormone(line) ?: continue

            if (hormone == BP) {
                // Blood pressure is a paired reading — expand to two rows so
                // each side charts independently.
                if (results.containsKey("bp_systolic")) continue
                val pair = matchBp(line)
                    ?: (i + 1).takeIf { it < lines.size }?.let { matchBp(lines[it]) }
                    ?: (i + 2).takeIf { it < lines.size }?.let { matchBp(lines[it]) }
                if (pair != null) {
                    results["bp_systolic"] = ParsedValue(
                        "bp_systolic", pair.first, "mmHg", raw = trimNumber(pair.first),
                    )
                    results["bp_diastolic"] = ParsedValue(
                        "bp_diastolic", pair.second, "mmHg", raw = trimNumber(pair.second),
                    )
                }
                continue
            }

            if (results.containsKey(hormone)) continue // already captured earlier in the doc

            // Try the same line first, then the next two lines for multi-line layouts.
            val candidate = matchValue(line, hormone)
                ?: (i + 1).takeIf { it < lines.size }?.let { matchValue(lines[it], hormone) }
                ?: (i + 2).takeIf { it < lines.size }?.let { matchValue(lines[it], hormone) }
            if (candidate != null) {
                results[hormone] = ParsedValue(
                    hormone = hormone,
                    value = candidate.value,
                    unit = candidate.unit,
                    raw = candidate.raw,
                    doubtful = candidate.doubtful,
                )
            }
        }
        return ParseResult(
            values = results.values.toList(),
            dateMs = extractDate(text),
            labName = detectLabName(lines),
        )
    }

    /** Lab chains common enough on French reports to be worth naming exactly.
     *  Anything else falls back to the letterhead's « Laboratoire … » line. */
    private val LAB_CHAINS = listOf(
        "Biogroup", "Cerballiance", "Synlab", "Eurofins", "Unilabs", "Bioclinic",
        "Labazur", "Inovie", "Dyomedea", "Biopath", "Oriade", "Novescia",
        "Labosud", "Laborizon", "Bioesterel", "Biomnis", "Labcorp",
        "Quest Diagnostics",
    )

    /** « Laboratoire de biologie médicale Saint-Roch », « LABORATOIRE DUPONT »… */
    private val LAB_LINE_REGEX = Regex(
        "(laboratoire(?:\\s+de\\s+biologie(?:\\s+m[ée]dicale)?)?[^,;|(]{0,40})",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Reads the laboratory off the letterhead. Only the top of the document is
     * scanned: further down, « laboratoire » shows up in footnotes and in
     * reference-range disclaimers, which name nothing useful.
     */
    internal fun detectLabName(lines: List<String>): String? {
        val head = lines.take(20)
        for (line in head) {
            LAB_CHAINS.firstOrNull { line.contains(it, ignoreCase = true) }?.let { return it }
        }
        for (line in head) {
            val hit = LAB_LINE_REGEX.find(line)?.groupValues?.get(1)
                ?.trim()?.trimEnd('-', '–', ':', '.')?.trim()
                ?: continue
            // A bare "Laboratoire" with nothing behind it names nobody.
            if (hit.length > 14) return hit.take(48)
        }
        return null
    }

    /**
     * Scans the full text for the most plausible "lab draw date". We pick
     * the **first** date that's within a reasonable window (≤ 3 years
     * ago, ≤ 1 day in the future) on the theory that lab reports put the
     * collection date near the top.
     */
    internal fun extractDate(text: String, now: Long = System.currentTimeMillis()): Long? {
        val cal = java.util.Calendar.getInstance()
        val lower = text.lowercase(Locale.getDefault())

        fun acceptable(ms: Long): Boolean {
            val threeYearsMs = 3L * 365 * 86_400_000L
            return ms in (now - threeYearsMs)..(now + 86_400_000L)
        }

        // Numeric dates first (most common on lab reports).
        for (m in NUMERIC_DATE_REGEX.findAll(text)) {
            val a = m.groupValues[1].toIntOrNull() ?: continue
            val b = m.groupValues[3].toIntOrNull() ?: continue
            val c = m.groupValues[4].toIntOrNull() ?: continue
            val ms = interpretNumericDate(a, b, c) ?: continue
            if (acceptable(ms)) return ms
        }

        // Textual months (FR + EN).
        for (m in TEXT_DATE_REGEX.findAll(text)) {
            val day = m.groupValues[1].toIntOrNull() ?: continue
            val monthName = m.groupValues[2].lowercase(Locale.getDefault())
            val month = ALL_MONTHS[monthName] ?: continue
            val year = m.groupValues[3].toIntOrNull()?.let { y -> if (y < 100) 2000 + y else y } ?: continue
            cal.clear(); cal.set(year, month, day, 12, 0, 0)
            val ms = cal.timeInMillis
            if (acceptable(ms)) return ms
        }
        return null
    }

    private fun interpretNumericDate(a: Int, b: Int, c: Int): Long? {
        // Heuristics: if the first group is 4 digits → YMD; otherwise → DMY.
        val cal = java.util.Calendar.getInstance()
        val (year, month, day) = when {
            a in 1000..9999 -> Triple(a, b - 1, c)        // 2026-05-27
            c in 1000..9999 -> Triple(c, b - 1, a)        // 27/05/2026
            else -> {
                // Two-digit year: assume 20XX.
                val year2 = if (c < 100) 2000 + c else c
                Triple(year2, b - 1, a)
            }
        }
        if (month !in 0..11 || day !in 1..31) return null
        cal.clear(); cal.set(year, month, day, 12, 0, 0)
        return cal.timeInMillis
    }

    private fun matchHormone(line: String): String? {
        for ((kind, patterns) in HORMONE_PATTERNS) {
            if (patterns.any { it.containsMatchIn(line) }) return kind
        }
        return null
    }

    /** Units each analyte may legitimately carry. Without this, the 2-line
     *  lookahead would happily hand "Hémoglobine" the "42,3 %" of the
     *  Hématocrite line below it in column-split OCR layouts — % is the most
     *  common unit-like token on a lab report. */
    private val HORMONE_UNITS = setOf("pg/mL", "pmol/L", "ng/dL", "nmol/L", "ng/mL", "mIU/mL")
    private val ALLOWED_UNITS: Map<String, Set<String>> = mapOf(
        "hemoglobin" to setOf("g/dL"),
        "hematocrit" to setOf("%"),
    )

    /** One reading pulled off a line, with what the document literally showed. */
    private data class ValueHit(
        val value: Double,
        val unit: String,
        val raw: String,
        val doubtful: Boolean,
    )

    private fun matchValue(line: String, kind: String): ValueHit? {
        val allowed = ALLOWED_UNITS[kind] ?: HORMONE_UNITS
        for (m in VALUE_UNIT_REGEX.findAll(line)) {
            val value = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: continue
            val unit = normalizeUnit(m.groupValues[2])
            if (unit in allowed) return ValueHit(value, unit, m.groupValues[1], doubtful = false)
        }
        // Nothing clean here. Retry tolerating the classic OCR confusions, and
        // hand the result back marked doubtful: we would rather show the user
        // « 1O,2 » and let them decide than silently store a fabricated 10.2.
        for (m in SLOPPY_VALUE_UNIT_REGEX.findAll(line)) {
            val token = m.groupValues[1]
            // "ol", "Il", "SS" — a scrap of a word, not a misread number.
            if (token.none { it.isDigit() }) continue
            val value = repairDigits(token).replace(',', '.').toDoubleOrNull() ?: continue
            val unit = normalizeUnit(m.groupValues[2])
            if (unit in allowed) return ValueHit(value, unit, token, doubtful = true)
        }
        return null
    }

    /** Undo the glyph confusions OCR makes inside a number. Keep the character
     *  set in step with [SLOPPY_VALUE_UNIT_REGEX]. */
    private fun repairDigits(token: String): String = buildString {
        for (c in token) append(
            when (c) {
                'O', 'o' -> '0'
                'I', 'i', 'L', 'l' -> '1'
                'S', 's' -> '5'
                'B' -> '8'
                else -> c
            },
        )
    }

    /** "128.0" → "128", for the raw string we quote back at the user. */
    private fun trimNumber(v: Double): String {
        val s = v.toString()
        return if (s.endsWith(".0")) s.dropLast(2) else s
    }

    /**
     * Systolic/diastolic pair. A unit-bearing pair always wins over a bare
     * one, and the ×10 "cmHg habit" rescale (12,8/8,2 → 128/82) only applies
     * when the unit is explicit — otherwise any dd/mm date with a small day
     * would rescale into a perfectly plausible fabricated reading.
     */
    private fun matchBp(line: String): Pair<Double, Double>? {
        var bare: Pair<Double, Double>? = null
        for (m in BP_PAIR_REGEX.findAll(line)) {
            var sys = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: continue
            var dia = m.groupValues[2].replace(',', '.').toDoubleOrNull() ?: continue
            val hasUnit = m.groupValues[3].isNotEmpty()
            if (hasUnit && sys < 30 && dia < 20) { sys *= 10; dia *= 10 }
            val plausible = sys in 60.0..260.0 && dia in 30.0..160.0 && sys > dia
            if (!plausible) continue
            if (hasUnit) return sys to dia
            if (bare == null) bare = sys to dia
        }
        return bare
    }

    private fun normalizeUnit(raw: String): String = when (raw.lowercase().replace(" ", "")) {
        "pg/ml" -> "pg/mL"
        "pmol/l" -> "pmol/L"
        "ng/dl" -> "ng/dL"
        "nmol/l" -> "nmol/L"
        "ng/ml" -> "ng/mL"
        "miu/ml" -> "mIU/mL"
        // µIU/mL and IU/L are equivalent to mIU/mL for LH/FSH/prolactin in
        // clinical practice — normalise so the catalog only has one bucket.
        "µiu/ml", "uiu/ml", "iu/l", "ui/l" -> "mIU/mL"
        // NFS: g/100 mL is the older notation for g/dL — one bucket.
        "g/dl", "g/100ml" -> "g/dL"
        else -> raw
    }
}
