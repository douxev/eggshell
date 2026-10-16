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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.douxev.eggshell.R
import com.douxev.eggshell.data.SportPrefs
import com.douxev.eggshell.data.SportRepository
import com.douxev.eggshell.data.StepCounter
import com.douxev.eggshell.ui.common.ScreenHeader
import com.douxev.eggshell.ui.components.CardVariant
import com.douxev.eggshell.ui.components.EggCard
import com.douxev.eggshell.ui.components.SectionTitle
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
    private val prefs: SportPrefs,
    private val counter: StepCounter,
    private val stepSync: com.douxev.eggshell.data.StepSync,
) : ViewModel() {

    private val _activities = MutableStateFlow<List<SportActivity>>(emptyList())
    val activities: StateFlow<List<SportActivity>> = _activities.asStateFlow()

    val pedometerOn: StateFlow<Boolean> = prefs.pedometer
    val dailyGoal: StateFlow<Int> = prefs.dailyGoal

    /** False on a phone with no step-counting hardware — the switch says so. */
    val pedometerAvailable: Boolean get() = counter.isAvailable

    /**
     * Re-read on every resume: the permission can be withdrawn from system
     * settings at any time, and a pedometer that is "on" while counting
     * nothing must say so rather than sit there looking fine.
     */
    private val _permission = MutableStateFlow(counter.hasPermission)
    val permission: StateFlow<Boolean> = _permission.asStateFlow()
    fun recheckPermission() { _permission.value = counter.hasPermission }

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

    /**
     * Switch counting on or off. On starts the background readings and takes
     * the first one straight away, so the baseline exists from this moment;
     * off stops them and drops the baseline (switching back on later must not
     * credit the whole interval in between) but keeps what was counted.
     */
    fun setPedometer(enabled: Boolean) {
        prefs.setPedometer(enabled)
        recheckPermission()
        viewModelScope.launch {
            runCatching { if (enabled) stepSync.enable() else stepSync.disable() }
        }
    }

    fun setGoal(steps: Int) = prefs.setDailyGoal(steps)
}

/**
 * The module's settings: the pedometer and its goal, the Health Connect
 * import, then the activity catalogue.
 *
 * The pedometer lives here rather than on the dashboard because it is a
 * setting one flips once, and a switch with three lines of explanation was
 * taking a card of the module's front page for the rest of its life.
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
    val pedometerOn by vm.pedometerOn.collectAsState()
    val goal by vm.dailyGoal.collectAsState()
    var newName by remember { mutableStateOf("") }
    var newKind by remember { mutableStateOf(SportKind.Cardio) }
    var pendingDelete by remember { mutableStateOf<SportActivity?>(null) }

    LaunchedEffect(Unit) { vm.refresh() }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        vm.recheckPermission()
        onPauseOrDispose { }
    }
    val permitted by vm.permission.collectAsState()

    val context = androidx.compose.ui.platform.LocalContext.current
    // ACTIVITY_RECOGNITION is a runtime permission from API 29. Below that the
    // counter needs none, so asking would present a dialog the system would
    // refuse to show.
    val needsPermission = !permitted
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        // Denied leaves the toggle off rather than on-and-silent: a pedometer
        // that is "on" and counts nothing is worse than one that is plainly off.
        vm.setPedometer(granted)
    }
    // Once refused for good, the system no longer shows the dialog; the app's
    // settings page is the only place left to grant it.
    val activity = context as? android.app.Activity
    val askOrOpenSettings: () -> Unit = {
        val canAsk = activity == null || !vm.pedometerOn.value ||
            activity.shouldShowRequestPermissionRationale(android.Manifest.permission.ACTIVITY_RECOGNITION)
        if (canAsk) {
            permissionLauncher.launch(android.Manifest.permission.ACTIVITY_RECOGNITION)
        } else {
            runCatching {
                context.startActivity(
                    android.content.Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.fromParts("package", context.packageName, null),
                    )
                )
            }
        }
    }

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
                    title = stringResource(R.string.sport_settings_title),
                    onBack = onBack,
                )
            }

            item {
                PedometerCard(
                    available = vm.pedometerAvailable,
                    enabled = pedometerOn,
                    permitted = permitted,
                    onGrant = askOrOpenSettings,
                    goal = goal,
                    onToggle = { wanted ->
                        // Ask before enabling, never before: the permission
                        // dialog is the consequence of a choice the user just
                        // made, not a greeting.
                        if (wanted && needsPermission) {
                            permissionLauncher.launch(
                                android.Manifest.permission.ACTIVITY_RECOGNITION
                            )
                        } else {
                            vm.setPedometer(wanted)
                        }
                    },
                    onGoal = vm::setGoal,
                )
            }

            // Next to the pedometer because it answers the same question —
            // where do the steps and workouts come from — for someone whose
            // watch already records them.
            item {
                com.douxev.eggshell.ui.health.HealthConnectCard()
            }

            item {
                SectionTitle(
                    stringResource(R.string.sport_activities_title),
                    modifier = Modifier.padding(top = 6.dp, start = 4.dp),
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

/**
 * The pedometer switch and, once it is on, the daily goal: four usual values
 * as chips and a field for any other. The field applies as soon as it holds a
 * number the prefs accept, so there is no save button to forget.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PedometerCard(
    available: Boolean,
    enabled: Boolean,
    permitted: Boolean,
    onGrant: () -> Unit,
    goal: Int,
    onToggle: (Boolean) -> Unit,
    onGoal: (Int) -> Unit,
) {
    val numbers = remember { java.text.NumberFormat.getIntegerInstance() }
    // Not keyed on the goal: the field used to be rebuilt every time a
    // keystroke changed the goal, so typing 10000 wiped itself the moment it
    // matched the 10 000 chip — and saved 1000 on the way there.
    var custom by remember { mutableStateOf(if (goal in GOAL_PRESETS) "" else goal.toString()) }
    fun commitCustom() {
        custom.toIntOrNull()
            ?.takeIf { it in SportPrefs.MIN_GOAL..SportPrefs.MAX_GOAL }
            ?.let(onGoal)
    }
    EggCard(variant = CardVariant.Low) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.sport_pedometer),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(
                        if (available) R.string.sport_pedometer_sub
                        else R.string.sport_pedometer_unavailable
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = enabled && available,
                enabled = available,
                onCheckedChange = onToggle,
            )
        }
        if (enabled && available && !permitted) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.sport_pedometer_no_permission),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onGrant) { Text(stringResource(R.string.sport_pedometer_grant)) }
            }
        }
        if (enabled && available) {
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.sport_goal_label),
                style = MaterialTheme.typography.labelLarge,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GOAL_PRESETS.forEach { preset ->
                    FilterChip(
                        selected = goal == preset,
                        onClick = { custom = ""; onGoal(preset) },
                        label = {
                            Text(stringResource(R.string.sport_goal_steps, numbers.format(preset)))
                        },
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = custom,
                onValueChange = { typed -> custom = typed.filter(Char::isDigit).take(5) },
                label = { Text(stringResource(R.string.sport_goal_custom)) },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                ),
                // Applied when the number is finished — Done, or leaving the
                // field — never per keystroke.
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onDone = { commitCustom() },
                ),
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { if (!it.isFocused) commitCustom() },
            )
        }
    }
}

/** The goals most people pick; anything else goes in the field. */
private val GOAL_PRESETS = listOf(5_000, 8_000, 10_000, 12_000)
