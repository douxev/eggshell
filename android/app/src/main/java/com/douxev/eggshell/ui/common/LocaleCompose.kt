package com.douxev.eggshell.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import java.util.Locale

/**
 * The locale to format with, read so that Compose recomposes when it changes.
 *
 * `Locale.getDefault()` is a static JVM global. Reading it inside a composable
 * gives the right answer once and then never again: the app offers a language
 * picker (`AppCompatDelegate.setApplicationLocales`, see the manifest's
 * `AppLocalesMetadataHolderService`), and on Android 13+ switching language does
 * not necessarily restart the process. Every date, weekday name and
 * `String.format` already composed would keep the old language until something
 * unrelated happened to invalidate it — a half-translated screen, which is
 * worse than either language on its own.
 *
 * `LocalConfiguration` is a snapshot-backed CompositionLocal, so reading the
 * locale through it makes the dependency explicit and the recomposition
 * automatic.
 *
 * Use this anywhere a composable needs a [Locale]; keep `Locale.getDefault()`
 * for non-composable code (repositories, exporters, receivers), where there is
 * no composition to invalidate and the static global is the correct source.
 */
/**
 * No `?: Locale.getDefault()` fallback, deliberately. `Configuration.getLocales()`
 * is non-empty for any Configuration that came from a Context — and writing the
 * fallback would reintroduce, in the one helper meant to remove it, exactly the
 * unobservable read every call site was just converted away from.
 */
@Composable
@ReadOnlyComposable
fun rememberLocale(): Locale = LocalConfiguration.current.locales[0]
