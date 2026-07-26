package com.douxev.eggshell.data.pdf

import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.graphics.toArgb
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.douxev.eggshell.ui.theme.LavendeLight

/**
 * Everything the doctor's report is drawn *with*: geometry, tokens, paints and
 * the formatting policy.
 *
 * Two rules govern this file.
 *
 * 1. **The prototype is 744 px wide, A4 is 595 pt.** Every length quoted in the
 *    handoff's §7 is a mock-up pixel, so it goes through [px] before it touches
 *    the canvas. The page margins are the one exception — §7.1 states them in
 *    points already. Hairlines are the other: 0,8 pt of ink survives a
 *    photocopier, 0,64 pt does not, so rule weights are not rescaled either.
 * 2. **The document is always the light palette**, whatever the app is wearing.
 *    A report is photocopied, faxed and filed; it has one background, white.
 *    The colours are still tokens — [LavendeLight] is the reference light
 *    scheme of the design system, read here rather than transcribed.
 */

/** Mock-up pixels → PDF points. */
internal fun px(v: Float): Float = v * 595f / 744f

internal object Geo {
    const val PAGE_W = 595f
    const val PAGE_H = 842f
    const val MARGIN_X = 42f
    const val MARGIN_TOP = 36f
    const val MARGIN_BOTTOM = 27f

    const val CONTENT_L = MARGIN_X
    const val CONTENT_R = PAGE_W - MARGIN_X
    const val CONTENT_W = CONTENT_R - CONTENT_L

    /** Banner: baseline, then the 1,5 pt rule that closes it. */
    const val BANNER_BASELINE = MARGIN_TOP + 9.6f
    const val BANNER_RULE = MARGIN_TOP + 16f

    /** The body never starts higher than this, nor runs past [BODY_BOTTOM]. */
    const val BODY_TOP = BANNER_RULE + 8f
    const val FOOTER_RULE = PAGE_H - MARGIN_BOTTOM - 24f
    const val BODY_BOTTOM = FOOTER_RULE
    val FOOTER_BASELINE = FOOTER_RULE + px(14f) + 9.6f

    /** Stat blocks and the chart beside them (§7.4.5, §7.5.2, §7.6.3). */
    val STAT_W = px(186f)
    val STAT_GAP = px(30f)
    val CHART_W = CONTENT_W - STAT_W - STAT_GAP

    /** Space above a section title, and below it before its first block. */
    val SECTION_TOP = px(28f)
    val SECTION_TOP_FIRST = px(26f)
    val SECTION_BOTTOM = px(12f)
}

/**
 * The document's ink. Section numbers and the main curve are the only things
 * allowed to be [primary]: one accent, everything else is grey (§7.1).
 */
internal object Ink {
    private val s = LavendeLight
    val page: Int = s.surfaceContainerLowest.toArgb()
    val onSurface: Int = s.onSurface.toArgb()
    val onSurfaceVariant: Int = s.onSurfaceVariant.toArgb()
    val outline: Int = s.outline.toArgb()
    val outlineVariant: Int = s.outlineVariant.toArgb()
    val primary: Int = s.primary.toArgb()
    val secondary: Int = s.secondary.toArgb()
    val tertiary: Int = s.tertiary.toArgb()
    val error: Int = s.error.toArgb()
}

/**
 * The type scale of §7.2, resolved to points.
 *
 * Weight 600 maps to Roboto Medium and weight 700 to the bold face. The 15
 * palettes never reach the document, but the app's own font does.
 */
internal object Sizes {
    val TITLE = px(27f)
    val SUBTITLE = px(15f)
    val MICRO = px(12f)
    val CELL = px(14f)
    val BIG = px(30f)
    val BIG_UNIT = px(16f)
    val BIG_CONV = px(14f)
    val NOTE = px(13f)
    val AXIS = px(9f)
    val ANNOT = px(8f)
    val IDENTITY = px(16f)
}

private val SANS: Typeface = Typeface.SANS_SERIF
private val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
private val BOLD: Typeface = Typeface.DEFAULT_BOLD

/**
 * One set per render. [Paint] is mutable and the dash effects are swapped as
 * the charts are drawn, so the paints are never shared between two documents.
 */
internal class ReportPaints {

    private fun paint(
        size: Float,
        face: Typeface,
        colour: Int,
        tracking: Float = 0f,
    ) = Paint().apply {
        isAntiAlias = true
        textSize = size
        typeface = face
        color = colour
        letterSpacing = tracking
    }

