package com.douxev.eggshell.ui.sport

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.text.KeyboardOptions
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.douxev.eggshell.R
import com.douxev.eggshell.data.SportRepository
import com.douxev.eggshell.ui.common.DateTimePickerField
import com.douxev.eggshell.ui.common.ScreenHeader
import com.douxev.eggshell.ui.components.ActionBand
import com.douxev.eggshell.ui.components.EggCard
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uniffi.transition.SportActivity

@HiltViewModel
class SportSessionEditorViewModel @Inject constructor(
    private val repo: SportRepository,
    private val heartRate: com.douxev.eggshell.data.watch.HeartRateMonitor,
) : ViewModel() {

    val heartRateState = heartRate.state

    /** Whether to offer connecting a sensor at all. */
    val heartRateSupported: Boolean get() = heartRate.isSupported
    val heartRatePermissions: List<String> get() = heartRate.requiredPermissions

    fun connectHeartRate() = heartRate.start()

    fun disconnectHeartRate() = heartRate.stop()

    override fun onCleared() {
        // Leaving the editor drops the connection. A GATT link left open would
        // keep the radio busy and the sensor awake for as long as the process
        // lived, which is a battery cost the user never asked for.
        heartRate.stop()
        super.onCleared()
    }

    private val _activities = MutableStateFlow<List<SportActivity>>(emptyList())
    val activities: StateFlow<List<SportActivity>> = _activities.asStateFlow()

    private val _activityId = MutableStateFlow<Long?>(null)
    val activityId: StateFlow<Long?> = _activityId.asStateFlow()

    private val _startedMs = MutableStateFlow(System.currentTimeMillis())
    val startedMs: StateFlow<Long> = _startedMs.asStateFlow()

    /** Minutes, as typed. Kept as text so a half-entered "4" is not read as 4. */
    private val _minutes = MutableStateFlow("")
    val minutes: StateFlow<String> = _minutes.asStateFlow()

    private val _note = MutableStateFlow("")
    val note: StateFlow<String> = _note.asStateFlow()

    /**
     * Why the last save failed. Surfaced rather than swallowed, for the reason
     * the note editor learned the hard way: a write that fails silently is
     * indistinguishable from one that worked, and the user only finds out later
     * that the session is not there.
     */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun dismissError() { _error.value = null }

    private var sessionId: Long? = null
    val canDelete: Boolean get() = sessionId != null

    /**
     * The distance of the session being edited, if it had one.
     *
     * Held and written back untouched. The editor has no distance field — it is
     * a number a watch measured, not one anyone types — and an update that did
     * not carry it forward would silently erase it the first time someone fixed
     * a typo in the note.
     */
    private var distanceM: Double? = null

    /** Same rule as [distanceM]: measured, not typed, and never erased by an edit. */
    private var avgHr: Long? = null
    private var maxHr: Long? = null

    fun load(id: Long?) {
        sessionId = id
        // A new session starts from nothing; an existing one keeps what it was
        // recorded with and is not overwritten by a sensor connected now.
        if (id == null) heartRate.resetSummary()
        viewModelScope.launch {
            // Only unarchived types are offered for a NEW session; an existing
            // one keeps whatever it was logged under, even if archived since.
            _activities.value = runCatching { repo.activities(includeArchived = id != null) }
                .getOrDefault(emptyList())
            if (id == null) return@launch
            repo.session(id)?.let { s ->
                _activityId.value = s.activityId
                _startedMs.value = s.startedMs
                _minutes.value = (s.durationS / 60).toString()
                _note.value = s.freeText.orEmpty()
                distanceM = s.distanceM
                avgHr = s.avgHr
                maxHr = s.maxHr
            }
        }
    }

    fun onActivity(id: Long?) { _activityId.value = id }
    fun onStarted(ms: Long) { _startedMs.value = ms }
    fun onMinutes(v: String) { _minutes.value = v.filter(Char::isDigit).take(4) }
    fun onNote(v: String) { _note.value = v }

    /** True when there is a duration to save. A session with no length is not one. */
    val canSave: Boolean get() = (_minutes.value.toLongOrNull() ?: 0L) > 0L

    suspend fun save(): Boolean {
        val minutes = _minutes.value.toLongOrNull() ?: return false
        if (minutes <= 0) return false
        val id = sessionId
        // Only for a session recorded here and now. Re-opening an old session
        // with a strap on must not rewrite the heart rate it was logged with.
        if (id == null) {
            heartRate.averageBpm?.let { avgHr = it.toLong() }
            heartRate.maxBpm?.let { maxHr = it.toLong() }
        }
        val outcome = runCatching {
            if (id == null) {
                repo.addSession(
                    activityId = _activityId.value,
                    startedMs = _startedMs.value,
                    durationS = minutes * 60,
                    note = _note.value,
                    distanceM = distanceM,
                    avgHr = avgHr,
                    maxHr = maxHr,
                ).also { sessionId = it.id }
            } else {
                repo.updateSession(
                    id = id,
                    activityId = _activityId.value,
                    startedMs = _startedMs.value,
                    durationS = minutes * 60,
                    note = _note.value,
                    distanceM = distanceM,
                    avgHr = avgHr,
                    maxHr = maxHr,
                )
            }
        }
        outcome.exceptionOrNull()?.let {
            _error.value = "${it::class.java.simpleName}: ${it.message ?: "no detail"}"
        }
        return outcome.isSuccess
    }

    fun delete(onDone: () -> Unit) {
        val id = sessionId ?: return onDone()
        viewModelScope.launch {
            runCatching { repo.deleteSession(id) }
            onDone()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SportSessionEditorScreen(
    sessionId: Long?,
    onBack: () -> Unit,
    vm: SportSessionEditorViewModel = hiltViewModel(),
) {
    val activities by vm.activities.collectAsState()
    val activityId by vm.activityId.collectAsState()
    val startedMs by vm.startedMs.collectAsState()
    val minutes by vm.minutes.collectAsState()
    val note by vm.note.collectAsState()
    val error by vm.error.collectAsState()
    val hrState by vm.heartRateState.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }

    val hrPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        // All of them, or none: a scan permission without a connect permission
        // finds sensors it cannot then talk to.
        if (granted.values.all { it }) vm.connectHeartRate()
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(sessionId) { vm.load(sessionId) }

    val leave = {
        scope.launch {
            // Nothing typed: leaving is not a failure, and must not write an
            // empty session. Something typed but unwritable: stay, so the dialog
            // can say why and the text is still there.
            if (!vm.canSave || vm.save()) onBack()
        }
        Unit
    }

    // The back gesture and the header arrow do the same thing, which is the
    // rule the note editor had to be taught.
    androidx.activity.compose.BackHandler { leave() }

    error?.let { reason ->
        AlertDialog(
            onDismissRequest = { vm.dismissError() },
            title = { Text(stringResource(R.string.notes_save_failed_title)) },
            text = { Text(stringResource(R.string.notes_save_failed_body, reason)) },
            confirmButton = {
                TextButton(onClick = { vm.dismissError() }) { Text(stringResource(R.string.action_ok)) }
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.sport_session_delete_title)) },
            text = { Text(stringResource(R.string.sport_session_delete_body)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.delete(onBack) }) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        bottomBar = {
            if (vm.canDelete) {
                ActionBand {
                    TextButton(onClick = { confirmDelete = true }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            stringResource(R.string.action_delete),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ScreenHeader(
                    title = stringResource(
                        if (sessionId == null) R.string.sport_session_new
                        else R.string.sport_session_edit
                    ),
                    onBack = { leave() },
                )
            }

            item {
                EggCard {
                    Text(
                        stringResource(R.string.sport_field_activity),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = activityId == null,
                            onClick = { vm.onActivity(null) },
                            label = { Text(stringResource(R.string.sport_no_activity)) },
                        )
                        activities.forEach { a ->
                            FilterChip(
                                selected = activityId == a.id,
                                onClick = { vm.onActivity(a.id) },
                                label = { Text(a.name) },
                            )
                        }
                    }
                }
            }

            item {
                DateTimePickerField(
                    label = stringResource(R.string.sport_field_when),
                    atMs = startedMs,
                    onChange = vm::onStarted,
                    modifier = Modifier.fillMaxWidth(),
                    // A session you have not done yet is not a session. The
                    // picker clamps rather than letting one be back-dated
                    // forwards by accident.
                    allowFuture = false,
                )
            }

            item {
                OutlinedTextField(
                    value = minutes,
                    onValueChange = vm::onMinutes,
                    label = { Text(stringResource(R.string.sport_field_duration)) },
                    suffix = { Text(stringResource(R.string.sport_duration_m, 0).substringAfter(' ')) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    isError = minutes.isNotEmpty() && !vm.canSave,
                    supportingText = {
                        if (minutes.isNotEmpty() && !vm.canSave) {
                            Text(stringResource(R.string.sport_duration_required))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Only for a session being logged now: connecting a sensor while
            // editing last week's run would measure nothing about it.
            if (sessionId == null && vm.heartRateSupported) {
                item { HeartRateCard(
                    state = hrState,
                    onConnect = {
                        val missing = vm.heartRatePermissions.filter {
                            androidx.core.content.ContextCompat.checkSelfPermission(context, it) !=
                                android.content.pm.PackageManager.PERMISSION_GRANTED
                        }
                        if (missing.isEmpty()) vm.connectHeartRate()
                        else hrPermissionLauncher.launch(missing.toTypedArray())
                    },
                    onDisconnect = vm::disconnectHeartRate,
                ) }
            }

            item {
                OutlinedTextField(
                    value = note,
                    onValueChange = vm::onNote,
                    label = { Text(stringResource(R.string.sport_field_note)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp),
                )
            }
        }
    }
}

/**
 * Connect a heart-rate sensor, and show what it is saying.
 *
 * Deliberately a card the user has to press rather than a scan that starts with
 * the screen: a Bluetooth scan is a radio cost and a permission prompt, and
 * most sessions are typed in without a strap anywhere near.
 */
@Composable
private fun HeartRateCard(
    state: com.douxev.eggshell.data.watch.HeartRateMonitor.State,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    EggCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.sport_hr_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    heartRateLabel(state),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (state) {
                is com.douxev.eggshell.data.watch.HeartRateMonitor.State.Live,
                is com.douxev.eggshell.data.watch.HeartRateMonitor.State.Waiting,
                is com.douxev.eggshell.data.watch.HeartRateMonitor.State.Connecting,
                is com.douxev.eggshell.data.watch.HeartRateMonitor.State.Scanning ->
                    TextButton(onClick = onDisconnect) {
                        Text(stringResource(R.string.sport_hr_disconnect))
                    }
                else -> TextButton(onClick = onConnect) {
                    Text(stringResource(R.string.sport_hr_connect))
                }
            }
        }
    }
}

/** One line saying exactly where the connection has got to, or what went wrong. */
@Composable
private fun heartRateLabel(
    state: com.douxev.eggshell.data.watch.HeartRateMonitor.State,
): String {
    return when (state) {
        is com.douxev.eggshell.data.watch.HeartRateMonitor.State.Idle ->
            stringResource(R.string.sport_hr_idle)
        is com.douxev.eggshell.data.watch.HeartRateMonitor.State.Scanning ->
            stringResource(R.string.sport_hr_scanning)
        is com.douxev.eggshell.data.watch.HeartRateMonitor.State.Connecting ->
            stringResource(R.string.sport_hr_connecting, state.name.orEmpty())
        is com.douxev.eggshell.data.watch.HeartRateMonitor.State.Waiting ->
            stringResource(R.string.sport_hr_waiting)
        is com.douxev.eggshell.data.watch.HeartRateMonitor.State.Live ->
            stringResource(R.string.sport_hr_live, state.bpm)
        is com.douxev.eggshell.data.watch.HeartRateMonitor.State.Failed -> stringResource(
            when (state.reason) {
                com.douxev.eggshell.data.watch.HeartRateMonitor.Reason.BLUETOOTH_OFF ->
                    R.string.sport_hr_bluetooth_off
                com.douxev.eggshell.data.watch.HeartRateMonitor.Reason.NO_PERMISSION ->
                    R.string.sport_hr_no_permission
                com.douxev.eggshell.data.watch.HeartRateMonitor.Reason.NOT_FOUND ->
                    R.string.sport_hr_not_found
                com.douxev.eggshell.data.watch.HeartRateMonitor.Reason.DISCONNECTED ->
                    R.string.sport_hr_disconnected
                com.douxev.eggshell.data.watch.HeartRateMonitor.Reason.UNSUPPORTED ->
                    R.string.sport_hr_unsupported
            }
        )
    }
}
