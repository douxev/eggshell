package com.douxev.eggshell.ui.sport

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.douxev.eggshell.R
import com.douxev.eggshell.data.SportRepository
import com.douxev.eggshell.ui.common.ScreenHeader
import com.douxev.eggshell.ui.components.CardVariant
import com.douxev.eggshell.ui.components.EggCard
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uniffi.transition.SportActivity

/** The four categories the core recognises. Free-form in SQL, closed here. */
enum class SportKind(val key: String, val labelRes: Int) {
    Cardio("cardio", R.string.sport_kind_cardio),
    Strength("strength", R.string.sport_kind_strength),
    Mobility("mobility", R.string.sport_kind_mobility),
    Other("other", R.string.sport_kind_other);

    companion object {
        fun of(key: String): SportKind = entries.firstOrNull { it.key == key } ?: Other
    }
}

@HiltViewModel
class SportActivitiesViewModel @Inject constructor(
    private val repo: SportRepository,
) : ViewModel() {

    private val _activities = MutableStateFlow<List<SportActivity>>(emptyList())
    val activities: StateFlow<List<SportActivity>> = _activities.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _activities.value = runCatching { repo.activities(includeArchived = true) }
                .getOrDefault(emptyList())
        }
    }

    fun add(name: String, kind: SportKind) {
        if (name.isBlank()) return
        viewModelScope.launch {
            runCatching { repo.addActivity(name.trim(), kind.key, null) }
            refresh()
        }
    }

    fun setArchived(id: Long, archived: Boolean) {
        viewModelScope.launch {
            runCatching { repo.setActivityArchived(id, archived) }
            refresh()
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            runCatching { repo.deleteActivity(id) }
            refresh()
        }
    }
}

/**
 * The activity catalogue.
 *
 * Archiving is offered first and delete second, because they differ in a way
 * that matters: archiving hides a type and leaves every session pointing at it,
 * while deleting detaches them permanently. The core makes deletion safe — the
 * sessions survive with no type rather than cascading away — but "safe" is not
 * "reversible", so the confirmation says exactly what will happen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SportActivitiesScreen(
    onBack: () -> Unit,
    vm: SportActivitiesViewModel = hiltViewModel(),
) {
    val activities by vm.activities.collectAsState()
    var newName by remember { mutableStateOf("") }
    var newKind by remember { mutableStateOf(SportKind.Cardio) }
    var pendingDelete by remember { mutableStateOf<SportActivity?>(null) }

    LaunchedEffect(Unit) { vm.refresh() }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.sport_activity_delete_title)) },
            text = { Text(stringResource(R.string.sport_activity_delete_body)) },
            confirmButton = {
                TextButton(onClick = { pendingDelete = null; vm.delete(target.id) }) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.surface) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                ScreenHeader(
                    title = stringResource(R.string.sport_activities_title),
                    onBack = onBack,
                )
            }

            item {
                EggCard {
                    Text(
                        stringResource(R.string.sport_activity_new),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text(stringResource(R.string.sport_activity_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(R.string.sport_activity_kind),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SportKind.entries.forEach { kind ->
                            FilterChip(
                                selected = newKind == kind,
                                onClick = { newKind = kind },
                                label = { Text(stringResource(kind.labelRes)) },
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    TextButton(
                        onClick = { vm.add(newName, newKind); newName = "" },
                        enabled = newName.isNotBlank(),
                    ) { Text(stringResource(R.string.action_save)) }
                }
            }

            items(activities, key = { it.id }) { activity ->
                EggCard(
                    variant = if (activity.archived) CardVariant.Outlined else CardVariant.Low,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                activity.name,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                stringResource(SportKind.of(activity.kind).labelRes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { vm.setArchived(activity.id, !activity.archived) }) {
                            Text(
                                stringResource(
                                    if (activity.archived) R.string.sport_activity_unarchive
                                    else R.string.sport_activity_archive
                                )
                            )
                        }
                        TextButton(onClick = { pendingDelete = activity }) {
                            Text(
                                stringResource(R.string.action_delete),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}
