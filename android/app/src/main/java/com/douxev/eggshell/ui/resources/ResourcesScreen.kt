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
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.douxev.eggshell.R

/**
 * Curated list of websites + associations the user finds useful for their
 * own transition. Tap a card → opens the URL in the system browser via
 * ACTION_VIEW. No in-app webview, no analytics, no link rewriting.
 *
 * Two subtabs: General (informational sites, helplines, national tools)
 * vs Associations (regional groups, most reachable through Discord).
 */
enum class ResourceCategory { General, Association }

data class Resource(
    val title: String,
    val description: String,
    val url: String?,
    val category: ResourceCategory,
)

private val PLACEHOLDER_RESOURCES: List<Resource> = listOf(
    // ── General ────────────────────────────────────────────────────────────
    Resource(
        title = "Fransgenre",
        description = "Plateforme communautaire francophone : témoignages, base de connaissances, listes de soignant·es trans-friendly, forum d\'entraide.",
        url = "https://fransgenre.fr",
        category = ResourceCategory.General,
    ),
    Resource(
        title = "AdminisTrans",
        description = "Guide collaboratif des démarches administratives trans en France : changement de prénom, de mention de sexe, papiers, mutuelle.",
        url = "https://administrans.fr/",
        category = ResourceCategory.General,
    ),
    Resource(
        title = "Wikitrans",
        description = "Encyclopédie collaborative francophone sur les questions trans : protocoles HRT, démarches administratives, ressources locales.",
        url = "https://wikitrans.co",
        category = ResourceCategory.General,
    ),
    Resource(
        title = "Transat (annuaire)",
        description = "Annuaire de médecins, endocrinologues et soignant·es trans-friendly partout en France.",
        url = "https://transat-asso.fr/",
        category = ResourceCategory.General,
    ),
    Resource(
        title = "SOS homophobie · ligne d'écoute",
        description = "Soutien anonyme et gratuit en cas d\'agression, discrimination ou détresse. 01 48 06 42 41.",
        url = "https://www.sos-homophobie.org",
        category = ResourceCategory.General,
    ),
    Resource(
        title = "S* Écoute",
        description = "Ligne d\'écoute 24/7 si tu traverses une période très dure : 01 45 39 40 00.",
        url = "https://www.suicide-ecoute.fr",
        category = ResourceCategory.General,
    ),

    // ── Associations (websites) ───────────────────────────────────────────
    Resource(
        title = "OUTrans",
        description = "Association d\'auto-support trans à Paris. Permanences, groupes de parole.",
        url = "https://outrans.org",
        category = ResourceCategory.Association,
    ),
    Resource(
        title = "Chrysalide Lyon",
        description = "Auto-support pour les personnes trans, intersexes, en questionnement et leurs proches.",
        url = "https://chrysalide-asso.fr/",
        category = ResourceCategory.Association,
    ),
    Resource(
        title = "Acceptess-T",
        description = "Association communautaire dédiée à la défense des droits des personnes trans, en particulier migrantes et travailleuses du sexe.",
        url = "https://www.acceptess-t.com",
        category = ResourceCategory.Association,
    ),

    // ── Associations (Discord servers) ─────────────────────────────────────
    Resource(
        title = "Divergenre",
        description = "Discord — Asso Amiens / Somme.",
        url = "https://discord.com/invite/3Jf5CqbN38",
        category = ResourceCategory.Association,
    ),
    Resource(
        title = "Transat (Marseille)",
        description = "Discord — Asso Marseille / Bouches-du-Rhône.",
        url = "https://cutt.ly/discord-transat",
        category = ResourceCategory.Association,
    ),
    Resource(
        title = "Trans Comté",
        description = "Discord — Collectif Franche-Comté.",
        url = "https://cutt.ly/discord-transcomte",
        category = ResourceCategory.Association,
    ),
    Resource(
        title = "Association Trans Toulousaine et Occitane",
        description = "Discord — Asso Toulouse.",
        url = "https://discord.gg/CU2tv7meqY",
        category = ResourceCategory.Association,
    ),
    Resource(
        title = "Collectif Intersexe et Activiste",
        description = "Discord — Asso intersexe.",
        url = "https://discord.com/invite/h2zGVmUM3D",
        category = ResourceCategory.Association,
    ),
    Resource(
        title = "Trans-mission Var",
        description = "Discord — Association Toulon / Var.",
        url = "https://discord.gg/hrTZZDv8QG",
        category = ResourceCategory.Association,
    ),
    Resource(
        title = "ISKIS",
        description = "Discord — Asso LGBTI+ Rennes / Ille-et-Vilaine.",
        url = "https://discord.gg/gAaCYc2kw8",
        category = ResourceCategory.Association,
    ),
    Resource(
        title = "MAG jeunes LGBT+",
        description = "Discord — Asso jeunes LGBTI+ Paris.",
        url = "https://discord.com/invite/GF85Q9v3Yt",
        category = ResourceCategory.Association,
    ),
    Resource(
        title = "Trans inter nb Rouen",
        description = "Discord — Serveur Rouen / Normandie.",
        url = "https://discord.gg/DJTyTF4yEA",
        category = ResourceCategory.Association,
    ),
    Resource(
        title = "Meuf Trans Toulouse",
        description = "Discord — Serveur transfem Toulouse.",
        url = "https://discord.gg/mk9X3UcKWv",
        category = ResourceCategory.Association,
    ),
    Resource(
        title = "Révolte TransDraconique",
        description = "Discord — Serveur Nancy / Metz / Grand Est.",
        url = "https://discord.gg/Ggh2cVcjtn",
        category = ResourceCategory.Association,
    ),
    Resource(
        title = "ASTR / Le Châlet Transfem",
        description = "Discord — Serveur transfem Suisse.",
        url = "https://discord.gg/HUXnBuKCrq",
        category = ResourceCategory.Association,
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResourcesScreen(
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    var tabIndex by rememberSaveable { mutableStateOf(0) }
    val tabs = remember {
        listOf(ResourceCategory.General, ResourceCategory.Association)
    }
    val currentCategory = tabs[tabIndex]
    val items = remember(currentCategory) {
        PLACEHOLDER_RESOURCES.filter { it.category == currentCategory }
    }

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
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = tabIndex) {
                tabs.forEachIndexed { i, cat ->
                    Tab(
                        selected = i == tabIndex,
                        onClick = { tabIndex = i },
                        text = {
                            Text(
                                stringResource(
                                    when (cat) {
                                        ResourceCategory.General -> R.string.resources_tab_general
                                        ResourceCategory.Association -> R.string.resources_tab_associations
                                    }
                                )
                            )
                        },
                    )
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
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
                items(items, key = { it.title }) { resource ->
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResourceCard(resource: Resource, onOpen: () -> Unit) {
    val clickable = resource.url != null
    // Use a chat-bubble icon for Discord links so the user can tell at a
    // glance "this opens Discord, not a website".
    val leadingIcon: ImageVector =
        if (resource.url?.contains("discord", ignoreCase = true) == true ||
            resource.url?.contains("cutt.ly/discord", ignoreCase = true) == true
        ) Icons.Filled.Forum else Icons.Filled.Public
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
                    leadingIcon,
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
