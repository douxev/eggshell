package com.douxev.eggshell.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.EnhancedEncryption
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.douxev.eggshell.BuildConfig
import com.douxev.eggshell.R
import com.douxev.eggshell.data.FeaturesPrefs
import com.douxev.eggshell.data.HormoneUnitPrefs
import com.douxev.eggshell.data.ScheduleRepository
import com.douxev.eggshell.data.ThemePrefs
import com.douxev.eggshell.data.VaultRepository
import com.douxev.eggshell.reminders.NotifContentPrefs
import com.douxev.eggshell.security.DecoyVerifier
import com.douxev.eggshell.security.VaultPrefs
import com.douxev.eggshell.ui.common.ScreenHeader
import com.douxev.eggshell.ui.components.CardVariant
import com.douxev.eggshell.ui.components.EggCard
import com.douxev.eggshell.ui.components.IconTile
import com.douxev.eggshell.ui.components.ListRow
import com.douxev.eggshell.ui.components.SectionTitle
import com.douxev.eggshell.ui.theme.AppTheme
import com.douxev.eggshell.ui.theme.EggDim
import com.douxev.eggshell.ui.theme.EggShapes
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Live summaries for the three doors. The subtitles are regenerated from real
 * state — a settings hub that lies about what is on is worse than no subtitle.
 */
