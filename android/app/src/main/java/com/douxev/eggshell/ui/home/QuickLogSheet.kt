package com.douxev.eggshell.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Vaccines
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.douxev.eggshell.R

/**
 * Bottom sheet shown when the user taps the home FAB. Matches the
 * `QuickMenu` from the prototype: 3-column grid of pastel cards, each routing
 * to the relevant logging screen.
 */
enum class QuickAction { Feel, Dose, Injection, Lab, Photo, Voice }

/** Which quick-log tiles are visible. Mirrors [FeaturesPrefs] so the sheet
 *  can hide what the user doesn't use. Lab is always shown — lab reminders
 *  exist independently of any tab and the user can always need to log one. */
data class QuickLogVisibility(
    val medications: Boolean,
    val journal: Boolean,
    val hormones: Boolean,
    val photos: Boolean,
    val voice: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickLogSheet(
    onDismiss: () -> Unit,
    onPick: (QuickAction) -> Unit,
    visibility: QuickLogVisibility,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
        ) {
            Text(
                stringResource(R.string.quicklog_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 18.dp),
            )
            val items = buildList {
                if (visibility.journal) add(QuickItem(QuickAction.Feel, Icons.Filled.Mood, R.string.quicklog_feel,
                    MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer))
                if (visibility.medications) {
                    add(QuickItem(QuickAction.Dose, Icons.Filled.Medication, R.string.quicklog_dose,
                        MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer))
                    add(QuickItem(QuickAction.Injection, Icons.Filled.Vaccines, R.string.quicklog_injection,
                        MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer))
                }
                if (visibility.hormones) add(QuickItem(QuickAction.Lab, Icons.Filled.ShowChart, R.string.quicklog_lab,
                    MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer))
                if (visibility.photos) add(QuickItem(QuickAction.Photo, Icons.Filled.PhotoCamera, R.string.quicklog_photo,
                    MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer))
                if (visibility.voice) add(QuickItem(QuickAction.Voice, Icons.Filled.GraphicEq, R.string.quicklog_voice,
                    MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer))
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(items) { item -> QuickTile(item, onPick) }
            }
        }
    }
}

private data class QuickItem(
    val action: QuickAction,
    val icon: ImageVector,
    val labelRes: Int,
    val background: Color,
    val foreground: Color,
)

@Composable
private fun QuickTile(item: QuickItem, onPick: (QuickAction) -> Unit) {
    Surface(
        onClick = { onPick(item.action) },
        shape = RoundedCornerShape(20.dp),
        color = item.background,
        contentColor = item.foreground,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(item.icon, contentDescription = null)
            Text(stringResource(item.labelRes), style = MaterialTheme.typography.labelLarge)
        }
    }
}
