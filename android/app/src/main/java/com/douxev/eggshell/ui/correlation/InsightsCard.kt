package com.douxev.eggshell.ui.correlation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.douxev.eggshell.R
import com.douxev.eggshell.ui.common.ValueFormat
import com.douxev.eggshell.ui.common.builtinLabelRes
import com.douxev.eggshell.ui.components.CardRule
import com.douxev.eggshell.ui.components.CardVariant
import com.douxev.eggshell.ui.components.EggCard
import com.douxev.eggshell.ui.components.MicroLabel
import com.douxev.eggshell.ui.theme.EggColors
import uniffi.transition.Insight
import uniffi.transition.Valence
import kotlin.math.abs

/**
 * The « Ce qui va ensemble » card: the links the engine found, strongest first.
 *
 * **Every string here is co-occurrence, never cause.** The engine compares
 * averages inside one person's own record over a few weeks; it cannot separate
 * "the missed dose wrecked the sleep" from "the bad week did both". The wording
 * is « va avec » / « les nuits où », never « à cause de » — and the sample
 * counts sit on every row, unhidden, because « 6 nuits contre 9 » and
 * « 40 contre 52 » are very different claims wearing the same sentence.
 *
 * The footer says the same thing in words, once, so the caveat is not carried
 * by the phrasing alone.
 */
@Composable
fun InsightsCard(insights: List<Insight>, modifier: Modifier = Modifier) {
    if (insights.isEmpty()) return

    EggCard(
        variant = CardVariant.Low,
        padding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
        modifier = modifier,
    ) {
        Text(
            stringResource(R.string.feel_insights_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        MicroLabel(
            stringResource(R.string.feel_insights_sub),
            modifier = Modifier.padding(top = 2.dp),
        )

        insights.take(MAX_SHOWN).forEachIndexed { index, insight ->
            if (index > 0) CardRule(alpha = 0.14f)
            InsightRow(insight)
        }

        CardRule(modifier = Modifier.padding(top = 4.dp), alpha = 0.14f)
        Text(
            stringResource(R.string.feel_insights_caveat),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

@Composable
private fun InsightRow(insight: Insight) {
    val scheme = MaterialTheme.colorScheme
    val rising = insight.delta > 0
    // Neutral metrics get no verdict colour at all: libido and vividness are
    // not achievements, and painting one green would be the app deciding what
    // a good night looks like for somebody else.
    val tint = when {
        insight.valence == Valence.NEUTRAL -> scheme.onSurfaceVariant
        insight.favourable -> EggColors.success
        else -> scheme.error
    }

    Row(
        modifier = Modifier.padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            if (rising) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = tint,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                sentenceFor(insight),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface,
            )
            // The counts are the honesty of the row. Never folded away behind a
            // "confidence" badge: the reader can weigh 6-vs-9 themselves, and a
            // single word could not carry the same information.
            MicroLabel(
                stringResource(
                    R.string.feel_insights_sample_fmt,
                    insight.sampleWith,
                    insight.sampleWithout,
                ),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * « Ton sommeil va avec les jours sans oubli : +2,1 en moyenne. »
 *
 * Built from two halves so the metric and the condition can be translated
 * independently — a sentence assembled from a single format string per
 * combination would be sixty strings, and the sixty-first would be forgotten.
 */
@Composable
private fun sentenceFor(insight: Insight): String {
    val metric = stringResource(metricLabelRes(insight.metricKey))
    val condition = stringResource(conditionLabelRes(insight.againstKey))
    val amount = ValueFormat.significant(abs(insight.delta), digits = 2)
    return stringResource(
        if (insight.delta > 0) R.string.feel_insights_up_fmt else R.string.feel_insights_down_fmt,
        metric,
        condition,
        amount,
    )
}

/**
 * Names the metric exactly as its slider does, by asking the same resolver.
 * A finding that said « Qualité du sommeil » while the editor said
 * `sleep_quality` would read as two unrelated things.
 */
private fun metricLabelRes(key: String): Int {
    val domain = if (key in DREAM_KEYS) "dreams" else "journal"
    val res = builtinLabelRes(domain, key)
    return if (res != 0) res else R.string.feel_insights_metric_generic
}

private val DREAM_KEYS = setOf("sleep_quality", "recall", "vividness", "emotional_tone")

private fun conditionLabelRes(key: String): Int = when (key) {
    "missed_dose" -> R.string.feel_insights_cond_missed
    "late_dose" -> R.string.feel_insights_cond_late
    "clean_day" -> R.string.feel_insights_cond_clean
    "lucid_dream" -> R.string.feel_insights_cond_lucid
    "any_dream" -> R.string.feel_insights_cond_dream
    else -> R.string.feel_insights_metric_generic
}

/**
 * Past this the card stops being a summary. The engine already ranks by size,
 * so the tail is the weakest findings — and a wall of them is how a reader
 * starts believing all of it.
 */
private const val MAX_SHOWN = 5
