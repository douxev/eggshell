package com.douxev.eggshell.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bloodtype
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.douxev.eggshell.R
import com.douxev.eggshell.data.FeaturesPrefs
import com.douxev.eggshell.ui.common.ScreenHeader
import com.douxev.eggshell.ui.components.CardVariant
import com.douxev.eggshell.ui.components.EggCard
import com.douxev.eggshell.ui.components.IconTile
import com.douxev.eggshell.ui.components.ListRow
import com.douxev.eggshell.ui.theme.EggColors
import com.douxev.eggshell.ui.theme.EggDim
import com.douxev.eggshell.ui.theme.EggShapes
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

@HiltViewModel
class FeaturesViewModel @Inject constructor(
    private val prefs: FeaturesPrefs,
) : ViewModel() {
    val medications: StateFlow<Boolean> = prefs.medications
    val journal: StateFlow<Boolean> = prefs.journal
    val hormones: StateFlow<Boolean> = prefs.hormones
    val weightTracking: StateFlow<Boolean> = prefs.weightTracking
    val photoTab: StateFlow<Boolean> = prefs.photoTab
    val voiceTab: StateFlow<Boolean> = prefs.voiceTab
    val notes: StateFlow<Boolean> = prefs.notes
    val bleeding: StateFlow<Boolean> = prefs.bleeding
    val appointments: StateFlow<Boolean> = prefs.appointments

    fun setMedications(v: Boolean) = prefs.setMedications(v)
    fun setJournal(v: Boolean) = prefs.setJournal(v)
    fun setHormones(v: Boolean) = prefs.setHormones(v)
    fun setWeightTracking(v: Boolean) = prefs.setWeightTracking(v)
    fun setPhotoTab(v: Boolean) = prefs.setPhotoTab(v)
    fun setVoiceTab(v: Boolean) = prefs.setVoiceTab(v)
    fun setNotes(v: Boolean) = prefs.setNotes(v)
    fun setBleeding(v: Boolean) = prefs.setBleeding(v)
    fun setAppointments(v: Boolean) = prefs.setAppointments(v)
}

/**
 * Porte « Modules » — the eight switches, in family order (§2.2): Traitement,
 * then Ressenti, then Évolution. The tile colour of each family is echoed on
 * the leading icon so the door reads like the launcher it governs.
 *
 * Turning a module off never removes a destination — only its launcher tile.
 * The hint at the top says so, because the old behaviour (tabs appearing and
 * disappearing) is exactly what made the app unpredictable.
 */