@HiltViewModel
class SettingsHubViewModel @Inject constructor(
    features: FeaturesPrefs,
    private val notifContent: NotifContentPrefs,
    private val schedules: ScheduleRepository,
    themePrefs: ThemePrefs,
    private val units: HormoneUnitPrefs,
    private val vault: VaultRepository,
    private val decoy: DecoyVerifier,
) : ViewModel() {

    /** How many of the eight modules are on right now. */
    val enabledModules: StateFlow<Int> = combine(
        listOf(
            features.medications,
            features.appointments,
            features.journal,
            features.bleeding,
            features.hormones,
            features.weightTracking,
            features.photoTab,
            features.voiceTab,
        ),
    ) { values -> values.count { it } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val theme: StateFlow<AppTheme> = themePrefs.theme
    val notifMode: StateFlow<NotifContentPrefs.Mode> = notifContent.mode

    private val _lockMode = MutableStateFlow(vault.currentMode)
    val lockMode: StateFlow<VaultPrefs.Mode?> = _lockMode.asStateFlow()
    private val _decoyOn = MutableStateFlow(decoy.hasDecoyPin)
    val decoyOn: StateFlow<Boolean> = _decoyOn.asStateFlow()

    /** The unit the lab screens show estradiol in — the one users recognise. */
    private val _estradiolUnit = MutableStateFlow(units.getEffective("estradiol"))
    val estradiolUnit: StateFlow<String?> = _estradiolUnit.asStateFlow()

    /**
     * Re-read the prefs the other doors may have changed while we were away.
     * `VaultPrefs`, `DecoyVerifier` and `HormoneUnitPrefs` are plain getters,
     * not flows, so the hub pulls them on every entry rather than pretending
     * to be reactive.
     */
    fun refresh() {
        _lockMode.value = vault.currentMode
        _decoyOn.value = decoy.hasDecoyPin
        _estradiolUnit.value = units.getEffective("estradiol")
    }

    /**
     * Switching what a reminder reveals also has to re-resolve the plain-text
     * mirror labels, otherwise already-scheduled alarms keep the old wording.
     */
    fun setNotifMode(mode: NotifContentPrefs.Mode) {
        notifContent.setMode(mode)
        viewModelScope.launch { runCatching { schedules.syncFromDb() } }
    }
}

/**
 * Réglages — seven screens collapsed into three doors (§2.4).
 *
 * Reminders moved *up* a level: the notification-content card lives here, and
 * the full CRUD hub is one row away. The PDF export left settings entirely —
 * it belongs to Rendez-vous, next to « Préparer ma consultation ».
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsHubScreen(
    onBack: () -> Unit,
    /** Porte 1 — les huit interrupteurs de modules. */
    onOpenModules: () -> Unit,
    /** Porte 2 — verrouillage, PIN, PIN leurre, masquage, captures, sauvegarde. */
    onOpenSecurity: () -> Unit,
    /** Porte 3 — thèmes, unités d’affichage, langue. */
    onOpenAppearance: () -> Unit,
    onOpenReminders: () -> Unit,
    onOpenResources: () -> Unit,
    vm: SettingsHubViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val enabled by vm.enabledModules.collectAsState()
    val theme by vm.theme.collectAsState()
    val notifMode by vm.notifMode.collectAsState()
    val lockMode by vm.lockMode.collectAsState()
    val decoyOn by vm.decoyOn.collectAsState()
    val estradiolUnit by vm.estradiolUnit.collectAsState()
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.refresh() }

    val openDonation: () -> Unit = {
        // A plain HTTPS link, not an SDK: the donation flow must not make a
        // single network call from inside eggshell itself.
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://paypal.me/metraf"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    val securitySub = stringResource(
        R.string.set_door_security_sub_fmt,
        lockMode?.let { lockModeLabel(it) } ?: stringResource(R.string.set_door_security_no_mode),
        stringResource(
            if (decoyOn) R.string.set_door_security_decoy_on
            else R.string.set_door_security_decoy_off,
        ),
        stringResource(R.string.set_door_security_backup),
    )
    val appearanceSub = stringResource(
        R.string.set_door_appearance_sub_fmt,
        theme.displayName,
        currentLanguageLabel(),
        estradiolUnit ?: stringResource(R.string.set_look_units_sub_none),
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
            item { ScreenHeader(title = stringResource(R.string.set_hub_title), onBack = onBack) }

            item {
                DoorRow(
                    icon = { Icon(Icons.Filled.Apps, contentDescription = null) },
                    title = stringResource(R.string.set_door_modules),
                    subtitle = stringResource(R.string.set_door_modules_sub_fmt, enabled),
                    onClick = onOpenModules,
                )
            }
            item {
                DoorRow(
                    icon = { Icon(Icons.Filled.EnhancedEncryption, contentDescription = null) },
                    title = stringResource(R.string.set_door_security),
                    subtitle = securitySub,
                    onClick = onOpenSecurity,
                )
            }
            item {
                DoorRow(
                    icon = { Icon(Icons.Filled.Palette, contentDescription = null) },
                    title = stringResource(R.string.set_door_appearance),
                    subtitle = appearanceSub,
                    onClick = onOpenAppearance,
                )
            }

            // ---- Rappels : une section, plus un sous-écran ----------------
            item {
                Spacer(Modifier.height(6.dp))
                SectionTitle(stringResource(R.string.set_section_reminders))
            }
            item {
                NotifContentCard(
                    selected = notifMode,
                    onSelect = vm::setNotifMode,
                )
            }
            item {
                // The full reminder CRUD (labs, photo, voice, journal,
                // priority, schedule deletion) stays one tap away.
                ListRow(
                    title = stringResource(R.string.settings_open_reminders),
                    subtitle = stringResource(R.string.set_open_reminders_sub),
                    leading = {
                        IconTile(
                            container = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Icon(
                                Icons.Filled.NotificationsActive,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    },
                    onClick = onOpenReminders,
                )
            }

            item { Spacer(Modifier.height(6.dp)) }
            item { SupportCard(onClick = openDonation) }
            item { Footer(onOpenResources = onOpenResources) }
        }
    }
}

@Composable
private fun DoorRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    ListRow(
        title = title,
        subtitle = subtitle,
        leading = {
            IconTile(
                size = 52.dp,
                shape = EggShapes.SmallTile,
                container = MaterialTheme.colorScheme.primaryContainer,
            ) {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.material3.LocalContentColor provides
                        MaterialTheme.colorScheme.onPrimaryContainer,
                ) { icon() }
            }
        },
        onClick = onClick,
    )
}

