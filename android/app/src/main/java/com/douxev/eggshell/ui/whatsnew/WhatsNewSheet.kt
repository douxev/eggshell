package com.douxev.eggshell.ui.whatsnew

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bloodtype
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.douxev.eggshell.R

data class WhatsNewHighlight(
    val icon: ImageVector,
    val title: String,
    val sub: String,
)

data class WhatsNewRelease(
    val versionCode: Int,
    val versionName: String,
    val title: String,
    val highlights: List<WhatsNewHighlight>,
)

/**
 * Catalogue of release-note bundles. The sheet always shows [LATEST], which
 * the caller invokes only when [com.douxev.eggshell.data.WhatsNewPrefs]
 * reports the user hasn't seen this version yet.
 *
 * For future releases, bump versionCode in app/build.gradle.kts and add an
 * entry here (or rewrite LATEST in place).
 */
object WhatsNewCatalog {
    val LATEST: WhatsNewRelease = WhatsNewRelease(
        versionCode = 9,
        versionName = "0.0.9",
        title = "Quoi de neuf",
        highlights = listOf(
            WhatsNewHighlight(
                icon = Icons.Filled.Bloodtype,
                title = "Menstruations",
                sub = "Nouvel onglet pour noter tes règles, le spotting et tes symptômes.",
            ),
            WhatsNewHighlight(
                icon = Icons.Filled.Tune,
                title = "Jauges personnalisables",
                sub = "Renomme, réordonne et ajoute tes propres curseurs dans le journal et les menstruations.",
            ),
            WhatsNewHighlight(
                icon = Icons.Filled.Insights,
                title = "Corrélations",
                sub = "Ton humeur en regard de tes prises, changements de traitement et jours de règles.",
            ),
            WhatsNewHighlight(
                icon = Icons.Filled.Edit,
                title = "Édition de traitement",
                sub = "Modifie un traitement ; les changements de dose ou de voie sont gardés pour les corrélations.",
            ),
            WhatsNewHighlight(
                icon = Icons.Filled.Palette,
                title = "Couleur des traitements",
                sub = "Choisis une couleur pour repérer chaque traitement d'un coup d'œil.",
            ),
            WhatsNewHighlight(
                icon = Icons.Filled.Security,
                title = "Leurre persistant",
                sub = "L'appli de notes leurre garde tes notes entre les sessions, isolée de ton vrai coffre.",
            ),
            WhatsNewHighlight(
                icon = Icons.Filled.Notifications,
                title = "Rappels sur mesure",
                sub = "Mode d'affichage (générique, nom ou alias) et priorité, rappel par rappel.",
            ),
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhatsNewSheet(
    release: WhatsNewRelease,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                release.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.whats_new_version_fmt, release.versionName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box(modifier = Modifier.height(4.dp))
            release.highlights.forEach { HighlightRow(it) }
            Box(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    scope.launch {
                        sheetState.hide()
                        onDismiss()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(50),
            ) { Text(stringResource(R.string.whats_new_got_it)) }
        }
    }
}

@Composable
private fun HighlightRow(h: WhatsNewHighlight) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                h.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Column(
            modifier = Modifier
                .padding(start = 14.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(h.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                h.sub,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
