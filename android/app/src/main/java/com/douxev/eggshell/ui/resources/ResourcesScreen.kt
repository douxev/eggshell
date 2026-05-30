package com.douxev.eggshell.ui.resources

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.douxev.eggshell.R

/**
 * Curated list of websites + associations the user finds useful for their
 * own transition. Placeholder entries below are starting points — edit
 * [PLACEHOLDER_RESOURCES] (or load from prefs later) to swap them out.
 *
 * Tap a card → opens the URL in the system browser via ACTION_VIEW. No
 * in-app webview, no analytics, no link rewriting — just a chooser the
 * user already trusts.
 */
data class Resource(
    val title: String,
    val description: String,
    val url: String?,
)

private val PLACEHOLDER_RESOURCES: List<Resource> = listOf(
    Resource(
        title = "Fransgenre",
        description = "Plateforme communautaire francophone : témoignages, base de connaissances, listes de soignant·es trans-friendly, forum d\'entraide.",
        url = "https://fransgenre.fr",
    ),
    Resource(
        title = "AdminTrans",
        description = "Guide collaboratif des démarches administratives trans en France : changement de prénom, de mention de sexe, papiers, mutuelle.",
        url = "https://admintrans.fr",
    ),
    Resource(
        title = "Wikitrans",
        description = "Encyclopédie collaborative francophone sur les questions trans : protocoles HRT, démarches administratives, ressources locales.",
        url = "https://wikitrans.co",
    ),
    Resource(
        title = "OUTrans",
        description = "Association d'auto-support trans à Paris. Permanences, groupes de parole.",
        url = "https://outrans.org",
    ),
    Resource(
        title = "Chrysalide Lyon",
        description = "Auto-support pour les personnes trans, intersexes, en questionnement et leurs proches.",
        url = "https://chrysalidelyon.fr",
    ),
    Resource(
        title = "Acceptess-T",
        description = "Association communautaire dédiée à la défense des droits des personnes trans, en particulier migrantes et travailleuses du sexe.",
        url = "https://www.acceptess-t.com",
    ),
    Resource(
        title = "FTM Variance",
        description = "Ressources et témoignages pour personnes transmasc.",
        url = null,
    ),
    Resource(
        title = "Transat",
        description = "Annuaire de médecins, endocrinologues et soignant·es trans-friendly partout en France.",
        url = null,
    ),
    Resource(
        title = "SOS homophobie · ligne d'écoute",
        description = "Soutien anonyme et gratuit en cas d\'agression, discrimination ou détresse. 01 48 06 42 41.",
        url = "https://www.sos-homophobie.org",
    ),
    Resource(
        title = "S* Écoute",
        description = "Ligne d'écoute 24/7 si tu traverses une période très dure : 01 45 39 40 00.",
        url = "https://www.suicide-ecoute.fr",
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResourcesScreen(
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.resources_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.resources_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            items(PLACEHOLDER_RESOURCES, key = { it.title }) { resource ->
                ResourceCard(
                    resource = resource,
                    onOpen = {
                        val url = resource.url ?: return@ResourceCard
                        runCatching {
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResourceCard(resource: Resource, onOpen: () -> Unit) {
    val clickable = resource.url != null
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (clickable) Modifier.clickable(onClick = onOpen) else Modifier),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Public,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Column(
                modifier = Modifier
                    .padding(start = 14.dp)
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    resource.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    resource.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (clickable) {
                Icon(
                    Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = stringResource(R.string.resources_open_external),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
