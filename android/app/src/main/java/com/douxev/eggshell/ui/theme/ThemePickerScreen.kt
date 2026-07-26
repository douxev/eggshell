package com.douxev.eggshell.ui.theme

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.douxev.eggshell.R
import com.douxev.eggshell.data.HormoneUnitPrefs
import com.douxev.eggshell.data.ThemePrefs
import com.douxev.eggshell.ui.common.ScreenHeader
import com.douxev.eggshell.ui.components.IconTile
import com.douxev.eggshell.ui.components.ListRow
import com.douxev.eggshell.ui.components.SectionTitle
import com.douxev.eggshell.ui.components.Segmented
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@HiltViewModel
class ThemePickerViewModel @Inject constructor(
    private val prefs: ThemePrefs,
    private val units: HormoneUnitPrefs,
) : ViewModel() {
    val selected: StateFlow<AppTheme> = prefs.theme
    fun pick(theme: AppTheme) = prefs.set(theme)

    /** Sampled on entry: `HormoneUnitPrefs` is plain prefs, not a flow. */
    private val _estradiolUnit = MutableStateFlow(units.getEffective("estradiol"))
    val estradiolUnit: StateFlow<String?> = _estradiolUnit.asStateFlow()

    fun refresh() {
        _estradiolUnit.value = units.getEffective("estradiol")
    }
}

/** Système / Français / English — the whole language offer of the app. */
private val LANGUAGE_TAGS: List<String> = listOf("", "fr", "en")

/**
 * Porte « Apparence & langue » — the 14 palettes, the language, and the way
 * lab values are shown.
 *
 * The unit picker is one row away rather than inlined: it is a per-analyte
 * catalogue, far too long to sit under a colour grid.
 */
@Composable
fun ThemePickerScreen(
    onBack: () -> Unit,
    /** « Apparence & langue » also owns the lab display units. */
    onOpenHormoneUnits: () -> Unit = {},
    vm: ThemePickerViewModel = hiltViewModel(),
) {
    val selected by vm.selected.collectAsState()
    val estradiolUnit by vm.estradiolUnit.collectAsState()
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.refresh() }

    val currentTag: String = remember(LocalConfiguration.current) {
        AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore(',')
    }
    val languageIndex = LANGUAGE_TAGS.indexOfFirst { tag ->
        if (tag.isEmpty()) currentTag.isEmpty() else currentTag.startsWith(tag, ignoreCase = true)
    }.coerceAtLeast(0)
    val languageLabels = listOf(
        stringResource(R.string.settings_language_system),
        stringResource(R.string.settings_language_fr),
        stringResource(R.string.settings_language_en),
    )

    // Two columns of swatches, laid out as rows inside the single scroller:
    // a nested lazy grid would fight the page for scroll gestures.
    val themeRows = remember { AppTheme.entries.chunked(2) }

    Scaffold(containerColor = MaterialTheme.colorScheme.surface) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ScreenHeader(
                    title = stringResource(R.string.set_door_appearance),
                    onBack = onBack,
                )
            }

            // -- Thème ---------------------------------------------------------
            item {
                SectionTitle(
                    stringResource(R.string.set_look_section_theme),
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            item {
                Text(
                    stringResource(R.string.theme_picker_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            items(themeRows.size) { rowIndex ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    themeRows[rowIndex].forEach { theme ->
                        ThemeSwatchCard(
                            theme = theme,
                            selected = theme == selected,
                            onClick = { vm.pick(theme) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // Keeps the last odd swatch half-width instead of letting
                    // it stretch across the row.
                    if (themeRows[rowIndex].size == 1) Spacer(Modifier.weight(1f))
                }
            }

            // -- Langue --------------------------------------------------------
            item { Spacer(Modifier.height(8.dp)) }
            item {
                SectionTitle(
                    stringResource(R.string.set_look_section_language),
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            item {
                Segmented(
                    options = languageLabels,
                    selectedIndex = languageIndex,
                    onSelect = { index ->
                        val tag = LANGUAGE_TAGS[index]
                        AppCompatDelegate.setApplicationLocales(
                            if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList()
                            else LocaleListCompat.forLanguageTags(tag),
                        )
                    },
                )
            }
            item {
                Text(
                    stringResource(R.string.set_look_language_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            // -- Unités d’affichage ---------------------------------------------
            item { Spacer(Modifier.height(8.dp)) }
            item {
                ListRow(
                    title = stringResource(R.string.set_look_units_row),
                    subtitle = estradiolUnit
                        ?.let { stringResource(R.string.set_look_units_sub_fmt, it) }
                        ?: stringResource(R.string.set_look_units_sub_none),
                    leading = {
                        IconTile(container = MaterialTheme.colorScheme.primaryContainer) {
                            Icon(
                                Icons.Filled.Straighten,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    },
                    onClick = onOpenHormoneUnits,
                )
            }
        }
    }
}

/**
 * A tiny screenshot of a palette: its surface, its three accent roles and two
 * simulated text lines. Colours come from the palette being previewed, not
 * from the active theme — that is the whole point of the card.
 */
@Composable
private fun ThemeSwatchCard(
    theme: AppTheme,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = resolveScheme(theme, systemInDark = false)
    val activeLabel = stringResource(R.string.set_look_theme_selected)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .semantics {
                contentDescription =
                    if (selected) "${theme.displayName} · $activeLabel" else theme.displayName
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.5f)
                .clip(RoundedCornerShape(18.dp))
                .background(scheme.background)
                .padding(2.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(18.dp))
                    .background(scheme.surface)
                    .padding(12.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(scheme.primary),
                    )
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(scheme.secondary),
                    )
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(scheme.tertiary),
                    )
                }
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(scheme.onSurface.copy(alpha = 0.7f)),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.6f)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(scheme.onSurfaceVariant.copy(alpha = 0.5f)),
                    )
                }
                if (selected) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(scheme.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = scheme.onPrimary,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .padding(top = 4.dp)
                .fillMaxWidth(),
        ) {
            // The selection is spelled out by the check glyph above; this dot
            // only mirrors it, it never carries the state on its own.
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                    ),
            )
            Text(
                theme.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
