package com.douxev.eggshell.ui.medication

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.Vaccines
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import com.douxev.eggshell.R
import com.douxev.eggshell.punctuality.DeltaLabel

/**
 * Canonical medication kind/route identifiers stored in the DB, paired with
 * their localised string resources. Identifiers are stable contracts — the
 * labels are presentation-only.
 *
 * The punctuality wording lives here too: the list, the detail and the chart
 * all read the same table, so an intake can never be called "à l'heure" on one
 * screen and "+18 min" on the next.
 */
object MedicationCatalog {

    val KINDS = listOf(
        "estrogen",
        "progesterone",
        "testosterone",
        "anti_androgen",
        "gnrh_agonist",
        "supplement",
        "other",
    )

    val ROUTES = listOf(
        "oral",
        "sublingual",
        "topical",
        "transdermal",
        "injection_im",
        "injection_sc",
        "suppository",
        "other",
    )

    @StringRes
    fun kindLabelRes(id: String): Int = when (id) {
        "estrogen" -> R.string.kind_estrogen
        "progesterone" -> R.string.kind_progesterone
        "testosterone" -> R.string.kind_testosterone
        "anti_androgen" -> R.string.kind_anti_androgen
        "gnrh_agonist" -> R.string.kind_gnrh_agonist
        "supplement" -> R.string.kind_supplement
        else -> R.string.kind_other
    }

    @StringRes
    fun routeLabelRes(id: String): Int = when (id) {
        "oral" -> R.string.route_oral
        "sublingual" -> R.string.route_sublingual
        "topical" -> R.string.route_topical
        "transdermal" -> R.string.route_transdermal
        "injection_im" -> R.string.route_injection_im
        "injection_sc" -> R.string.route_injection_sc
        "suppository" -> R.string.route_suppository
        else -> R.string.route_other
    }

    @StringRes
    fun injectionSiteLabelRes(id: String): Int = when (id) {
        "thigh_left" -> R.string.site_thigh_left
        "thigh_right" -> R.string.site_thigh_right
        "abdomen_left_upper" -> R.string.site_abdomen_left_upper
        "abdomen_right_upper" -> R.string.site_abdomen_right_upper
        "abdomen_left_lower" -> R.string.site_abdomen_left_lower
        "abdomen_right_lower" -> R.string.site_abdomen_right_lower
        "glute_left" -> R.string.site_glute_left
        "glute_right" -> R.string.site_glute_right
        "deltoid_left" -> R.string.site_deltoid_left
        "deltoid_right" -> R.string.site_deltoid_right
        else -> R.string.site_other
    }

    fun isInjection(route: String) = route == "injection_im" || route == "injection_sc"

    /**
     * Tile glyph of a treatment: `vaccines` for anything that goes through a
     * needle, `medication` otherwise (handoff §6.4).
     */
    fun routeIcon(route: String): ImageVector =
        if (isInjection(route)) Icons.Filled.Vaccines else Icons.Filled.Medication

    /** ± this many minutes still counts as "on time" everywhere (D2). */
    const val ON_TIME_TOLERANCE_MIN: Int = 15

    /** Separator of the refonte: a middle dot, breathing on both sides. */
    const val SEP: String = " · "
}

/**
 * The localized wording of a punctuality offset — the *word* half of the
 * "glyph + colour + word" rule (§10). Never derive one of these in a screen:
 * three screens and the doctor report have to say the same thing.
 *
 * Resolved off a [Context] rather than `stringResource` because the chart takes
 * a plain lambda, which cannot call a composable.
 */
internal fun deltaLabelText(context: Context, label: DeltaLabel): String = when (label) {
    DeltaLabel.OnTime -> context.getString(R.string.meds_on_time)
    DeltaLabel.Missed -> context.getString(R.string.meds_missed)
    is DeltaLabel.Early -> context.getString(R.string.meds_delta_early_fmt, label.minutes)
    is DeltaLabel.Minutes -> context.getString(R.string.meds_delta_minutes_fmt, label.minutes)
    is DeltaLabel.Hours -> context.getString(R.string.meds_delta_hours_fmt, label.hours)
    is DeltaLabel.HoursMinutes ->
        context.getString(R.string.meds_delta_hours_minutes_fmt, label.hours, label.minutes)
}

@Composable
internal fun deltaLabelText(label: DeltaLabel): String =
    deltaLabelText(LocalContext.current, label)

/** The two label lambdas [com.douxev.eggshell.ui.components.PunctualityChart] asks for. */
internal class ChartLabels(private val context: Context) {
    val delta: (DeltaLabel) -> String = { deltaLabelText(context, it) }
    val missed: (Int) -> String = { context.getString(R.string.meds_chart_missed_fmt, it) }
}

@Composable
internal fun rememberChartLabels(): ChartLabels {
    val context = LocalContext.current
    return remember(context) { ChartLabels(context) }
}

/** Trims the trailing `.0` a Double picks up on its way to the screen. */
internal fun formatDoseValue(v: Double): String {
    val s = v.toString()
    return if (s.endsWith(".0")) s.dropLast(2) else s
}

/** « 2 mg », or just « 2 » when the treatment carries no unit. */
internal fun formatDoseWithUnit(dose: Double?, unit: String?): String? {
    val amount = dose?.let(::formatDoseValue) ?: return null
    val u = unit?.takeIf { it.isNotBlank() } ?: return amount
    return "$amount $u"
}
