package com.douxev.eggshell.ui.sport

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
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
import com.douxev.eggshell.ui.components.ActionBand
import com.douxev.eggshell.ui.components.CardVariant
import com.douxev.eggshell.ui.components.EggCard
import com.douxev.eggshell.ui.components.EggFab
import com.douxev.eggshell.ui.components.EmptyState
import com.douxev.eggshell.ui.components.SectionTitle
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uniffi.transition.SportActivity
import uniffi.transition.SportSession

@HiltViewModel
class SportViewModel @Inject constructor(
    private val repo: SportRepository,
    private val prefs: com.douxev.eggshell.data.SportPrefs,
    private val counter: com.douxev.eggshell.data.StepCounter,
    private val importer: com.douxev.eggshell.data.watch.WatchImporter,
) : ViewModel() {

    val pedometerOn: StateFlow<Boolean> = prefs.pedometer
    val dailyGoal: StateFlow<Int> = prefs.dailyGoal

    /** False on a phone with no step-counting hardware — the toggle is hidden. */
    val pedometerAvailable: Boolean get() = counter.isAvailable

    private val _month = MutableStateFlow(java.time.YearMonth.now())
    val month: StateFlow<java.time.YearMonth> = _month.asStateFlow()

    private val _steps = MutableStateFlow<Map<java.time.LocalDate, Long>>(emptyMap())
    val steps: StateFlow<Map<java.time.LocalDate, Long>> = _steps.asStateFlow()

    private val _dashboard = MutableStateFlow(Dashboard())
    val dashboard: StateFlow<Dashboard> = _dashboard.asStateFlow()

    /** What the header card shows. All of it derived, none of it stored. */
    data class Dashboard(
        val weekSessions: Int = 0,
        val weekMinutes: Long = 0,
        val currentStreak: Int = 0,
        val longestStreak: Int = 0,
        val todaySteps: Long = 0,
    )

    private val _importPreview =
        MutableStateFlow<com.douxev.eggshell.data.watch.WatchImporter.Preview?>(null)
    val importPreview: StateFlow<com.douxev.eggshell.data.watch.WatchImporter.Preview?> =
        _importPreview.asStateFlow()

    /** Non-null once an import finished: how many sessions it wrote. */
    private val _importResult = MutableStateFlow<Int?>(null)
    val importResult: StateFlow<Int?> = _importResult.asStateFlow()

    fun dismissImport() { _importPreview.value = null }
    fun dismissImportResult() { _importResult.value = null }

    /** Read the file and show what it holds. Writes nothing. */
    fun previewImport(uri: android.net.Uri) {
        viewModelScope.launch {
            _importPreview.value = runCatching { importer.preview(uri) }
                .getOrElse {
                    com.douxev.eggshell.data.watch.WatchImporter.Preview(emptyList(), emptySet())
                }
        }
    }

    fun confirmImport() {
        val preview = _importPreview.value ?: return
        _importPreview.value = null
        viewModelScope.launch {
            val types = runCatching { repo.activities() }.getOrDefault(emptyList())
            _importResult.value = runCatching { importer.import(preview.importable, types) }
                .getOrDefault(0)
            refresh()
        }
    }

    fun setPedometer(enabled: Boolean) {
        prefs.setPedometer(enabled)
        // Drop the baseline when switching off, so switching back on later does
        // not credit the whole interval in between as one enormous day.
        if (!enabled) counter.reset() else syncSteps()
    }

    fun setGoal(steps: Int) = prefs.setDailyGoal(steps)

    fun showMonth(month: java.time.YearMonth) {
        _month.value = month
        viewModelScope.launch { loadSteps() }
    }

    /**
     * Read the hardware counter and credit the delta to today.
     *
     * Only ever adds. The counter is cumulative since boot and this is the only
     * place that turns it into a day, so the reboot rule in
     * [com.douxev.eggshell.data.StepCounter.consume] and the max-only write in
     * the core are both guarding the same thing from opposite ends.
     */
    fun syncSteps() {
        if (!prefs.pedometer.value) return
        viewModelScope.launch {
            val delta = runCatching { counter.readDelta() }.getOrNull() ?: return@launch
            if (delta > 0) {
                val today = counter.today()
                val current = runCatching { repo.stepDay(today)?.steps }.getOrNull() ?: 0L
                runCatching { repo.recordSteps(today, current + delta) }
            }
            loadSteps()
        }
    }

    private suspend fun loadSteps() {
        val month = _month.value
        val days = runCatching { repo.stepDays(month.atDay(1), month.atEndOfMonth()) }
            .getOrDefault(emptyList())
        _steps.value = days.mapNotNull { d ->
            runCatching { java.time.LocalDate.parse(d.dayKey) to d.steps }.getOrNull()
        }.toMap()
        _dashboard.value = _dashboard.value.copy(
            todaySteps = runCatching { repo.stepDay(counter.today())?.steps }.getOrNull() ?: 0L,
        )
    }

    private val _sessions = MutableStateFlow<List<SportSession>>(emptyList())
    val sessions: StateFlow<List<SportSession>> = _sessions.asStateFlow()

    /**
     * The activity catalogue indexed by id, so a session row can name its type
     * without a query per row.
     *
     * Archived types included: a session logged under a type that was later
     * archived still belongs to it, and relabelling it "sans type" would be the
     * app lying about the user\'s own history.
     */
    private val _activities = MutableStateFlow<Map<Long, SportActivity>>(emptyMap())
    val activities: StateFlow<Map<Long, SportActivity>> = _activities.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _activities.value = runCatching { repo.activities(includeArchived = true) }
                .getOrDefault(emptyList())
                .associateBy { it.id }
            val sessions = runCatching { repo.sessions(limit = HISTORY_DEPTH) }
                .getOrDefault(emptyList())
            _sessions.value = sessions
            _dashboard.value = buildDashboard(sessions)
            _loading.value = false
            loadSteps()
        }
        syncSteps()
    }

    private fun buildDashboard(sessions: List<SportSession>): Dashboard {
        val zone = java.time.ZoneId.systemDefault()
        val today = java.time.LocalDate.now(zone)
        // The week starts where the user's locale says it does, not on a fixed
        // Monday: "this week" that disagrees with their calendar is a number
        // they cannot check.
        val firstDayOfWeek = java.time.temporal.WeekFields
            .of(java.util.Locale.getDefault()).firstDayOfWeek
        val weekStart = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(firstDayOfWeek))
        val fromMs = weekStart.atStartOfDay(zone).toInstant().toEpochMilli()
        val toMs = weekStart.plusWeeks(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val days = SportStats.sessionDays(sessions, zone)
        return Dashboard(
            weekSessions = SportStats.countBetween(sessions, fromMs, toMs),
            weekMinutes = SportStats.minutesBetween(sessions, fromMs, toMs),
            currentStreak = SportStats.currentStreak(days, today),
            longestStreak = SportStats.longestStreak(days),
            todaySteps = _dashboard.value.todaySteps,
        )
    }

    private companion object {
        /**
         * How far back the dashboard reads. Bounded because the streak walk is
         * over a set built from this list, and an unbounded read would grow with
         * every session ever logged for a number that only looks a few weeks
         * back in practice.
         */
        const val HISTORY_DEPTH = 1_000L
    }
}

