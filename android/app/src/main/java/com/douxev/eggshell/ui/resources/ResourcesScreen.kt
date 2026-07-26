package com.douxev.eggshell.ui.resources

import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.douxev.eggshell.R
import com.douxev.eggshell.ui.common.ScreenHeader
import com.douxev.eggshell.ui.components.CardVariant
import com.douxev.eggshell.ui.components.EggCard
import com.douxev.eggshell.ui.components.IconTile
import com.douxev.eggshell.ui.components.Segmented
import com.douxev.eggshell.ui.theme.EggDim

/**
 * Curated list of websites and associations, reached from the settings footer
 * (D5). Tapping a card opens the URL in the system browser through
 * ACTION_VIEW: no in-app webview, no analytics, no link rewriting.
 *
 * Two segments: Général (informational sites, helplines, national tools) and
 * Associations (regional groups, most reachable through Discord).
 */
enum class ResourceCategory { General, Association }

data class Resource(
    /** Proper nouns stay literal; anything descriptive is a resource id. */
    val title: String? = null,
    @StringRes val titleRes: Int? = null,
    @StringRes val descriptionRes: Int,
    val url: String?,
    val category: ResourceCategory,
)

private val RESOURCES: List<Resource> = listOf(
    // ── Général ───────────────────────────────────────────────────────────
    Resource(
        title = "Fransgenre",
        descriptionRes = R.string.set_res_fransgenre,
        url = "https://fransgenre.fr",
        category = ResourceCategory.General,
    ),
    Resource(
        title = "AdminisTrans",
        descriptionRes = R.string.set_res_administrans,
        url = "https://administrans.fr/",
        category = ResourceCategory.General,
    ),
    Resource(
        title = "Wikitrans",
        descriptionRes = R.string.set_res_wikitrans,
        url = "https://wikitrans.co",
        category = ResourceCategory.General,
    ),
    Resource(
        titleRes = R.string.set_res_transat_dir_title,
        descriptionRes = R.string.set_res_transat_dir,
        url = "https://transat-asso.fr/",
        category = ResourceCategory.General,
    ),
    Resource(
        titleRes = R.string.set_res_sos_title,
        descriptionRes = R.string.set_res_sos,
        url = "https://www.sos-homophobie.org",
        category = ResourceCategory.General,
    ),
    Resource(
        titleRes = R.string.set_res_ecoute_title,
        descriptionRes = R.string.set_res_ecoute,
        url = "https://www.suicide-ecoute.fr",
        category = ResourceCategory.General,
    ),

    // ── Associations (sites) ──────────────────────────────────────────────
    Resource(
        title = "OUTrans",
        descriptionRes = R.string.set_res_outrans,
        url = "https://outrans.org",
        category = ResourceCategory.Association,
    ),
    Resource(
        title = "Chrysalide Lyon",
        descriptionRes = R.string.set_res_chrysalide,
        url = "https://chrysalide-asso.fr/",
        category = ResourceCategory.Association,
    ),
    Resource(
        title = "Acceptess-T",
        descriptionRes = R.string.set_res_acceptess,
        url = "https://www.acceptess-t.com",
        category = ResourceCategory.Association,
    ),

    // ── Associations (Discord) ────────────────────────────────────────────
    Resource(
        title = "Divergenre",
        descriptionRes = R.string.set_res_divergenre,
        url = "https://discord.com/invite/3Jf5CqbN38",
        category = ResourceCategory.Association,
    ),
    Resource(
        title = "Transat (Marseille)",
        descriptionRes = R.string.set_res_transat_marseille,
        url = "https://cutt.ly/discord-transat",
        category = ResourceCategory.Association,
    ),
    Resource(
        title = "Trans Comté",
        descriptionRes = R.string.set_res_transcomte,
        url = "https://cutt.ly/discord-transcomte",
        category = ResourceCategory.Association,
    ),
    Resource(
        title = "Association Trans Toulousaine et Occitane",
        descriptionRes = R.string.set_res_attoo,
        url = "https://discord.gg/CU2tv7meqY",
        category = ResourceCategory.Association,
    ),
    Resource(
        title = "Collectif Intersexe et Activiste",
        descriptionRes = R.string.set_res_cia,
        url = "https://discord.com/invite/h2zGVmUM3D",
        category = ResourceCategory.Association,
    ),
    Resource(
        title = "Trans-mission Var",
        descriptionRes = R.string.set_res_transmission_var,
        url = "https://discord.gg/hrTZZDv8QG",
        category = ResourceCategory.Association,
    ),
    Resource(
        title = "ISKIS",
        descriptionRes = R.string.set_res_iskis,
        url = "https://discord.gg/gAaCYc2kw8",
        category = ResourceCategory.Association,
    ),
    Resource(
        title = "MAG jeunes LGBT+",
        descriptionRes = R.string.set_res_mag,
        url = "https://discord.com/invite/GF85Q9v3Yt",
        category = ResourceCategory.Association,
    ),
    Resource(
        title = "Trans inter nb Rouen",
        descriptionRes = R.string.set_res_rouen,
        url = "https://discord.gg/DJTyTF4yEA",
        category = ResourceCategory.Association,
    ),
    Resource(
        title = "Meuf Trans Toulouse",
        descriptionRes = R.string.set_res_meuf_toulouse,
        url = "https://discord.gg/mk9X3UcKWv",
        category = ResourceCategory.Association,
    ),
    Resource(
        title = "Révolte TransDraconique",
        descriptionRes = R.string.set_res_revolte,
        url = "https://discord.gg/Ggh2cVcjtn",
        category = ResourceCategory.Association,
    ),
    Resource(
        title = "ASTR / Le Châlet Transfem",
        descriptionRes = R.string.set_res_astr,
        url = "https://discord.gg/HUXnBuKCrq",
        category = ResourceCategory.Association,
    ),
)