/**
 * What a reminder is allowed to reveal, with a framed preview of the very
 * thing at stake: the lock screen. Reading the mode name is not enough —
 * people need to see the sentence a passer-by would read.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NotifContentCard(
    selected: NotifContentPrefs.Mode,
    onSelect: (NotifContentPrefs.Mode) -> Unit,
) {
    val options = listOf(
        NotifContentPrefs.Mode.GENERIC to R.string.set_notif_chip_generic,
        NotifContentPrefs.Mode.NAME to R.string.set_notif_chip_name,
        NotifContentPrefs.Mode.ALIAS to R.string.set_notif_chip_alias,
    )
    val previewRes = when (selected) {
        NotifContentPrefs.Mode.GENERIC -> R.string.set_notif_preview_generic
        NotifContentPrefs.Mode.NAME -> R.string.set_notif_preview_name
        NotifContentPrefs.Mode.ALIAS -> R.string.set_notif_preview_alias
    }
    EggCard(
        variant = CardVariant.Low,
        padding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Text(
            stringResource(R.string.set_notif_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            stringResource(R.string.set_notif_sub),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            modifier = Modifier.padding(top = 14.dp),
        ) {
            options.forEach { (mode, labelRes) ->
                FilterChip(
                    selected = mode == selected,
                    onClick = { onSelect(mode) },
                    label = { Text(stringResource(labelRes)) },
                    // Filter chips keep their own 10 dp radius so they never
                    // read as the fully-round period pills (D4).
                    shape = RoundedCornerShape(10.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                )
            }
        }
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .padding(top = 14.dp)
                .fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Notifications,
                    contentDescription = stringResource(R.string.set_notif_preview_label),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(17.dp),
                )
                Text(
                    stringResource(previewRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SupportCard(onClick: () -> Unit) {
    EggCard(variant = CardVariant.Tertiary, onClick = onClick) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Favorite,
                contentDescription = null,
                modifier = Modifier.size(26.dp),
            )
            androidx.compose.foundation.layout.Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.set_support_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.set_support_sub),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f),
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

@Composable
private fun Footer(onOpenResources: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            onClick = onOpenResources,
            color = MaterialTheme.colorScheme.surface,
            shape = EggShapes.Pill,
        ) {
            Box(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                Text(
                    stringResource(R.string.set_footer_resources),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Text(
            stringResource(R.string.set_footer_separator),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Text(
            // The version is the build's, never a literal — a wrong version
            // number in a bug report costs more than it looks.
            stringResource(R.string.set_footer_version_fmt, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Short name of a vault mode, shared by the hub subtitle and the door. */
@Composable
internal fun lockModeLabel(mode: VaultPrefs.Mode): String = stringResource(
    when (mode) {
        VaultPrefs.Mode.KEYSTORE_ONLY -> R.string.mode_keystore_only_title
        VaultPrefs.Mode.KEYSTORE_BIOMETRIC -> R.string.mode_keystore_biometric_title
        VaultPrefs.Mode.KEYSTORE_PASSPHRASE -> R.string.mode_keystore_passphrase_title
        VaultPrefs.Mode.PARANOID -> R.string.mode_paranoid_title
    },
)

/**
 * The app-locale label to print in the Apparence & langue subtitle. Reads the
 * per-app locale rather than the device one: they diverge as soon as the user
 * picks a language in the third door.
 */
@Composable
internal fun currentLanguageLabel(): String {
    val tag = remember(LocalConfiguration.current) {
        AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore(',')
    }
    return stringResource(
        when {
            tag.startsWith("fr", ignoreCase = true) -> R.string.settings_language_fr
            tag.startsWith("en", ignoreCase = true) -> R.string.settings_language_en
            else -> R.string.settings_language_system
        },
    )
}