/**
 * Sport — the sessions, newest first.
 *
 * The dashboard (streaks, weekly totals, the step calendar) lands on top of
 * this list in a later step. The list comes first because it is what the module
 * is for, and what makes any of the rest mean anything.
 */
@Composable
fun SportScreen(
    onBack: () -> Unit,
    onAddSession: () -> Unit,
    onOpenSession: (Long) -> Unit,
    onManageActivities: () -> Unit,
    vm: SportViewModel = hiltViewModel(),
) {
    val sessions by vm.sessions.collectAsState()
    val activities by vm.activities.collectAsState()
    val loading by vm.loading.collectAsState()
    val dashboard by vm.dashboard.collectAsState()
    val pedometerOn by vm.pedometerOn.collectAsState()
    val goal by vm.dailyGoal.collectAsState()
    val month by vm.month.collectAsState()
    val steps by vm.steps.collectAsState()
    var pickedDay by remember { mutableStateOf<java.time.LocalDate?>(null) }

    val context = androidx.compose.ui.platform.LocalContext.current
    // ACTIVITY_RECOGNITION is a runtime permission from API 29. Below that the
    // counter needs none, so asking would present a dialog the system would
    // refuse to show.
    val needsPermission = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q &&
        androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACTIVITY_RECOGNITION,
        ) != android.content.pm.PackageManager.PERMISSION_GRANTED
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        // Denied leaves the toggle off rather than on-and-silent: a pedometer
        // that is "on" and counts nothing is worse than one that is plainly off.
        vm.setPedometer(granted)
    }

    val importPreview by vm.importPreview.collectAsState()
    val importResult by vm.importResult.collectAsState()
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(vm::previewImport) }

    importPreview?.let { preview ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { vm.dismissImport() },
            title = { Text(stringResource(R.string.sport_import_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    when {
                        preview.workouts.isEmpty() -> Text(stringResource(R.string.sport_import_empty))
                        preview.importable.isEmpty() -> Text(stringResource(R.string.sport_import_all_dupes))
                        else -> {
                            Text(
                                androidx.compose.ui.res.pluralStringResource(
                                    R.plurals.sport_import_found,
                                    preview.importable.size,
                                    preview.importable.size,
                                )
                            )
                            preview.importable.take(IMPORT_PREVIEW_ROWS).forEach { w ->
                                Text(
                                    formatWhen(w.startedMs) + " · " + formatDuration(w.durationS),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (preview.duplicates.isNotEmpty()) {
                                Text(
                                    androidx.compose.ui.res.pluralStringResource(
                                        R.plurals.sport_import_skipped,
                                        preview.duplicates.size,
                                        preview.duplicates.size,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (preview.importable.isNotEmpty()) {
                    TextButton(onClick = { vm.confirmImport() }) {
                        Text(stringResource(R.string.sport_import_confirm))
                    }
                } else {
                    TextButton(onClick = { vm.dismissImport() }) {
                        Text(stringResource(R.string.action_ok))
                    }
                }
            },
            dismissButton = {
                if (preview.importable.isNotEmpty()) {
                    TextButton(onClick = { vm.dismissImport() }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            },
        )
    }

    importResult?.let { count ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { vm.dismissImportResult() },
            text = {
                Text(
                    androidx.compose.ui.res.pluralStringResource(
                        R.plurals.sport_import_done, count, count,
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.dismissImportResult() }) {
                    Text(stringResource(R.string.action_ok))
                }
            },
        )
    }

    pickedDay?.let { day ->
        StepDayDialog(
            day = day,
            steps = steps[day],
            onDismiss = { pickedDay = null },
        )
    }

    // Keyed on Unit but re-entered on every return from the editor, because
    // Navigation Compose disposes a destination it navigates away from.
    LaunchedEffect(Unit) { vm.refresh() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        bottomBar = {
            ActionBand {
                EggFab(
                    icon = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.sport_add),
                    onClick = onAddSession,
                )
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                ScreenHeader(
                    title = stringResource(R.string.sport_title),
                    onBack = onBack,
                    actions = {
                        TextButton(onClick = {
                            // Any MIME type: exporters label .gpx and .tcx as
                            // application/octet-stream, text/xml, application/xml
                            // and application/gpx+xml depending on where the file
                            // came from, and a filter that misses one greys out
                            // the very file the user is trying to pick.
                            importLauncher.launch(arrayOf("*/*"))
                        }) {
                            Text(stringResource(R.string.sport_import))
                        }
                        TextButton(onClick = onManageActivities) {
                            Text(stringResource(R.string.sport_activities_manage))
                        }
                    },
                )
            }

            item {
                DashboardCard(
                    dashboard = dashboard,
                    showSteps = pedometerOn,
                    goal = goal,
                )
            }

            // The step calendar only appears once the pedometer is on: an empty
            // month grid would be a screenful of nothing explaining itself.
            if (pedometerOn) {
                item {
                    StepCalendar(
                        yearMonth = month,
                        stepsByDay = steps,
                        dailyGoal = goal,
                        onPrevMonth = { vm.showMonth(month.minusMonths(1)) },
                        onNextMonth = { vm.showMonth(month.plusMonths(1)) },
                        onPickDay = { pickedDay = it },
                    )
                }
            }

            if (vm.pedometerAvailable) {
                item {
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
                                    stringResource(R.string.sport_pedometer_sub),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            androidx.compose.material3.Switch(
                                checked = pedometerOn,
                                onCheckedChange = { wanted ->
                                    // Ask before enabling, never before: the
                                    // permission dialog is the consequence of a
                                    // choice the user just made, not a greeting.
                                    if (wanted && needsPermission) {
                                        permissionLauncher.launch(
                                            android.Manifest.permission.ACTIVITY_RECOGNITION
                                        )
                                    } else {
                                        vm.setPedometer(wanted)
                                    }
                                },
                            )
                        }
                    }
                }
            }

            item {
                SectionTitle(
                    stringResource(R.string.sport_sessions_section),
                    modifier = Modifier.padding(top = 6.dp, start = 4.dp),
                )
            }

            if (!loading && sessions.isEmpty()) {
                item {
                    EmptyState(
                        message = stringResource(R.string.sport_empty),
                        actionLabel = stringResource(R.string.sport_add),
                        onAction = onAddSession,
                    )
                }
            }

            items(sessions, key = { it.id }) { session ->
                SessionRow(
                    session = session,
                    activity = session.activityId?.let { activities[it] },
                    onClick = { onOpenSession(session.id) },
                )
            }
        }
    }
}

/**
 * The header card: this week, the streak, and today's steps.
 *
 * Three numbers, not a chart. A chart of four sessions is a chart of noise, and
 * what someone actually wants on opening the module is whether they have moved
 * this week and whether the run is still alive.
 */
@Composable
private fun DashboardCard(
    dashboard: SportViewModel.Dashboard,
    showSteps: Boolean,
    goal: Int,
) {
    EggCard(variant = CardVariant.Primary) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Stat(
                value = dashboard.weekSessions.toString(),
                label = stringResource(R.string.sport_stat_week_sessions),
            )
            Stat(
                value = formatDuration(dashboard.weekMinutes * 60),
                label = stringResource(R.string.sport_stat_week_time),
            )
            Stat(
                value = dashboard.currentStreak.toString(),
                label = stringResource(R.string.sport_stat_streak),
            )
        }
        if (showSteps) {
            Text(
                stringResource(
                    R.string.sport_stat_steps_today,
                    dashboard.todaySteps.toInt(),
                    goal,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
        if (dashboard.longestStreak > dashboard.currentStreak) {
            Text(
                stringResource(R.string.sport_stat_best_streak, dashboard.longestStreak),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** What a tapped calendar day says. Read-only: correcting a day is a separate
 *  deliberate act, and a stray tap must not be able to rewrite one. */
@Composable
private fun StepDayDialog(
    day: java.time.LocalDate,
    steps: Long?,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                java.time.format.DateTimeFormatter
                    .ofLocalizedDate(java.time.format.FormatStyle.FULL)
                    .format(day)
            )
        },
        text = {
            Text(
                if (steps == null || steps == 0L) stringResource(R.string.sport_steps_none)
                else stringResource(R.string.sport_steps_count, steps.toInt())
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) }
        },
    )
}

@Composable
private fun SessionRow(
    session: SportSession,
    activity: SportActivity?,
    onClick: () -> Unit,
) {
    EggCard(variant = CardVariant.Low, onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    // A session whose type was deleted keeps everything else,
                    // and is labelled honestly rather than hidden.
                    activity?.name ?: stringResource(R.string.sport_no_activity),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    formatWhen(session.startedMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                session.freeText?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    formatDuration(session.durationS),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                // Only when there is one. Most sessions have no distance, and
                // a blank line under every strength set is noise.
                session.distanceM?.let {
                    Text(
                        formatDistance(it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                session.avgHr?.let {
                    Text(
                        stringResource(R.string.sport_hr_avg, it.toInt()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
fun formatWhen(atMs: Long): String {
    val zone = ZoneId.systemDefault()
    return remember(atMs) {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .format(Instant.ofEpochMilli(atMs).atZone(zone))
    }
}

/** How many workouts a preview lists before it just gives the count. */
private const val IMPORT_PREVIEW_ROWS = 4

/** "5,0 km" or "800 m". Kilometres from one on, which is where people switch. */
@Composable
fun formatDistance(metres: Double): String =
    if (metres >= 1_000) stringResource(R.string.sport_distance_km, metres / 1_000)
    else stringResource(R.string.sport_distance_m, metres.toInt())

/** "1 h 05" or "45 min". Never seconds — nobody logs a session to the second. */
@Composable
fun formatDuration(durationS: Long): String {
    val minutes = durationS / 60
    return if (minutes >= 60) {
        stringResource(R.string.sport_duration_hm, (minutes / 60).toInt(), (minutes % 60).toInt())
    } else {
        stringResource(R.string.sport_duration_m, minutes.toInt())
    }
}
