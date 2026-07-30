package com.douxev.eggshell.ui.whatsnew

import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PhotoCamera
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.douxev.eggshell.R
import com.douxev.eggshell.ui.components.IconTile
import com.douxev.eggshell.ui.theme.EggShapes
import kotlinx.coroutines.launch

data class WhatsNewHighlight(
    val icon: ImageVector,
    @StringRes val titleRes: Int,
    @StringRes val subRes: Int,
    /**
     * Optional "read more" target, opened in the browser.
     *
     * Worth knowing before adding one: following it writes the URL into the
     * phone's browser history, which this app cannot reach and cannot clean.
     * For someone relying on the decoy that is a trace the sheet created, so a
     * link only belongs here when what it points at is worth that.
     */
    val linkUrl: String? = null,
)

data class WhatsNewRelease(
    val versionCode: Int,
    val versionName: String,
    @StringRes val titleRes: Int,
    val highlights: List<WhatsNewHighlight>,
)

/**
 * Catalogue of release-note bundles. The sheet always shows [LATEST], which
 * the caller invokes only when [com.douxev.eggshell.data.WhatsNewPrefs]
 * reports the user hasn't seen this version yet.
 *
 * For a future release, bump versionCode in app/build.gradle.kts and rewrite
 * LATEST here — the copy lives in `strings_settings.xml`, never inline.
 */
object WhatsNewCatalog {
    val LATEST: WhatsNewRelease = WhatsNewRelease(
        versionCode = 18,
        versionName = "2.1.0",
        titleRes = R.string.set_wn_title,
        highlights = listOf(
            WhatsNewHighlight(
                icon = Icons.Filled.Description,
                titleRes = R.string.set_wn_1_title,
                subRes = R.string.set_wn_1_sub,
            ),
            WhatsNewHighlight(
                icon = Icons.Filled.Key,
                titleRes = R.string.set_wn_2_title,
                subRes = R.string.set_wn_2_sub,
            ),
            WhatsNewHighlight(
                icon = Icons.Filled.CloudDone,
                titleRes = R.string.set_wn_3_title,
                subRes = R.string.set_wn_3_sub,
            ),
            WhatsNewHighlight(
                icon = Icons.Filled.PhotoCamera,
                titleRes = R.string.set_wn_4_title,
                subRes = R.string.set_wn_4_sub,
            ),
            WhatsNewHighlight(
                icon = Icons.Filled.Bedtime,
                titleRes = R.string.set_wn_5_title,
                subRes = R.string.set_wn_5_sub,
            ),
            WhatsNewHighlight(
                icon = Icons.Filled.BugReport,
                titleRes = R.string.set_wn_6_title,
                subRes = R.string.set_wn_6_sub,
                // The releases index, not a tag: it is right on the day of the
                // release and still right a year later.
                linkUrl = "https://github.com/douxev/eggshell/releases",
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
        shape = EggShapes.Sheet,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // A full list of highlights overflows a short screen; the sheet
                // scrolls rather than clipping the confirm button out of reach.
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                stringResource(release.titleRes),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.whats_new_version_fmt, release.versionName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            release.highlights.forEach { HighlightRow(it) }
            Spacer(Modifier.height(8.dp))
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
                shape = EggShapes.Pill,
            ) { Text(stringResource(R.string.whats_new_got_it)) }
        }
    }
}

@Composable
private fun HighlightRow(h: WhatsNewHighlight) {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.Top) {
        IconTile(container = MaterialTheme.colorScheme.primaryContainer) {
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
            Text(
                stringResource(h.titleRes),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(h.subRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (h.linkUrl != null) {
                val label = stringResource(R.string.whats_new_changelog_link)
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .clickable { openExternally(context, h.linkUrl) }
                        .semantics { contentDescription = label },
                )
            }
        }
    }
}

/**
 * Hand the URL to the browser through the *application* context.
 *
 * Deliberately not `context.startActivity`: MainActivity overrides it to set
 * `leavingForOwnActivity`, which tells `onUserLeaveHint` to skip locking. That
 * is right for a picker or a share sheet — the user is back in seconds — and
 * wrong for a browser, which is a real app switch that can last minutes. Going
 * around the override leaves the flag alone, so the vault locks behind us the
 * way it does for any other departure.
 */
private fun openExternally(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, url.toUri())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    // A device with no browser at all is unusual but not impossible, and an
    // ActivityNotFoundException here would take the whole sheet down.
    runCatching { context.applicationContext.startActivity(intent) }
}
