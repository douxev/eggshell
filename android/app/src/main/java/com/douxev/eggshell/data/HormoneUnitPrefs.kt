package com.douxev.eggshell.data

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-hormone preferred display unit.
 *
 * Each measurement is stored in the unit the user typed at entry time. This
 * file remembers which unit to *display* historic values in — set by the user
 * once from Settings, then applied across the Hormones screen and the PDF
 * export. Returning null means "show as recorded" (no preference).
 *
 * Values live in plain SharedPreferences because the conversion is a UI
 * preference, not data.
 */
@Singleton
class HormoneUnitPrefs @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences =
        SecurePrefs.get(context, PREFS_NAME)

    /**
     * Returns the unit the user explicitly picked from the Settings screen,
     * or null if they haven't picked one. Most callers want [getEffective]
     * instead — it falls back to the conventional default per hormone.
     */
    fun getExplicit(hormone: String): String? {
        val raw = prefs.getString(key(hormone), null) ?: return null
        if (raw == AS_RECORDED) return null
        return raw
    }

    /** True iff the user has opted into "show as recorded" for this hormone. */
    fun isAsRecorded(hormone: String): Boolean =
        prefs.getString(key(hormone), null) == AS_RECORDED

    /**
     * The unit to display values in: explicit user choice if any, else the
     * conventional default for the hormone, else null (i.e. show the raw
     * unit because we have no opinion).
     *
     * Defaults follow common French/international clinical practice for
     * trans HRT monitoring. They can always be overridden from Settings.
     */
    fun getEffective(hormone: String): String? {
        if (isAsRecorded(hormone)) return null
        getExplicit(hormone)?.let { return it }
        return DEFAULTS[hormone]
    }

    fun setPreferred(hormone: String, unit: String?) {
        prefs.edit().apply {
            when {
                unit == null -> remove(key(hormone)) // clears the override, back to default
                unit.isBlank() -> remove(key(hormone))
                else -> putString(key(hormone), unit)
            }
        }.apply()
    }

    /** Explicitly mark a hormone as "show as recorded" (no conversion). */
    fun setAsRecorded(hormone: String) {
        prefs.edit().putString(key(hormone), AS_RECORDED).apply()
    }

    fun defaultFor(hormone: String): String? = DEFAULTS[hormone]

    private fun key(hormone: String) = "$KEY_PREFIX$hormone"

    companion object {
        private const val PREFS_NAME = "transition_hormone_units"
        private const val KEY_PREFIX = "unit_"
        private const val AS_RECORDED = "__as_recorded__"

        /**
         * Conventional defaults applied when the user hasn't explicitly
         * picked a unit. Most lab reports for trans HRT cite these units,
         * regardless of the lab's reporting preference, so the app's
         * default behaviour is "auto-convert to the conventional unit".
         */
        private val DEFAULTS: Map<String, String> = mapOf(
            "estradiol" to "pg/mL",
            "testosterone" to "ng/dL",
            "progesterone" to "ng/mL",
            "lh" to "mIU/mL",
            "fsh" to "mIU/mL",
            "prolactin" to "ng/mL",
            "shbg" to "nmol/L",
        )
    }
}
