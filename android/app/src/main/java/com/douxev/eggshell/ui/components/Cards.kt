package com.douxev.eggshell.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.douxev.eggshell.ui.theme.EggDim
import com.douxev.eggshell.ui.theme.EggShapes

/**
 * The content-card of the refonte: flat, tonal, 24 dp radius, no shadow.
 * Surfaces differentiate by tonal tier, never by elevation.
 */
enum class CardVariant { Primary, Tertiary, Secondary, Low, Outlined, Error }

@Composable
fun EggCard(
    modifier: Modifier = Modifier,
    variant: CardVariant = CardVariant.Low,
    padding: PaddingValues = PaddingValues(EggDim.CardPadding),
    shape: Shape = EggShapes.Card,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val container: Color = when (variant) {
        CardVariant.Primary -> scheme.primaryContainer
        CardVariant.Tertiary -> scheme.tertiaryContainer
        CardVariant.Secondary -> scheme.secondaryContainer
        CardVariant.Low -> scheme.surfaceContainerLow
        CardVariant.Outlined -> scheme.surface
        CardVariant.Error -> scheme.errorContainer
    }
    val onContainer: Color = when (variant) {
        CardVariant.Primary -> scheme.onPrimaryContainer
        CardVariant.Tertiary -> scheme.onTertiaryContainer
        CardVariant.Secondary -> scheme.onSecondaryContainer
        CardVariant.Low, CardVariant.Outlined -> scheme.onSurface
        CardVariant.Error -> scheme.onErrorContainer
    }
    val border = if (variant == CardVariant.Outlined) {
        BorderStroke(1.dp, scheme.outlineVariant)
    } else {
        null
    }
    val body: @Composable () -> Unit = {
        Column(modifier = Modifier.padding(padding), content = content)
    }
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            color = container,
            contentColor = onContainer,
            border = border,
        ) { body() }
    } else {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            color = container,
            contentColor = onContainer,
            border = border,
        ) { body() }
    }
}

/**
 * The one empty state of the app (§5.3): a low card, a sentence in the second
 * person, and a button that starts the thing. Never a blank screen.
 */
@Composable
fun EmptyState(
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    EggCard(modifier = modifier, variant = CardVariant.Low) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (actionLabel != null && onAction != null) {
            TextButton(
                onClick = onAction,
                modifier = Modifier.padding(top = 4.dp),
            ) { Text(actionLabel, style = MaterialTheme.typography.labelLarge) }
        }
    }
}

/**
 * Errors live in the flow as an error-container card with an explicit message
 * and a way to retry — never a toast the user can miss (§5.3).
 */
@Composable
fun ErrorCard(
    message: String,
    modifier: Modifier = Modifier,
    retryLabel: String? = null,
    onRetry: (() -> Unit)? = null,
) {
    EggCard(modifier = modifier, variant = CardVariant.Error) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
        if (retryLabel != null && onRetry != null) {
            TextButton(onClick = onRetry, modifier = Modifier.padding(top = 4.dp)) {
                Text(retryLabel, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/**
 * Loading placeholder shaped like the real content (§5.3). Never a full-screen
 * spinner: the page keeps its silhouette while the vault query runs.
 */
@Composable
fun SkeletonBlock(
    height: Dp,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(height),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {}
}

/** The 1 dp hairline used inside cards, at 20 % of the current ink. */
@Composable
fun CardRule(modifier: Modifier = Modifier, alpha: Float = 0.20f) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp),
        color = LocalContentColorOrOnSurface().copy(alpha = alpha),
    ) {}
}

@Composable
private fun LocalContentColorOrOnSurface(): Color =
    androidx.compose.material3.LocalContentColor.current

/** Small tinted icon tile used as the leading slot of rows and identity cards. */
@Composable
fun IconTile(
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    shape: Shape = EggShapes.IconTile,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    content: @Composable () -> Unit,
) {
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .size(size)
            .background(container, shape),
        contentAlignment = Alignment.Center,
    ) { content() }
}
