package com.douxev.eggshell.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.douxev.eggshell.R
import uniffi.transition.MetricDefinition

/**
 * One configurable 0..N slider gated by a switch. Generalised from the original
 * journal gauge so it can render any [MetricDefinition] — built-in or custom —
 * for both the journal and the bleeding tracker.
 */
@Composable
fun GaugeRow(
    label: String,
    enabled: Boolean,
    value: Float,
    onEnabledChange: (Boolean) -> Unit,
    onValueChange: (Float) -> Unit,
    leftEmoji: String?,
    rightEmoji: String?,
    valueRange: ClosedFloatingPointRange<Float> = 0f..10f,
    steps: Int = 9,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }
        if (enabled) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                if (!leftEmoji.isNullOrBlank()) {
                    Text(leftEmoji, style = MaterialTheme.typography.bodyMedium)
                }
                Slider(
                    value = value,
                    onValueChange = onValueChange,
                    valueRange = valueRange,
                    steps = steps,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                        .semantics { stateDescription = value.toInt().toString() },
                )
                if (!rightEmoji.isNullOrBlank()) {
                    Text(rightEmoji, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/**
 * Render a column of [GaugeRow]s, one per [definition], reading/writing the
 * `enabled` and `value` snapshot maps keyed by definition id. The owning screen
 * seeds those maps (from stored values when editing) and reads them back on
 * save, so this composable stays stateless.
 */
@Composable
fun MetricSlidersColumn(
    definitions: List<MetricDefinition>,
    enabled: SnapshotStateMap<Long, Boolean>,
    values: SnapshotStateMap<Long, Float>,
) {
    definitions.forEach { def ->
        val min = def.minValue.toInt()
        val max = def.maxValue.toInt()
        val (le, re) = metricEmojis(def)
        GaugeRow(
            label = metricLabel(def),
            enabled = enabled[def.id] ?: false,
            value = values[def.id] ?: ((min + max) / 2f),
            onEnabledChange = { enabled[def.id] = it },
            onValueChange = { values[def.id] = it },
            leftEmoji = le,
            rightEmoji = re,
            valueRange = min.toFloat()..max.toFloat(),
            steps = (max - min - 1).coerceAtLeast(0),
        )
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
        "euphoria" -> "😐" to "😄"
        "libido" -> "💤" to "🔥"
        "energy" -> "🥱" to "⚡"
        "flow" -> "💧" to "🩸"
        "pain" -> "🙂" to "😖"
        "cramps" -> "🙂" to "😣"
        else -> null to null
    }
}

private fun builtinLabelRes(domain: String, metricKey: String): Int = when (domain) {
    "journal" -> when (metricKey) {
        "mood" -> R.string.gauge_mood
        "dysphoria" -> R.string.gauge_dysphoria
        "euphoria" -> R.string.gauge_euphoria
        "libido" -> R.string.gauge_libido
        "energy" -> R.string.gauge_energy
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
