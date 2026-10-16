package com.douxev.eggshell.widget

import android.content.Context
import android.content.res.ColorStateList
import android.os.Build
import android.widget.RemoteViews
import androidx.annotation.ColorRes
import androidx.annotation.IdRes
import androidx.compose.ui.graphics.toArgb
import com.douxev.eggshell.R
import com.douxev.eggshell.data.ThemePrefs
import com.douxev.eggshell.ui.theme.AppTheme
import com.douxev.eggshell.ui.theme.extendedColorsFor
import com.douxev.eggshell.ui.theme.resolveScheme

/**
 * The colours a widget is painted in — the same palette the app is wearing.
 *
 * A widget used to be lavender whatever the app looked like, which on a phone
 * set to Catppuccin Mocha or Gruvbox made the home screen the one place the
 * app did not look like itself. There are two ways a widget gets its colours
 * now, and which one applies depends on the theme the user picked:
 *
 * - **Système** (the default). Nothing is painted at runtime. The layouts
 *   reference `@color/widget_*`, which `values/` and `values-night/` define as
 *   the lavender light and dark palettes, and the launcher resolves them
 *   against *its* night mode when it inflates the card. That is what keeps a
 *   widget following a dark-mode switch that happens while the app is not
 *   running: a colour baked in as an integer would sit there in the wrong
 *   polarity until the next repaint.
 * - **A chosen palette.** Every other entry of [AppTheme] is fixed — light or
 *   dark by itself — so its scheme is resolved here and applied as integers
 *   over the XML defaults. Only from Android 12: that is where `RemoteViews`
 *   learnt to tint a background or a progress bar, and painting the text
 *   without the card behind it would put dark words on a light card. Below
 *   that the widget keeps the lavender defaults, which at least follow
 *   light/dark.
 *
 * Read in the provider, which runs in the app's own process — the launcher
 * only ever receives the finished `RemoteViews`, never the theme preference.
 */
class WidgetPalette private constructor(
    val card: Int,
    val onCard: Int,
    val muted: Int,
    val accent: Int,
    val onAccent: Int,
    val accentContainer: Int,
    val onAccentContainer: Int,
    val track: Int,
    private val roles: IntArray,
) {

    /**
     * The colour roles a gauge can be drawn in — the same mapping the journal
     * screen uses ([com.douxev.eggshell.ui.common.metricAccent]), so the bar
     * for « Humeur » is the same colour on the home screen as in the app.
     *
     * Stored in the mirror by ordinal; append only.
     */
    enum class Role(@ColorRes val res: Int) {
        PRIMARY(R.color.widget_role_primary),
        ERROR(R.color.widget_role_error),
        TERTIARY(R.color.widget_role_tertiary),
        SECONDARY(R.color.widget_role_secondary),
        SUCCESS(R.color.widget_role_success);

        companion object {
            fun of(ordinal: Int): Role = entries.getOrElse(ordinal) { PRIMARY }

            /** Which role a metric draws in, by its key, else by position. */
            fun forMetric(key: String, index: Int): Role = when (key) {
                "mood" -> PRIMARY
                "dysphoria" -> ERROR
                "euphoria" -> TERTIARY
                "libido" -> SECONDARY
                "energy" -> SUCCESS
                else -> when (index % 4) {
                    0 -> PRIMARY
                    1 -> ERROR
                    2 -> TERTIARY
                    else -> SECONDARY
                }
            }
        }
    }

    fun role(role: Role): Int = roles[role.ordinal]

    companion object {
        /**
         * The palette to paint with, or null when the XML defaults are right.
         *
         * Null for the system theme (see the class doc) and on anything older
         * than Android 12, where the tints cannot be applied.
         */
        fun current(context: Context): WidgetPalette? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
            val theme = runCatching { ThemePrefs(context.applicationContext).theme.value }
                .getOrDefault(AppTheme.SYSTEM)
            if (theme == AppTheme.SYSTEM) return null
            val scheme = resolveScheme(theme, systemInDark = theme.isDark)
            val extended = extendedColorsFor(theme, systemInDark = theme.isDark)
            return WidgetPalette(
                card = scheme.surfaceContainer.toArgb(),
                onCard = scheme.onSurface.toArgb(),
                muted = scheme.onSurfaceVariant.toArgb(),
                accent = scheme.primary.toArgb(),
                onAccent = scheme.onPrimary.toArgb(),
                accentContainer = scheme.primaryContainer.toArgb(),
                onAccentContainer = scheme.onPrimaryContainer.toArgb(),
                track = scheme.surfaceContainerHighest.toArgb(),
                roles = intArrayOf(
                    scheme.primary.toArgb(),
                    scheme.error.toArgb(),
                    scheme.tertiary.toArgb(),
                    scheme.secondary.toArgb(),
                    extended.success.toArgb(),
                ),
            )
        }
    }
}

