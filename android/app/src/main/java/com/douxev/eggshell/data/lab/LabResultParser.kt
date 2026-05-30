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
    ).mapValues { (_, patterns) -> patterns.map { Regex(it, RegexOption.IGNORE_CASE) } }

    /** A number (decimal point or comma) followed by an allowed unit token. */
    private val VALUE_UNIT_REGEX = Regex(
        "(\\d{1,5}[.,]?\\d{0,3})\\s*" +
            "(pg/mL|pg/ml|pmol/L|pmol/l|ng/dL|ng/dl|nmol/L|nmol/l|ng/mL|ng/ml|" +
            "mIU/mL|mIU/ml|miu/ml|µIU/mL|µIU/ml|uIU/mL|uIU/ml|UI/L|ui/l|IU/L|iu/l)",
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
    )

    /** Everything we managed to pull out of a single OCR pass. */
    data class ParseResult(
        val values: List<ParsedValue>,
        /** Best-guess "date of the lab draw". Null when no date could be
         *  recognised in the document; callers should default to "now". */
        val dateMs: Long?,
    )

    fun parse(text: String): ParseResult {
        val lines = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return ParseResult(emptyList(), null)

        val results = LinkedHashMap<String, ParsedValue>() // dedup by hormone, preserve insertion order
        for ((i, line) in lines.withIndex()) {
            val hormone = matchHormone(line) ?: continue
            if (results.containsKey(hormone)) continue // already captured earlier in the doc

            // Try the same line first, then the next two lines for multi-line layouts.
            val candidate = matchValue(line)
                ?: (i + 1).takeIf { it < lines.size }?.let { matchValue(lines[it]) }
                ?: (i + 2).takeIf { it < lines.size }?.let { matchValue(lines[it]) }
            if (candidate != null) {
                results[hormone] = ParsedValue(hormone, candidate.first, candidate.second)
            }
        }
        return ParseResult(values = results.values.toList(), dateMs = extractDate(text))
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

    private fun matchValue(line: String): Pair<Double, String>? {
        val m = VALUE_UNIT_REGEX.find(line) ?: return null
        val raw = m.groupValues[1].replace(',', '.')
        val value = raw.toDoubleOrNull() ?: return null
        val unit = normalizeUnit(m.groupValues[2])
        return value to unit
    }

    private fun normalizeUnit(raw: String): String = when (raw.lowercase()) {
        "pg/ml" -> "pg/mL"
        "pmol/l" -> "pmol/L"
        "ng/dl" -> "ng/dL"
        "nmol/l" -> "nmol/L"
        "ng/ml" -> "ng/mL"
        "miu/ml" -> "mIU/mL"
        // µIU/mL and IU/L are equivalent to mIU/mL for LH/FSH/prolactin in
        // clinical practice — normalise so the catalog only has one bucket.
        "µiu/ml", "uiu/ml", "iu/l", "ui/l" -> "mIU/mL"
        else -> raw
    }
}
