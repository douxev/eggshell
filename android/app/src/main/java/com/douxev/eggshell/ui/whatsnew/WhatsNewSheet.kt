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
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Schedule
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
        versionCode = 10,
        versionName = "0.1.0",
        title = "Quoi de neuf",
        highlights = listOf(
            WhatsNewHighlight(
                icon = Icons.Filled.Event,
                title = "Rendez-vous",
                sub = "Nouvel onglet pour noter tes RDV, les professionnel·les et ce qu'il y a à faire.",
            ),
            WhatsNewHighlight(
                icon = Icons.Filled.Insights,
                title = "Résumé",
                sub = "Compare ta semaine ou ton mois au précédent : humeur, prises notées vs prévues, symptômes.",
            ),
            WhatsNewHighlight(
                icon = Icons.Filled.Schedule,
                title = "Historique des prises",
                sub = "Supprime une prise, et note l'heure exacte d'une prise oubliée (antidatage).",
            ),
            WhatsNewHighlight(
                icon = Icons.Filled.DeleteOutline,
                title = "Supprimer un traitement",
                sub = "Archive un traitement, ou supprime-le définitivement avec tout son historique.",
            ),
            WhatsNewHighlight(
                icon = Icons.Filled.Lock,
                title = "Bilans labo protégés",
                sub = "Importe tes résultats même quand le PDF du labo est verrouillé par un mot de passe.",
            ),
            WhatsNewHighlight(
                icon = Icons.Filled.PictureAsPdf,
                title = "Export PDF médecin",
                sub = "Le récapitulatif PDF pour ton médecin ne plante plus et s'ouvre dans le partage.",
            ),
            WhatsNewHighlight(
                icon = Icons.Filled.Mood,
                title = "Humeur confirmée",
                sub = "Un petit mot confirme l'enregistrement de ton ressenti, avec un accès au journal.",
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
