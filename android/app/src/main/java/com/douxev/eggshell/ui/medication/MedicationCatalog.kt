package com.douxev.eggshell.ui.medication

import androidx.annotation.StringRes
import com.douxev.eggshell.R

/**
 * Canonical medication kind/route identifiers stored in the DB, paired with
 * their localised string resources. Identifiers are stable contracts — the
 * labels are presentation-only.
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
}