@Composable
fun FeaturesScreen(
    onBack: () -> Unit,
    vm: FeaturesViewModel = hiltViewModel(),
) {
    val meds by vm.medications.collectAsState()
    val appointments by vm.appointments.collectAsState()
    val journal by vm.journal.collectAsState()
    val bleeding by vm.bleeding.collectAsState()
    val hormones by vm.hormones.collectAsState()
    val weight by vm.weightTracking.collectAsState()
    val photo by vm.photoTab.collectAsState()
    val voice by vm.voiceTab.collectAsState()
    val notes by vm.notes.collectAsState()

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
            item { ScreenHeader(title = stringResource(R.string.set_door_modules), onBack = onBack) }
            item {
                EggCard(variant = CardVariant.Low) {
                    Text(
                        stringResource(R.string.set_modules_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // -- Famille Traitement ------------------------------------------
            item {
                ModuleRow(
                    icon = Icons.Filled.Medication,
                    family = ModuleFamily.Treatment,
                    title = stringResource(R.string.set_module_meds),
                    subtitle = stringResource(R.string.set_module_meds_sub),
                    checked = meds,
                    onCheckedChange = vm::setMedications,
                )
            }
            item {
                ModuleRow(
                    icon = Icons.Filled.CalendarMonth,
                    family = ModuleFamily.Treatment,
                    title = stringResource(R.string.set_module_appointments),
                    subtitle = stringResource(R.string.set_module_appointments_sub),
                    checked = appointments,
                    onCheckedChange = vm::setAppointments,
                )
            }

            // -- Famille Ressenti --------------------------------------------
            item {
                ModuleRow(
                    icon = Icons.Filled.EditNote,
                    family = ModuleFamily.Feeling,
                    title = stringResource(R.string.set_module_journal),
                    subtitle = stringResource(R.string.set_module_journal_sub),
                    checked = journal,
                    onCheckedChange = vm::setJournal,
                )
            }
            item {
                ModuleRow(
                    icon = Icons.Filled.Bloodtype,
                    family = ModuleFamily.Feeling,
                    title = stringResource(R.string.set_module_bleeding),
                    subtitle = stringResource(R.string.set_module_bleeding_sub),
                    checked = bleeding,
                    onCheckedChange = vm::setBleeding,
                )
            }

            // -- Famille Évolution -------------------------------------------
            item {
                ModuleRow(
                    icon = Icons.Filled.Science,
                    family = ModuleFamily.Evolution,
                    title = stringResource(R.string.set_module_labs),
                    subtitle = stringResource(R.string.set_module_labs_sub),
                    checked = hormones,
                    onCheckedChange = vm::setHormones,
                )
            }
            item {
                ModuleRow(
                    // `monitor_weight` is absent from the icon set the design
                    // system ships; `straighten` is the agreed substitution.
                    icon = Icons.Filled.Straighten,
                    family = ModuleFamily.Evolution,
                    title = stringResource(R.string.set_module_weight),
                    subtitle = stringResource(R.string.set_module_weight_sub),
                    checked = weight,
                    onCheckedChange = vm::setWeightTracking,
                )
            }
            item {
                ModuleRow(
                    icon = Icons.Filled.PhotoCamera,
                    family = ModuleFamily.Evolution,
                    title = stringResource(R.string.set_module_photos),
                    subtitle = stringResource(R.string.set_module_photos_sub),
                    checked = photo,
                    onCheckedChange = vm::setPhotoTab,
                )
            }
            item {
                ModuleRow(
                    icon = Icons.Filled.GraphicEq,
                    family = ModuleFamily.Evolution,
                    title = stringResource(R.string.set_module_voice),
                    subtitle = stringResource(R.string.set_module_voice_sub),
                    checked = voice,
                    onCheckedChange = vm::setVoiceTab,
                )
            }
            item {
                ModuleRow(
                    icon = Icons.Filled.Description,
                    family = ModuleFamily.Evolution,
                    title = stringResource(R.string.set_module_notes),
                    subtitle = stringResource(R.string.set_module_notes_sub),
                    checked = notes,
                    onCheckedChange = vm::setNotes,
                )
            }

            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

/** The three launcher families (§2.2) — colour code only, never a screen. */
private enum class ModuleFamily { Treatment, Feeling, Evolution }

@Composable
private fun ModuleRow(
    icon: ImageVector,
    family: ModuleFamily,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val container: Color = when (family) {
        ModuleFamily.Treatment -> MaterialTheme.colorScheme.primaryContainer
        ModuleFamily.Feeling -> MaterialTheme.colorScheme.tertiaryContainer
        ModuleFamily.Evolution -> EggColors.evolutionContainer
    }
    val onContainer: Color = when (family) {
        ModuleFamily.Treatment -> MaterialTheme.colorScheme.onPrimaryContainer
        ModuleFamily.Feeling -> MaterialTheme.colorScheme.onTertiaryContainer
        ModuleFamily.Evolution -> EggColors.onEvolutionContainer
    }
    ListRow(
        title = title,
        subtitle = subtitle,
        leading = {
            IconTile(size = 44.dp, shape = EggShapes.IconTile, container = container) {
                Icon(icon, contentDescription = null, tint = onContainer)
            }
        },
        trailing = {
            // The row title is the switch's accessible name: without it
            // TalkBack would announce eight identical "on/off" controls.
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                modifier = Modifier.semantics { contentDescription = title },
            )
        },
    )
}
