package com.douxev.eggshell.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.douxev.eggshell.ui.theme.EggDim
import com.douxev.eggshell.ui.theme.EggShapes

/** M3 segmented control — one track, separators, single choice. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Segmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        options.forEachIndexed { index, label ->
            SegmentedButton(
                selected = index == selectedIndex,
                onClick = { onSelect(index) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) { Text(label, style = MaterialTheme.typography.labelLarge) }
        }
    }
}

/** Selectable period / filter pill: 36 high, radius 100, 13.5/600. */
@Composable
fun Pill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val container = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val content = if (selected) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(36.dp),
        shape = EggShapes.Pill,
        color = container,
        contentColor = content,
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 15.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.W600,
            )
        }
    }
}

/**
 * Read-only status pill. Punctuality states use it with three distinct
 * containers so the information is never carried by colour alone — the word is
 * always there too (§10).
 */
@Composable
fun StatusPill(
    label: String,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(container, EggShapes.Pill)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = content,
        )
    }
}

/**
 * The FAB of the refonte: a 56 dp rounded square with an 18 dp radius, never a
 * circle. Always hosted inside an [ActionBand], never floating over content.
 */
@Composable
fun EggFab(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    if (label == null) {
        FloatingActionButton(
            onClick = onClick,
            modifier = modifier.size(56.dp),
            shape = EggShapes.Fab,
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Icon(icon, contentDescription = contentDescription)
        }
    } else {
        ExtendedFloatingActionButton(
            onClick = onClick,
            modifier = modifier.height(56.dp),
            shape = EggShapes.Fab,
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            // The visible label already names the action for TalkBack, so the
            // icon stays decorative and `contentDescription` only backs the
            // node when a caller passes a label that differs from the intent.
            icon = { Icon(icon, contentDescription = null) },
            text = { Text(label, style = MaterialTheme.typography.labelLarge) },
        )
    }
}

/**
 * Reserves the bottom band an action lives in (84 dp). The rule of the refonte:
 * a FAB or an action bar never floats over the content — it would hide the last
 * launcher row or the last history line. Use as `Scaffold(bottomBar = …)`.
 */
@Composable
fun ActionBand(
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.CenterEnd,
    content: @Composable BoxScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(EggDim.ActionBandHeight)
                .padding(horizontal = EggDim.ScreenMargin),
            contentAlignment = alignment,
            content = content,
        )
    }
}

/** Pushed-screen app bar: back arrow + inline title; destructive actions in red. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EggTopBar(
    title: String,
    onBack: () -> Unit,
    backContentDescription: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = backContentDescription,
                )
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        modifier = modifier,
    )
}

/** Home-screen header: the date on the left, the settings cog on the right. */
@Composable
fun HomeHeader(
    title: String,
    settingsContentDescription: String,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = onOpenSettings,
            modifier = Modifier.size(EggDim.TouchTarget),
        ) {
            Icon(
                Icons.Filled.Settings,
                contentDescription = settingsContentDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Decorative-only wrapper: hides a purely visual node from accessibility. */
@Composable
fun Decorative(content: @Composable () -> Unit) {
    Box(modifier = Modifier.clearAndSetSemantics {}) { content() }
}
