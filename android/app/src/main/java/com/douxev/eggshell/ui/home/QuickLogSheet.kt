package com.douxev.eggshell.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EnhancedEncryption
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Vaccines
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import com.douxev.eggshell.R
import com.douxev.eggshell.data.FeaturesPrefs
import com.douxev.eggshell.ui.theme.EggShapes

/**
 * « Noter rapidement » — the sheet behind the home FAB (handoff §6.3).
 *
 * One gesture from anywhere: six tiles, each present only when its module is
 * enabled. The footer restates, every time, that what you are about to write
 * stays on the device.
 */
enum class QuickAction { Feel, Dose, Injection, Lab, Photo, Voice }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickLogSheet(
    onDismiss: () -> Unit,
    onPick: (QuickAction) -> Unit,
    vm: QuickLogViewModel = hiltViewModel(),
) {
    val scheme = MaterialTheme.colorScheme
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val meds by vm.medications.collectAsState()
    val journal by vm.journal.collectAsState()
    val hormones by vm.hormones.collectAsState()
    val photos by vm.photos.collectAsState()
    val voice by vm.voice.collectAsState()

    val tiles = buildList {
        if (journal) {
            add(Tile(QuickAction.Feel, R.string.quicklog_feel, Icons.Filled.Mood, scheme.primaryContainer, scheme.onPrimaryContainer))
        }
        if (meds) {
            add(Tile(QuickAction.Dose, R.string.quicklog_dose, Icons.Filled.Medication, scheme.secondaryContainer, scheme.onSecondaryContainer))
            add(Tile(QuickAction.Injection, R.string.quicklog_injection, Icons.Filled.Vaccines, scheme.tertiaryContainer, scheme.onTertiaryContainer))
        }
        if (hormones) {
            add(Tile(QuickAction.Lab, R.string.quicklog_lab, Icons.Filled.Science, scheme.surfaceContainerHighest, scheme.onSurface))
        }
        if (photos) {
            add(Tile(QuickAction.Photo, R.string.quicklog_photo, Icons.Filled.PhotoCamera, scheme.surfaceContainerHighest, scheme.onSurface))
        }
        if (voice) {
            add(Tile(QuickAction.Voice, R.string.quicklog_voice, Icons.Filled.GraphicEq, scheme.surfaceContainerHighest, scheme.onSurface))
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = EggShapes.Sheet,
        containerColor = scheme.surfaceContainerHigh,
        contentColor = scheme.onSurface,
        scrimColor = scheme.scrim.copy(alpha = 0.55f),
    ) {
        Column(
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp, top = 10.dp),
        ) {
            Text(
                stringResource(R.string.quicklog_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                stringResource(R.string.quicklog_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(top = 16.dp),
            ) {
                tiles.chunked(3).forEach { row ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        row.forEach { tile ->
                            QuickTile(
                                tile = tile,
                                onClick = { onPick(tile.action) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 18.dp),
            ) {
                Icon(
                    Icons.Filled.EnhancedEncryption,
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.quicklog_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

private data class Tile(
    val action: QuickAction,
    val labelRes: Int,
    val icon: ImageVector,
    val container: Color,
    val content: Color,
)

@Composable
private fun QuickTile(tile: Tile, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(tile.labelRes)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .height(84.dp)
            .background(tile.container, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 10.dp)
            .semantics(mergeDescendants = true) { contentDescription = label },
    ) {
        Icon(
            tile.icon,
            contentDescription = null,
            tint = tile.content,
            modifier = Modifier.size(25.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            label,
            fontSize = 13.sp,
            color = tile.content,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

@HiltViewModel
class QuickLogViewModel @Inject constructor(prefs: FeaturesPrefs) : ViewModel() {
    val medications: StateFlow<Boolean> = prefs.medications
    val journal: StateFlow<Boolean> = prefs.journal
    val hormones: StateFlow<Boolean> = prefs.hormones
    val photos: StateFlow<Boolean> = prefs.photoTab
    val voice: StateFlow<Boolean> = prefs.voiceTab
}
