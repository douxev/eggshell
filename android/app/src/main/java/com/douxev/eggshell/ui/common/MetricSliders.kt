package com.douxev.eggshell.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.douxev.eggshell.R
import com.douxev.eggshell.ui.theme.EggColors
import uniffi.transition.MetricDefinition
import kotlin.math.roundToInt

/**
 * The configurable indicators of the refonte (§6.2): one row per slider, the
 * name on the left, the value `n/10` on the right **in the row's accent**, and
 * the two emojis framing the track.
 *
 * There is no per-entry on/off switch any more: hiding, reordering and creating
 * an indicator all happen once, in the metric editor. What the form shows is
 * what the entry records.
 */

/**
 * §6.2 accent map. Built-ins own a fixed hue so the same indicator keeps the
 * same colour in the sliders, in `MoodBars` and in the charts; a user-created
 * slider borrows the same four hues in catalog order rather than inventing one,
 * which would drift away from the palette.
 */
@Composable
fun metricAccent(def: MetricDefinition, index: Int): Color = when (def.metricKey) {
    "mood" -> MaterialTheme.colorScheme.primary
    "dysphoria" -> MaterialTheme.colorScheme.error
    "euphoria" -> MaterialTheme.colorScheme.tertiary
    "libido" -> MaterialTheme.colorScheme.secondary
    "energy" -> EggColors.success
    // Bleeding built-ins: the flow is the "measured value", pain and cramps read
    // as the discomfort axis.
    "flow" -> MaterialTheme.colorScheme.error
    "pain" -> MaterialTheme.colorScheme.secondary
    "cramps" -> MaterialTheme.colorScheme.tertiary
    else -> when (index % 4) {
        0 -> MaterialTheme.colorScheme.primary
        1 -> MaterialTheme.colorScheme.error
        2 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.secondary
    }
}

/**
 * One indicator row. [value] is in the definition's own domain (0..10 for every
 * indicator the app creates), and the slider steps by one unit so the keyboard
 * and TalkBack land on whole numbers.
 */
@Composable
fun MetricSliderRow(
    def: MetricDefinition,
    value: Float,
    accent: Color,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val min = def.minValue.toInt()
    val max = def.maxValue.toInt()
    val label = metricLabel(def)
    val (lowEmoji, highEmoji) = metricEmojis(def)
    // roundToInt, not toInt: Compose's stepped Slider snaps in float, so the
    // 8th position of a 1..10 range arrives as 7.9999998 and truncation
    // rendered it as 7 — the scale read 1 2 3 4 5 6 7 7 9 10.
    val shown = value.roundToInt().coerceIn(min, max)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.feel_value_fmt, shown, max),
                style = MaterialTheme.typography.labelLarge,
                color = accent,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!lowEmoji.isNullOrBlank()) Text(lowEmoji, fontSize = 17.sp)
            val cd = stringResource(R.string.feel_slider_cd_fmt, label, shown, max)
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = min.toFloat()..max.toFloat(),
                steps = (max - min - 1).coerceAtLeast(0),
                colors = SliderDefaults.colors(
                    thumbColor = accent,
                    activeTrackColor = accent,
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                ),
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = cd },
            )
            if (!highEmoji.isNullOrBlank()) Text(highEmoji, fontSize = 17.sp)
        }
    }
}

/**
 * The stack of indicator rows, reading and writing the `values` snapshot map
 * keyed by definition id. The owning screen seeds the map (from the stored
 * values when editing) and reads it back on save, so this stays stateless.
 */
@Composable
fun MetricSliderStack(
    definitions: List<MetricDefinition>,
    values: SnapshotStateMap<Long, Float>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        definitions.forEachIndexed { index, def ->
            val min = def.minValue.toInt()
            val max = def.maxValue.toInt()
            MetricSliderRow(
                def = def,
                value = values[def.id] ?: ((min + max) / 2f),
                accent = metricAccent(def, index),
                onValueChange = { values[def.id] = it },
            )
        }
    }
}

/** Display label: the user's free-text label for custom metrics, or a
 *  localized string for built-ins (whose stored label is empty). */
@Composable
fun metricLabel(def: MetricDefinition): String {
    if (def.label.isNotBlank()) return def.label
    val res = builtinLabelRes(def.domain, def.metricKey)
    return if (res != 0) stringResource(res) else def.metricKey
}

/** Emoji pair: the custom metric's own emojis, or sensible built-in defaults. */
fun metricEmojis(def: MetricDefinition): Pair<String?, String?> {
    if (!def.emojiLeft.isNullOrBlank() || !def.emojiRight.isNullOrBlank()) {
        return def.emojiLeft to def.emojiRight
    }
    return when (def.metricKey) {
        "mood" -> "😞" to "😊"
        "dysphoria" -> "😌" to "😣"
        "sleep_quality" -> "😵" to "😴"
        "recall" -> "🌫️" to "🔎"
        "vividness" -> "🌫️" to "🎬"
        "emotional_tone" -> "😢" to "😊"
        "euphoria" -> "😐" to "😄"
        "libido" -> "💤" to "🔥"
        "energy" -> "🥱" to "⚡"
        "flow" -> "💧" to "🩸"
        "pain" -> "🙂" to "😖"
        "cramps" -> "🙂" to "😣"
        else -> null to null
    }
}

/**
 * Localized name of a built-in slider, or 0 for a custom one.
 *
 * Internal rather than private so the insights card can name the same metric
 * the same way — a finding that called it « Qualité du sommeil » while the
 * editor called it `sleep_quality` would read as two different things.
 */
internal fun builtinLabelRes(domain: String, metricKey: String): Int = when (domain) {
    "journal" -> when (metricKey) {
        "mood" -> R.string.gauge_mood
        "dysphoria" -> R.string.gauge_dysphoria
        "euphoria" -> R.string.gauge_euphoria
        "libido" -> R.string.gauge_libido
        "energy" -> R.string.gauge_energy
        else -> 0
    }
    "dreams" -> when (metricKey) {
        "sleep_quality" -> R.string.dreams_gauge_sleep_quality
        "recall" -> R.string.dreams_gauge_recall
        "vividness" -> R.string.dreams_gauge_vividness
        "emotional_tone" -> R.string.dreams_gauge_emotional_tone
        else -> 0
    }
    "bleeding" -> when (metricKey) {
        "flow" -> R.string.bleeding_gauge_flow
        "pain" -> R.string.bleeding_gauge_pain
        "cramps" -> R.string.bleeding_gauge_cramps
        else -> 0
    }
    else -> 0
}
