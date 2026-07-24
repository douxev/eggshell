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
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.ShowChart
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
        versionCode = 13,
        versionName = "1.0.1",
        title = "Quoi de neuf",
        highlights = listOf(
            WhatsNewHighlight(
                icon = Icons.Filled.Event,
                title = "Règles : n'importe quel jour, ou toute une période",
                sub = "Note un jour passé, ou « cette semaine = règles » en une seule action.",
            ),
            WhatsNewHighlight(
                icon = Icons.Filled.Schedule,
                title = "Prises par période et corrigibles",
                sub = "Déclare une plage de prises (ex. gel quotidien sur des mois) et modifie une prise déjà notée — voie comprise.",
            ),
            WhatsNewHighlight(
                icon = Icons.Filled.NotificationsActive,
                title = "Rappels sur mesure",
                sub = "Modifie tes rappels, donne-leur ton propre texte, ajoute un rappel journal — tout est regroupé au même endroit.",
            ),
            WhatsNewHighlight(
                icon = Icons.Filled.CalendarMonth,
                title = "Calendrier plus parlant",
                sub = "Règles en ligne continue et points de traitement sur le calendrier du journal, avec légende.",
            ),
            WhatsNewHighlight(
                icon = Icons.Filled.ShowChart,
                title = "Courbes datées",
                sub = "Les courbes d'hormones affichent les dates et un rond à chaque jour de prise.",
            ),
            WhatsNewHighlight(
                icon = Icons.Filled.Science,
                title = "Bilan sanguin enrichi",
                sub = "L'import PDF reconnaît maintenant la tension artérielle, l'hémoglobine et l'hématocrite.",
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