/**
 * Applies a [WidgetPalette] to the views of a layout.
 *
 * Every method is a no-op when the palette is null, so a provider can call
 * them unconditionally and the system theme falls through to the XML colours.
 * The method names passed to `RemoteViews` are the ones `androidx.core`'s
 * `RemoteViewsCompat` wraps: `setBackgroundTintList`, `setProgressTintList`,
 * `setProgressBackgroundTintList` (Android 12+), `setColorFilter` and
 * `setTextColor`. A name the launcher does not recognise as remotable would
 * fail the whole card at inflation, so nothing else is used.
 */
internal object WidgetPaint {

    /** The card behind everything. */
    fun card(views: RemoteViews, palette: WidgetPalette?, @IdRes root: Int) {
        palette ?: return
        tintBackground(views, root, palette.card)
    }

    fun onCard(views: RemoteViews, palette: WidgetPalette?, @IdRes vararg ids: Int) {
        palette ?: return
        ids.forEach { views.setTextColor(it, palette.onCard) }
    }

    fun muted(views: RemoteViews, palette: WidgetPalette?, @IdRes vararg ids: Int) {
        palette ?: return
        ids.forEach { views.setTextColor(it, palette.muted) }
    }

    fun accentText(views: RemoteViews, palette: WidgetPalette?, @IdRes vararg ids: Int) {
        palette ?: return
        ids.forEach { views.setTextColor(it, palette.accent) }
    }

    /** A module icon: the vector is white under a tint, and this replaces the tint. */
    fun icon(views: RemoteViews, palette: WidgetPalette?, @IdRes vararg ids: Int) {
        palette ?: return
        ids.forEach { views.setInt(it, "setColorFilter", palette.accent) }
    }

    /** A filled pill: accent background, on-accent text. */
    fun filledPill(views: RemoteViews, palette: WidgetPalette?, @IdRes vararg ids: Int) {
        palette ?: return
        ids.forEach {
            tintBackground(views, it, palette.accent)
            views.setTextColor(it, palette.onAccent)
        }
    }

    /** A tonal container with no text of its own (a stat tile's LinearLayout). */
    fun tile(views: RemoteViews, palette: WidgetPalette?, @IdRes vararg ids: Int) {
        palette ?: return
        ids.forEach { tintBackground(views, it, palette.accentContainer) }
    }

    /** A tonal pill or tile: accent-container background, on-container text. */
    fun tonalPill(views: RemoteViews, palette: WidgetPalette?, @IdRes vararg ids: Int) {
        palette ?: return
        ids.forEach {
            tintBackground(views, it, palette.accentContainer)
            views.setTextColor(it, palette.onAccentContainer)
        }
    }

    /**
     * A gauge's fill and track.
     *
     * Under the system theme the fill still has to be set — which colour a row
     * gets depends on which metric landed on it — so the resource form is used
     * there, which the launcher resolves against its own night mode.
     */
    fun gauge(
        views: RemoteViews,
        palette: WidgetPalette?,
        @IdRes id: Int,
        role: WidgetPalette.Role,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (palette == null) {
            // The resource form: the launcher resolves it against its own night
            // mode, so the role colour flips with the system theme as the XML
            // colours do.
            views.setColorStateList(id, "setProgressTintList", role.res)
            return
        }
        views.setColorStateList(id, "setProgressTintList", ColorStateList.valueOf(palette.role(role)))
        views.setColorStateList(
            id, "setProgressBackgroundTintList", ColorStateList.valueOf(palette.track),
        )
    }

    private fun tintBackground(views: RemoteViews, @IdRes id: Int, color: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        views.setColorStateList(id, "setBackgroundTintList", ColorStateList.valueOf(color))
    }
}
