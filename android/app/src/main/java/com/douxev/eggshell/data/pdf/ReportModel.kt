package com.douxev.eggshell.data.pdf

import com.douxev.eggshell.punctuality.DosePoint
import com.douxev.eggshell.punctuality.PunctualityAxis

/**
 * The doctor's report, as a value.
 *
 * Nothing here knows about a `Canvas`: the model is assembled from the vault by
 * [ReportBuilder] and painted by [ReportPainter]. That seam is what lets the
 * document's *content* be reasoned about — which sections survived, what each
 * figure says — without a device, and it is the only way the Android and the
 * iOS renderers can be held to the same document.
 *
 * Sections carry no number. They are numbered at paint time, after the empty
 * ones have been dropped, so the numbering always reads 1..n with no gap
 * (§7.7).
 */
internal data class ReportModel(
    /** « Période du 26 avril au 26 juillet 2026 ». */
    val title: String,
    /** Day count · edition date · provenance of the figures. */
    val subtitle: String,
    /**
     * Printed only when the vault actually holds both fields. They now come
     * from the encrypted setting store, never from plaintext preferences, so a
     * phone with a decoy mode gives up nothing when they are left unset.
     */
    val identity: Identity?,
    val sections: List<Section>,
    val disclaimerLead: String,
    val disclaimerBody: String,
)

internal data class Identity(val name: String?, val birthDate: String?) {
    val isEmpty: Boolean get() = name.isNullOrBlank() && birthDate.isNullOrBlank()
}

/** A numbered section. Dropped upstream when it has nothing to say. */
internal data class Section(val title: String, val blocks: List<Block>)

internal data class Column(
    val title: String,
    val weight: Float,
    val alignRight: Boolean = false,
    /** The identification column, and the analyte values, are weight 600. */
    val strong: Boolean = false,
    val muted: Boolean = false,
)

internal data class Stat(val label: String, val value: String)

internal data class HeadValue(
    val caption: String,
    val value: String,
    val unit: String,
    /** « · 470 pmol/L » — omitted when there is nothing to convert to. */
    val conversion: String?,
)

internal data class LegendItem(val label: String, val dashed: Boolean, val secondary: Boolean)

internal data class PhotoTile(val date: String, val bytes: ByteArray) {
    // Byte arrays make data classes lie about equality; the id is the date plus
    // the identity of the buffer, which is all any caller here needs.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

internal sealed interface Block {

    /** A framing sentence at cell size, or a grey note at note size. */
    data class Paragraph(val text: String, val note: Boolean = false) : Block

    /** An all-caps over-title above a chart or a values block. */
    data class Caption(val text: String) : Block

    data class Table(
        val columns: List<Column>,
        val rows: List<List<String>>,
        val rowPadPt: Float,
    ) : Block

    /** §2's two-part list: a fixed date column and a flowing sentence. */
    data class DatedList(val rows: List<Pair<String, String>>) : Block

    /** The 148,8 pt label/value block, alone or beside a chart. */
    data class StatChart(
        val stats: List<Stat>,
        val caption: String?,
        val chart: ChartSpec?,
        val punctuality: PunctualitySpec?,
        val note: String?,
        /** §5 centres the block on its chart; §3 and §7 top-align them. */
        val centred: Boolean = false,
    ) : Block

    /** The two big numbers under « TAUX HORMONAUX ». */
    data class HeadValues(val left: HeadValue, val right: HeadValue?) : Block

    /** A full-width chart with the legend row §7.5 gives it. */
    data class WideChart(
        val chart: ChartSpec,
        val legend: List<LegendItem>,
        val legendTail: String?,
    ) : Block

    /** « Fatigue 21 j » — the count carries the weight. */
    data class Chips(val items: List<Pair<String, String>>) : Block

    /** Empty squares the doctor ticks with a pen. */
    data class Checklist(val items: List<String>) : Block

    data class Photos(val tiles: List<PhotoTile>) : Block
}

internal data class TimedValue(val atMs: Long, val value: Double)

internal data class ChartSeries(
    val points: List<TimedValue>,
    val dashed: Boolean,
    /** A dot per sample; the last one is drawn slightly larger. */
    val dots: Boolean,
    val secondary: Boolean,
    /**
     * §5.1 asks every main curve to close on a filled, slightly larger point —
     * it is what tells the reader where the series stops rather than where the
     * plot does. [dots] already ends that way; this is for a bare line, and it
     * stays off for the dashed secondary series, which has no terminal point of
     * its own in §7.5.
     */
    val terminalDot: Boolean = false,
)

/** A dashed vertical at a treatment change, labelled « ↑ dose 18/05 ». */
internal data class ChartMarker(val atMs: Long, val label: String)

/**
 * One chart slot, in points, measured from the top-left of the slot. Fixed
 * heights are the point of the exercise: the layout engine reserves the block
 * whole and breaks the page *before* it rather than clipping it.
 */
internal data class ChartSpec(
    val widthPt: Float,
    val heightPt: Float,
    /** Reserved on the left for the Y labels; 0 when the chart has none. */
    val gutterPt: Float,
    val insetPt: Float,
    val plotTopPt: Float,
    val baselinePt: Float,
    val gridlinesPt: List<Float>,
    /** One label per gridline, top to bottom. Empty for an unlabelled chart. */
    val yTickLabels: List<String>,
    val fromMs: Long,
    val toMs: Long,
    val yMin: Double,
    val yMax: Double,
    val series: List<ChartSeries>,
    val markers: List<ChartMarker> = emptyList(),
    /** « janv. 2026 » / « juil. 2026 » under the plot. */
    val bounds: Pair<String, String>? = null,
)

/**
 * The punctuality scatter of §3. It is not a line chart: the Y axis is an
 * offset in minutes, the band under the dashed separator holds the doses that
 * were never logged, and each dot is coloured by its band.
 */
internal data class PunctualitySpec(
    val widthPt: Float,
    val points: List<DosePoint>,
    val axis: PunctualityAxis,
    val fromMs: Long,
    val toMs: Long,
    /** Three gradations, top to bottom: on time, half the max, the max. */
    val tickLabels: List<String>,
    val missedLabel: String,
)
