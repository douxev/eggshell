package com.douxev.eggshell.ui.theme

import android.app.Activity
import android.view.Window
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Edge-to-edge, without the three APIs Android 15 deprecated.
 *
 * This replaces `androidx.activity.enableEdgeToEdge()`. Google Play reports the
 * app as calling `Window.setStatusBarColor`, `Window.setNavigationBarColor` and
 * `LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES` — and disassembling the release
 * APK puts every one of them inside androidx's own `EdgeToEdgeApi23`, `Api26`,
 * `Api28`, `Api29`, `Api30` and `Api35`. The calls are unreachable or inert on
 * Android 15, so this is a static-scan finding rather than a behavioural one,
 * but the only way to clear it is to stop linking that helper at all: once
 * nothing references it, R8 strips the whole family and the methods leave the
 * APK with them.
 *
 * What `enableEdgeToEdge()` does, and what replaces it here:
 *
 *  1. **Draw behind the system bars** — `setDecorFitsSystemWindows(false)`.
 *     Not deprecated; used unchanged below.
 *  2. **Transparent (or scrimmed) bars** — moved to theme attributes in
 *     `res/values/themes.xml` and `res/values-v29/themes.xml`. The attributes
 *     set the same window fields, but before the window is first shown, so
 *     there is not even the brief opaque bar the API-call version can produce.
 *  3. **Light or dark bar icons** — [SyncSystemBarIcons] below, still through
 *     `WindowInsetsControllerCompat`, which is the current API.
 *  4. **Cutout mode `SHORT_EDGES`** — deliberately dropped. On Android 15 with
 *     `targetSdk` 36 the platform forces edge-to-edge into the cutout anyway
 *     and ignores the setting, so nothing changes there. On API 27–34 the
 *     window is now letterboxed away from the notch in landscape instead of
 *     drawing under it — which is what the horizontal `displayCutout` padding
 *     on the NavHost was already producing visually, so the two agree.
 */
fun Activity.enableEdgeToEdgeCompat() {
    WindowCompat.setDecorFitsSystemWindows(window, false)
}

/**
 * Keeps the system-bar icons legible against whatever the app is drawing.
 *
 * Driven by the resolved Compose surface colour rather than by the system's
 * dark-mode flag, which is what `enableEdgeToEdge()`'s default `SystemBarStyle
 * .auto()` uses. That difference is a fix, not just a port: the app ships
 * fifteen palettes, and a palette's light variant is not obliged to be pale.
 * Reading the luminance of the colour actually behind the bars answers the
 * question that matters — can these icons be seen — instead of a proxy for it.
 *
 * A `SideEffect` rather than `LaunchedEffect`: this must land in the same frame
 * as the colours it describes, or a palette change shows one frame of icons in
 * the wrong polarity.
 */
@Composable
fun SyncSystemBarIcons() {
    val view = LocalView.current
    val surface = MaterialTheme.colorScheme.surface
    // Previews and other non-window hosts have no Activity to configure.
    if (view.isInEditMode) return
    val window: Window = (view.context as? Activity)?.window ?: return
    // Light *icons* go on a dark surface, so the flag is the inverse: it asks
    // for the light-background treatment, i.e. dark icons.
    val lightBars = surface.luminance() > 0.5f
    SideEffect {
        WindowInsetsControllerCompat(window, view).apply {
            isAppearanceLightStatusBars = lightBars
            isAppearanceLightNavigationBars = lightBars
        }
    }
}