@Composable
fun ResourcesScreen(
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    val categories = remember { listOf(ResourceCategory.General, ResourceCategory.Association) }
    val currentCategory = categories[tabIndex]
    val visible = remember(currentCategory) { RESOURCES.filter { it.category == currentCategory } }
    val segments = listOf(
        stringResource(R.string.resources_tab_general),
        stringResource(R.string.resources_tab_associations),
    )

    Scaffold(containerColor = MaterialTheme.colorScheme.surface) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(
                start = EggDim.ScreenMargin,
                end = EggDim.ScreenMargin,
                bottom = 40.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(EggDim.RowGap),
        ) {
            item {
                ScreenHeader(title = stringResource(R.string.resources_title), onBack = onBack)
            }
            item {
                Text(
                    stringResource(R.string.set_resources_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            item {
                Segmented(
                    options = segments,
                    selectedIndex = tabIndex,
                    onSelect = { tabIndex = it },
                )
            }
            items(visible.size) { index ->
                val resource = visible[index]
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
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun ResourceCard(resource: Resource, onOpen: () -> Unit) {
    val clickable = resource.url != null
    // A chat-bubble glyph for Discord invites so it is obvious at a glance
    // that the link leaves for a chat app, not for a website.
    val leadingIcon: ImageVector =
        if (resource.url?.contains("discord", ignoreCase = true) == true) {
            Icons.Filled.Forum
        } else {
            Icons.Filled.Public
        }
    val title = resource.title ?: stringResource(resource.titleRes!!)
    EggCard(
        variant = CardVariant.Low,
        padding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
        onClick = if (clickable) onOpen else null,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(container = MaterialTheme.colorScheme.primaryContainer) {
                Icon(
                    leadingIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Column(
                modifier = Modifier
                    .padding(start = 14.dp)
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(resource.descriptionRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (clickable) {
                Icon(
                    Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = stringResource(R.string.resources_open_external),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
