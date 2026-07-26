package com.douxev.eggshell.ui.hormones

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.douxev.eggshell.R

/**
 * Filter chips of the Mesures zone. The refonte keeps two distinct species
 * (D4): the selector chip at radius 10, and the period pill at radius 100.
 * Harmonising them would make a filter indistinguishable from a period.
 */
internal val MeasureChipShape = RoundedCornerShape(10.dp)

object HormoneCatalog {
    /** Analytes surfaced in the Hormones tab. Weight uses the same storage
     *  backend but lives in its own UI tab, so it's not in this list. The
     *  blood-pressure pair and the NFS values (Hb, Hte) share the thread —
     *  HRT/testo follow-up tracks them at the same cadence as hormone draws. */
    val KINDS = listOf(
        "estradiol", "progesterone", "testosterone",
        "lh", "fsh", "prolactin", "shbg",
        "bp_systolic", "bp_diastolic", "hemoglobin", "hematocrit",
        "other",
    )

    /** Stable identifier used in the hormone_measurements table to store
     *  weight entries. The Poids tab filters on it, the Hormones tab filters
     *  it out. */
    const val WEIGHT = "weight"

    val UNITS = listOf(
        "pg/mL", "pmol/L", "ng/dL", "nmol/L", "ng/mL", "mIU/mL",
        "mmHg", "g/dL", "%", "other",
    )

    val WEIGHT_UNITS = listOf("kg", "lb")

    @Composable
    fun kindLabel(id: String): String = stringResource(
        when (id) {
            "estradiol" -> R.string.hormone_estradiol
            "progesterone" -> R.string.hormone_progesterone
            "testosterone" -> R.string.hormone_testosterone
            "lh" -> R.string.hormone_lh
            "fsh" -> R.string.hormone_fsh
            "prolactin" -> R.string.hormone_prolactin
            "shbg" -> R.string.hormone_shbg
            "bp_systolic" -> R.string.hormone_bp_systolic
            "bp_diastolic" -> R.string.hormone_bp_diastolic
            "hemoglobin" -> R.string.hormone_hemoglobin
            "hematocrit" -> R.string.hormone_hematocrit
            WEIGHT -> R.string.weight_kind
            else -> R.string.hormone_other
        }
    )

    /**
     * Local kg ↔ lb conversion. The Rust core's [uniffi.transition.convertHormoneValue]
     * doesn't know about weight, so we handle it client-side. Returns null
     * when the units aren't recognised (caller falls back to the raw value).
     */
    fun convertWeight(value: Double, from: String, to: String): Double? = when {
        from == to -> value
        from == "kg" && to == "lb" -> value * 2.20462
        from == "lb" && to == "kg" -> value / 2.20462
        else -> null
    }
}
