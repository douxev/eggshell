package com.douxev.eggshell.ui.common

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10

/**
 * How a measured value is written on screen.
 *
 * `Double.toString()` was doing this job, and it prints whatever the binary
 * representation happens to be: a 0.1 + 0.2 conversion came out as
 * `0.30000000000000004`, and a unit conversion of an œstradiol reading landed
 * on `43.66972477064221 pg/mL`. Neither is a measurement — a lab sheet quotes
 * four or five figures at most, and the rest is arithmetic noise dressed up as
 * precision.
 *
 * The rule is **significant figures, not decimal places**. Blood analytes span
 * six orders of magnitude between a TSH in mIU/L and a platelet count, so a
 * fixed number of decimals is wrong at one end or the other: `%.2f` turns
 * 0.001234 into `0.00`, and pads 98765.4 with digits nobody measured.
 */
object ValueFormat {

    /** Figures a displayed measurement is quoted to. */
    const val SIGNIFICANT_DIGITS = 5

    /**
     * [v] to [digits] significant figures, trailing zeros kept.
     *
     * Keeping the zeros is deliberate: `0.30000` and `0.3` are the same number
     * but not the same claim, and a column of readings that all quote the same
     * width is the one a reader can scan for a change. The zeros are only ever
     * padded to the right of the significant run, never invented to the left of
     * it — 0.001234 keeps its leading zeros and still shows five figures.
     *
     * Never switches to scientific notation: the unit already bounds the order
     * of magnitude on every screen this feeds, and `1.234e-4` on a lab reading
     * reads as a bug, not as a value.
     */
    fun significant(v: Double, digits: Int = SIGNIFICANT_DIGITS): String {
        // NaN / ±∞ reach this from a conversion against a zero factor. There is
        // no honest rendering, and printing "NaN" onto a reading row is worse
        // than saying nothing.
        // NaN / ±∞ reach this from a conversion against a zero factor. There is
        // no honest rendering, and printing "NaN" onto a reading row is worse
        // than saying nothing.
        if (!v.isFinite()) return EMPTY
        if (v == 0.0) return BigDecimal.ZERO.setScale(digits - 1).toPlainString()

        // A value with more integer digits than we quote is already past the
        // precision we claim, and rounding it into the significant run would
        // *destroy* measured digits rather than hide unmeasured ones: a platelet
        // count of 123456 would be reported as 123460. Show it whole instead.
        if (floor(log10(abs(v))).toInt() >= digits) {
            return BigDecimal(v).setScale(0, RoundingMode.HALF_UP).toPlainString()
        }

        // Round to the significant run FIRST, then measure where it landed.
        // Measuring the raw value instead puts 0.0999999 one decade too low and
        // renders it "0.100000" — six figures, because the rounding that
        // carried it over 0.1 happened after the width was already decided.
        val rounded = BigDecimal(v).round(java.math.MathContext(digits, RoundingMode.HALF_UP))
        if (rounded.signum() == 0) return BigDecimal.ZERO.setScale(digits - 1).toPlainString()

        // Where the first significant digit sits: 3 for 1234.5, -4 for 0.00012345.
        val exponent = floor(log10(abs(rounded.toDouble()))).toInt()
        val scale = (digits - 1 - exponent).coerceAtLeast(0)

        return rounded.setScale(scale, RoundingMode.HALF_UP).toPlainString()
    }

    /**
     * The value as written, with a bare `.0` dropped — 12.0 becomes `12`.
     *
     * This is what seeds an editable field, and it deliberately does NOT round:
     * saving a dialog writes back whatever the field holds, so quoting a
     * rounded value there would let a save silently overwrite the stored
     * reading with the display's approximation of it.
     */
    fun plain(v: Double): String {
        if (!v.isFinite()) return EMPTY
        val s = v.toString()
        return if (s.endsWith(".0")) s.dropLast(2) else s
    }

    /** Shown where a number would otherwise be — an em dash, not "null". */
    const val EMPTY = "—"
}
