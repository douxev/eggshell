package com.douxev.eggshell.ui.hormones

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.douxev.eggshell.R
import com.douxev.eggshell.data.HormoneUnitPrefs
import com.douxev.eggshell.ui.common.ScreenHeader
import com.douxev.eggshell.ui.components.CardVariant
import com.douxev.eggshell.ui.components.EggCard
import com.douxev.eggshell.ui.theme.EggDim
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@HiltViewModel
class HormoneUnitsViewModel @Inject constructor(
    private val prefs: HormoneUnitPrefs,
) : ViewModel() {

    sealed interface Choice {
        /** Use the conventional default for this hormone. */
        data object Default : Choice
        /** Show the value exactly as the user typed it. */
        data object AsRecorded : Choice
        /** Convert to this explicit unit. */
        data class Unit(val name: String) : Choice
    }

    private val _selections = MutableStateFlow(load())
    val selections: StateFlow<Map<String, Choice>> = _selections.asStateFlow()

    fun setChoice(hormone: String, choice: Choice) {
        when (choice) {
            Choice.Default -> prefs.setPreferred(hormone, null)
            Choice.AsRecorded -> prefs.setAsRecorded(hormone)
            is Choice.Unit -> prefs.setPreferred(hormone, choice.name)
        }
        _selections.value = load()
    }

    fun defaultFor(hormone: String): String? = prefs.defaultFor(hormone)

    private fun load(): Map<String, Choice> =
        HormoneCatalog.KINDS.associateWith { h ->
            when {
                prefs.isAsRecorded(h) -> Choice.AsRecorded
                prefs.getExplicit(h) != null -> Choice.Unit(prefs.getExplicit(h)!!)
                else -> Choice.Default
            }
        }
}

/**
 * Unit catalog grouped by hormone. We restrict each hormone to the units that
 * make sense for it — estradiol is reported in pg/mL or pmol/L, SHBG has its
 * own scale. Offering all seven units everywhere would only confuse.
 */
private val UNITS_FOR_HORMONE: Map<String, List<String>> = mapOf(
    "estradiol" to listOf("pg/mL", "pmol/L"),
    "testosterone" to listOf("ng/dL", "nmol/L", "ng/mL"),
    "progesterone" to listOf("ng/mL", "nmol/L"),
    "lh" to listOf("mIU/mL"),
    "fsh" to listOf("mIU/mL"),
    "prolactin" to listOf("ng/mL"),
    "shbg" to listOf("nmol/L"),
    "other" to listOf("pg/mL", "pmol/L", "ng/dL", "nmol/L", "ng/mL", "mIU/mL"),
)

/** Sub-screen of « Apparence & langue »: how a lab value is displayed. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HormoneUnitsScreen(
    onBack: () -> Unit,
    vm: HormoneUnitsViewModel = hiltViewModel(),
) {
    val selections by vm.selections.collectAsState()

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
                ScreenHeader(
                    title = stringResource(R.string.hormones_units_title),
                    onBack = onBack,
                )
            }
            item {
                EggCard(variant = CardVariant.Low) {
                    Text(
                        stringResource(R.string.hormones_units_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(HormoneCatalog.KINDS, key = { it }) { hormone ->
                val units = UNITS_FOR_HORMONE[hormone] ?: return@items
                val current = selections[hormone] ?: HormoneUnitsViewModel.Choice.Default
                val defaultUnit = vm.defaultFor(hormone)
                EggCard(
                    variant = CardVariant.Low,
                    padding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
                ) {
                    Text(
                        HormoneCatalog.kindLabel(hormone),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 10.dp),
                    ) {
                        UnitChip(
                            selected = current is HormoneUnitsViewModel.Choice.Default,
                            label = stringResource(R.string.hormones_units_default) +
                                defaultUnit?.let { " · $it" }.orEmpty(),
                            onClick = {
                                vm.setChoice(hormone, HormoneUnitsViewModel.Choice.Default)
                            },
                        )
                        UnitChip(
                            selected = current is HormoneUnitsViewModel.Choice.AsRecorded,
                            label = stringResource(R.string.hormones_units_as_recorded),
                            onClick = {
                                vm.setChoice(hormone, HormoneUnitsViewModel.Choice.AsRecorded)
                            },
                        )
                        units.forEach { u ->
                            UnitChip(
                                selected = current is HormoneUnitsViewModel.Choice.Unit &&
                                    current.name == u,
                                label = u,
                                onClick = {
                                    vm.setChoice(hormone, HormoneUnitsViewModel.Choice.Unit(u))
                                },
                            )
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

/** Filter chip species: 10 dp radius, distinct from the round period pills (D4). */
@Composable
private fun UnitChip(selected: Boolean, label: String, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        shape = RoundedCornerShape(10.dp),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    )
}
