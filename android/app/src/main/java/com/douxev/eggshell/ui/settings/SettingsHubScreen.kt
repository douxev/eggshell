package com.douxev.eggshell.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.ShieldMoon
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import android.content.Intent
import android.net.Uri
import com.douxev.eggshell.R

/**
 * "Plus" hub. Shortcuts to reminders, PDF export, hormone units, theme,
 * resources, the dedicated Fonctionnalités screen (where per-view toggles
 * live), and advanced settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsHubScreen(
    onOpenFeatures: () -> Unit,
    onOpenReminders: () -> Unit,
    onOpenAdvanced: () -> Unit,
    onOpenPdf: () -> Unit,
    onOpenHormoneUnits: () -> Unit,
    onOpenTheme: () -> Unit,
    onOpenResources: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val openDonation: () -> Unit = {
        // Browser intent — paypal.me handles deep-linking into the
        // PayPal app on devices that have it installed. We don't bundle
        // the SDK; this stays a plain HTTPS link so the donation flow
        // makes no API call from inside Eggshell itself.
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://paypal.me/metraf"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, top = 6.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        stringResource(R.string.more_title),
                        style = MaterialTheme.typography.headlineLarge,
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        shape = CircleShape,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Icon(
                                Icons.Filled.Lock,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                "  " + stringResource(R.string.today_local_pill),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }

            // Shortcut rows
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column {
                        SettingRow(
                            icon = Icons.Filled.Tune,
                            title = stringResource(R.string.more_row_features),
                            sub = stringResource(R.string.more_row_features_sub),
                            onClick = onOpenFeatures,
                            showDivider = true,
                        )
                        SettingRow(
                            icon = Icons.Filled.Palette,
                            title = stringResource(R.string.more_row_theme),
                            sub = stringResource(R.string.more_row_theme_sub),
                            onClick = onOpenTheme,
                            showDivider = true,
                        )
                        SettingRow(
                            icon = Icons.Filled.PictureAsPdf,
                            title = stringResource(R.string.more_row_pdf),
                            sub = stringResource(R.string.more_row_pdf_sub),
                            onClick = onOpenPdf,
                            showDivider = true,
                        )
                        SettingRow(
                            icon = Icons.Filled.Straighten,
                            title = stringResource(R.string.more_row_units),
                            sub = stringResource(R.string.more_row_units_sub),
                            onClick = onOpenHormoneUnits,
                            showDivider = true,
                        )
                        SettingRow(
                            icon = Icons.Filled.Notifications,
                            title = stringResource(R.string.more_row_reminders),
                            sub = stringResource(R.string.more_row_reminders_sub),
                            onClick = onOpenReminders,
                            showDivider = true,
                        )
                        SettingRow(
                            icon = Icons.Filled.Public,
                            title = stringResource(R.string.more_row_resources),
                            sub = stringResource(R.string.more_row_resources_sub),
                            onClick = onOpenResources,
                            showDivider = true,
                        )
                        SettingRow(
                            icon = Icons.Filled.ShieldMoon,
                            title = stringResource(R.string.more_row_advanced),
                            sub = stringResource(R.string.more_row_advanced_sub),
                            onClick = onOpenAdvanced,
                            showDivider = false,
                        )
                    }
                }
            }

            // Donation: own card so it has a bit more visual weight than
            // a plain shortcut row, without competing with the actually-
            // functional rows above. Tertiary container = warm, non-noisy.
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    ),
                    shape = RoundedCornerShape(24.dp),
                    onClick = openDonation,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.tertiary),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.Favorite,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onTertiary,
                            )
                        }
                        Column(
                            modifier = Modifier
                                .padding(start = 14.dp)
                                .weight(1f)
                        ) {
                            Text(
                                stringResource(R.string.more_row_donate),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                stringResource(R.string.more_row_donate_sub),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.85f),
                            )
                        }
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    sub: String?,
    onClick: () -> Unit,
    showDivider: Boolean,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Column(
                modifier = Modifier
                    .padding(start = 14.dp)
                    .weight(1f)
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (sub != null) {
                    Text(
                        sub,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (showDivider) {
            Box(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            )
        }
    }
}
