package com.douxev.eggshell.ui.hormones

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.douxev.eggshell.R
import com.douxev.eggshell.data.HormoneUnitPrefs

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
 * make clinical sense — e.g. estradiol is reported in pg/mL or pmol/L; SHBG
 * has its own scale. Showing all 7 units everywhere would just confuse.
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HormoneUnitsScreen(
    onBack: () -> Unit,
    vm: HormoneUnitsViewModel = hiltViewModel(),
) {
    val selections by vm.selections.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.hormones_units_title)) },
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
                    stringResource(R.string.hormones_units_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            items(HormoneCatalog.KINDS, key = { it }) { hormone ->
                val units = UNITS_FOR_HORMONE[hormone] ?: return@items
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            HormoneCatalog.kindLabel(hormone),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        val current = selections[hormone] ?: HormoneUnitsViewModel.Choice.Default
                        val defaultUnit = vm.defaultFor(hormone)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = current is HormoneUnitsViewModel.Choice.Default,
                                onClick = { vm.setChoice(hormone, HormoneUnitsViewModel.Choice.Default) },
                                label = {
                                    val suffix = defaultUnit?.let { " · $it" }.orEmpty()
                                    Text(stringResource(R.string.hormones_units_default) + suffix)
                                },
                                shape = RoundedCornerShape(50),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                ),
                            )
                            FilterChip(
                                selected = current is HormoneUnitsViewModel.Choice.AsRecorded,
                                onClick = { vm.setChoice(hormone, HormoneUnitsViewModel.Choice.AsRecorded) },
                                label = { Text(stringResource(R.string.hormones_units_as_recorded)) },
                                shape = RoundedCornerShape(50),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                ),
                            )
                            units.forEach { u ->
                                FilterChip(
                                    selected = current is HormoneUnitsViewModel.Choice.Unit && current.name == u,
                                    onClick = { vm.setChoice(hormone, HormoneUnitsViewModel.Choice.Unit(u)) },
                                    label = { Text(u) },
                                    shape = RoundedCornerShape(50),
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