    private fun stroke(colour: Int, width: Float, dash: FloatArray? = null) = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        color = colour
        strokeWidth = width
        if (dash != null) pathEffect = DashPathEffect(dash, 0f)
    }

    // -- text ---------------------------------------------------------------

    val title = paint(Sizes.TITLE, MEDIUM, Ink.onSurface, tracking = -0.0111f)
    val subtitle = paint(Sizes.SUBTITLE, SANS, Ink.onSurfaceVariant)
    val bannerLeft = paint(Sizes.MICRO, BOLD, Ink.onSurface, tracking = 0.1f)
    val bannerRight = paint(Sizes.MICRO, SANS, Ink.onSurfaceVariant, tracking = 0.0417f)
    val footer = paint(Sizes.MICRO, SANS, Ink.onSurfaceVariant)
    val sectionTitle = paint(Sizes.MICRO, BOLD, Ink.primary, tracking = 0.1f)
    val tableHead = paint(Sizes.MICRO, BOLD, Ink.onSurfaceVariant, tracking = 0.0583f)
    val cell = paint(Sizes.CELL, SANS, Ink.onSurface)
    val cellStrong = paint(Sizes.CELL, MEDIUM, Ink.onSurface)
    val cellMuted = paint(Sizes.CELL, SANS, Ink.onSurfaceVariant)
    val caption = paint(Sizes.MICRO, BOLD, Ink.onSurfaceVariant, tracking = 0.0583f)
    val identityLabel = paint(Sizes.MICRO, BOLD, Ink.onSurfaceVariant, tracking = 0.075f)
    val identityValue = paint(Sizes.IDENTITY, MEDIUM, Ink.onSurface)
    val big = paint(Sizes.BIG, MEDIUM, Ink.onSurface, tracking = -0.0133f)
    val bigUnit = paint(Sizes.BIG_UNIT, SANS, Ink.onSurfaceVariant)
    val bigConv = paint(Sizes.BIG_CONV, SANS, Ink.onSurfaceVariant)
    val note = paint(Sizes.NOTE, SANS, Ink.onSurfaceVariant)
    val noteLead = paint(Sizes.NOTE, BOLD, Ink.onSurface)
    val bound = paint(Sizes.MICRO, SANS, Ink.onSurfaceVariant)
    val annotation = paint(Sizes.ANNOT, SANS, Ink.onSurfaceVariant)

    fun axis(colour: Int) = paint(Sizes.AXIS, SANS, colour)

    // -- strokes and fills --------------------------------------------------

    /** §7.3's three weights, kept at their stated size so print survives. */
    val ruleStrong = stroke(Ink.onSurface, 1.5f)
    val ruleTable = stroke(Ink.onSurface, 1.2f)
    val ruleHair = stroke(Ink.outlineVariant, 1f)
    val ruleBaseline = stroke(Ink.onSurface, 1f)
    val ruleGrid = stroke(Ink.outlineVariant, 0.8f)
    val ruleAxis = stroke(Ink.outline, 0.8f)
    val boxOutline = stroke(Ink.outline, 1f)
    val boxOutlineFaint = stroke(Ink.outlineVariant, 1f)
    val checkbox = stroke(Ink.onSurface, 1.2f)
    val zeroLine = stroke(Ink.tertiary, 1f, floatArrayOf(2.4f, 2.4f))
    val missedSeparator = stroke(Ink.outlineVariant, 0.8f, floatArrayOf(1.6f, 2.4f))
    val markerLine = stroke(Ink.onSurfaceVariant, 1f, floatArrayOf(1.6f, 2.4f))

    val seriesMain = stroke(Ink.primary, 1.6f).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    val seriesSecondary = stroke(Ink.tertiary, 1.6f, floatArrayOf(4f, 2.4f)).apply {
        strokeCap = Paint.Cap.BUTT
        strokeJoin = Paint.Join.ROUND
    }

    fun dot(colour: Int) = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = colour
    }
}

/**
 * The document's number and date policy, in one place so the same value can
 * never print two ways. Numbers follow the app's locale — French gets its
 * decimal comma, English its point — and rounding is *rounding*, never the
 * silent truncation the previous exporter did.
 */
internal class ReportFormats(private val locale: Locale) {

    private fun decimals(max: Int) = DecimalFormat("0", DecimalFormatSymbols(locale)).apply {
        maximumFractionDigits = max
        minimumFractionDigits = 0
    }

    private val zero = decimals(0)
    private val one = decimals(1)
    private val two = decimals(2)

    /** Free-form value: at most two decimals, trailing zeros dropped. */
    fun number(v: Double): String = two.format(v)

    /** Scores, weights, pitches: exactly the precision the reader can use. */
    fun score(v: Double): String = one.format(v)

    fun integer(v: Int): String = zero.format(v.toLong())

    /** « +2,3 kg », « −38 Hz » — the sign is part of the reading. */
    fun signed(v: Double, unit: String, oneDecimal: Boolean = true): String {
        val body = if (oneDecimal) one.format(kotlin.math.abs(v)) else zero.format(kotlin.math.abs(v))
        val sign = if (v < 0) "−" else "+"
        return if (unit.isBlank()) "$sign$body" else "$sign$body $unit"
    }

    fun value(v: Double, unit: String?): String {
        val u = unit?.takeIf { it.isNotBlank() } ?: return number(v)
        return "${number(v)} $u"
    }

    private fun fmt(pattern: String) = SimpleDateFormat(pattern, locale)

    private val slashed = fmt("dd/MM/yyyy")
    private val prose = fmt("d MMMM yyyy")
    private val proseNoYear = fmt("d MMMM")
    private val monthShort = fmt("MMM yyyy")
    private val monthLong = fmt("MMMM yyyy")
    private val dayMonth = fmt("dd/MM")

    fun slashed(atMs: Long): String = slashed.format(Date(atMs))
    fun prose(atMs: Long): String = prose.format(Date(atMs))
    fun proseNoYear(atMs: Long): String = proseNoYear.format(Date(atMs))
    fun monthShort(atMs: Long): String = monthShort.format(Date(atMs))
    fun monthLong(atMs: Long): String = monthLong.format(Date(atMs))
    fun dayMonth(atMs: Long): String = dayMonth.format(Date(atMs))

    /** ISO, for the file name only — never a locale-shaped date on disk. */
    fun iso(atMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(atMs))

    fun capitalise(s: String): String =
        s.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
}
