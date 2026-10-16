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
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.douxev.eggshell.R
import com.douxev.eggshell.data.SportRepository
import com.douxev.eggshell.reminders.LabReminderManager
import com.douxev.eggshell.reminders.LabReminderPrefs
import com.douxev.eggshell.ui.common.ScreenHeader
import com.douxev.eggshell.ui.components.ActionBand
import com.douxev.eggshell.ui.components.CardVariant
import com.douxev.eggshell.ui.components.EggCard
import com.douxev.eggshell.ui.components.EggFab
import com.douxev.eggshell.ui.components.EmptyState
import com.douxev.eggshell.ui.components.IconTile
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
    private val stepSync: com.douxev.eggshell.data.StepSync,
    private val counter: com.douxev.eggshell.data.StepCounter,
    private val health: com.douxev.eggshell.data.health.HealthConnectSync,
    private val importer: com.douxev.eggshell.data.watch.WatchImporter,
    private val reminders: LabReminderManager,
) : ViewModel() {

    val pedometerOn: StateFlow<Boolean> = prefs.pedometer
    val dailyGoal: StateFlow<Int> = prefs.dailyGoal

    private val _month = MutableStateFlow(java.time.YearMonth.now())
    val month: StateFlow<java.time.YearMonth> = _month.asStateFlow()

    private val _steps = MutableStateFlow<Map<java.time.LocalDate, Long>>(emptyMap())
    val steps: StateFlow<Map<java.time.LocalDate, Long>> = _steps.asStateFlow()

    private val _dashboard = MutableStateFlow(Dashboard())
    val dashboard: StateFlow<Dashboard> = _dashboard.asStateFlow()

    /** How many session reminders exist, for the door that leads to them. */
    private val _reminderCount = MutableStateFlow(0)
    val reminderCount: StateFlow<Int> = _reminderCount.asStateFlow()

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

    fun showMonth(month: java.time.YearMonth) {
        _month.value = month
        viewModelScope.launch { loadSteps() }
    }

    /**
     * Take a fresh reading and pour the ledger into the vault, then redraw.
     *
     * The counting itself happens in the background ([com.douxev.eggshell.data.StepSync]);
     * opening the screen only makes sure what it shows is up to the minute.
     */
    fun syncSteps() {
        if (!prefs.pedometer.value) return
        viewModelScope.launch {
            runCatching { stepSync.sampleAndFlush() }
            loadSteps()
        }
    }

    // -- Live steps -------------------------------------------------------------

    private var live: AutoCloseable? = null

    /**
     * Whether the screen wants the live count right now. The listener is
     * registered after a reading, so the screen may already have gone by the
     * time it would be: this is checked then, and a late listener closed at
     * once instead of running for the rest of the ViewModel's life.
     */
    private var liveWanted = false

    /**
     * Follow the counter while the screen is visible: today's figure moves as
     * the person walks. A reading is taken first so the starting point is
     * current, then every sensor event adds its growth since that reading.
     * Nothing is written per step — [stopLive] takes one proper reading.
     */
    fun startLive() {
        if (!prefs.pedometer.value || liveWanted) return
        liveWanted = true
        viewModelScope.launch {
            runCatching { stepSync.sampleAndFlush() }
            loadSteps()
            val (baseRaw, ledgerToday) = runCatching { stepSync.liveBase() }.getOrNull() ?: return@launch
            baseRaw ?: return@launch
            val day = java.time.LocalDate.now()
            // The larger of what the vault and the ledger say for today: a
            // correction or an import may have raised the vault past the ledger.
            val baseToday = maxOf(_dashboard.value.todaySteps, ledgerToday)
            if (!liveWanted || live != null) return@launch
            live = counter.listen { raw ->
                // A counter that went backwards rebooted under us; and past
                // midnight the base belongs to yesterday. Either way, reread.
                if (raw < baseRaw || java.time.LocalDate.now() != day) {
                    viewModelScope.launch { stopLive(); startLive() }
                    return@listen
                }
                val today = baseToday + (raw - baseRaw)
                _dashboard.value = _dashboard.value.copy(todaySteps = today)
                if (java.time.YearMonth.from(day) == _month.value) {
                    _steps.value = _steps.value + (day to today)
                }
            }
        }
    }

    /** The screen is going away: stop following, and take one real reading. */
    fun stopLive() {
        liveWanted = false
        val handle = live ?: return
        live = null
        runCatching { handle.close() }
        viewModelScope.launch { runCatching { stepSync.sampleAndFlush() } }
    }

    override fun onCleared() {
        liveWanted = false
        runCatching { live?.close() }
        live = null
        super.onCleared()
    }

    /**
     * Set a day to exactly what the user says it was.
     *
     * The only way to bring a day down — a phone that spent an afternoon in a
     * tumble dryer — and surfaced rather than swallowed when it fails, since a
     * correction that silently did not happen looks exactly like one that did.
     */
    fun correctDay(day: java.time.LocalDate, value: Long) {
        viewModelScope.launch {
            val ok = runCatching { stepSync.correct(day, value) }.isSuccess
            if (!ok) _stepError.value = true
            loadSteps()
        }
    }

    private val _stepError = MutableStateFlow(false)
    val stepError: StateFlow<Boolean> = _stepError.asStateFlow()
    fun dismissStepError() { _stepError.value = false }

    /** The numbers under the step calendar. All derived, none of it stored. */
    data class StepSummary(
        val month: StepStats.Month = StepStats.Month(0, 0, 0, 0, 0),
        /** This month's daily average against last month's, in percent. */
        val changeVsPrevious: Int? = null,
        val goalStreak: Int = 0,
        val bestGoalStreak: Int = 0,
        val yearTotal: Long = 0,
    )

    private val _stepSummary = MutableStateFlow(StepSummary())
    val stepSummary: StateFlow<StepSummary> = _stepSummary.asStateFlow()

    private suspend fun stepRange(
        from: java.time.LocalDate,
        to: java.time.LocalDate,
    ): Map<java.time.LocalDate, Long> =
        runCatching { repo.stepDays(from, to) }.getOrDefault(emptyList()).mapNotNull { d ->
            runCatching { java.time.LocalDate.parse(d.dayKey) to d.steps }.getOrNull()
        }.toMap()

    private suspend fun loadSteps() {
        val month = _month.value
        val today = java.time.LocalDate.now()
        val goal = prefs.dailyGoal.value
        val shown = stepRange(month.atDay(1), month.atEndOfMonth())
        val before = month.minusMonths(1)
        val previous = stepRange(before.atDay(1), before.atEndOfMonth())
        // A year and a bit back: enough for this year's total and for any
        // streak someone could realistically be on.
        val recent = stepRange(today.minusDays(STEP_WINDOW_DAYS), today)
        _steps.value = shown
        val current = StepStats.month(shown, goal)
        _stepSummary.value = StepSummary(
            month = current,
            changeVsPrevious = StepStats.change(current.average, StepStats.month(previous, goal).average),
            goalStreak = StepStats.goalStreak(recent, goal, today),
            bestGoalStreak = StepStats.bestGoalStreak(recent, goal),
            yearTotal = recent.filterKeys { it.year == today.year }.values.sum(),
        )
        _dashboard.value = _dashboard.value.copy(todaySteps = recent[today] ?: 0L)
    }

    private val _sessions = MutableStateFlow<List<SportSession>>(emptyList())
    val sessions: StateFlow<List<SportSession>> = _sessions.asStateFlow()

    /**
     * The activity catalogue indexed by id, so a session row can name its type
     * without a query per row.
     *
     * Archived types included: a session logged under a type that was later
     * archived still belongs to it, and relabelling it "sans type" would be the
     * app lying about the user's own history.
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
            _reminderCount.value = runCatching {
                reminders.list().count { it.category == LabReminderPrefs.CATEGORY_SPORT }
            }.getOrDefault(0)
            _loading.value = false
            loadSteps()
        }
        syncSteps()
        // Health Connect, when the user turned it on: what their watch recorded
        // since the last sync. Rate-limited and fail-soft on its side; a reload
        // only when it actually brought something in.
        viewModelScope.launch {
            val r = health.syncIfEnabled()
            if (r is com.douxev.eggshell.data.health.HealthConnectSync.Result.Imported &&
                (r.sessions > 0 || r.stepDays > 0)
            ) {
                refresh()
            }
        }
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

        /** How far back the step streak and the year's total look. */
        const val STEP_WINDOW_DAYS = 400L
    }
}

/**
 * Sport — the dashboard, the step calendar, three doors, then the sessions.
 *
 * The doors are rows that say what they open, because the two text buttons
 * they replace did not: « Importer » gave no clue that it wanted a watch's
 * .gpx/.tcx export, and the pedometer switch sat on the dashboard taking a
 * card of its own for a setting one flips once. The switch now lives with the
 * activity types; the reminders get a door of their own, the way a treatment's
 * reminders are reached from its own screen.
 */
@Composable
fun SportScreen(
    onBack: () -> Unit,
    onAddSession: () -> Unit,
    onOpenSession: (Long) -> Unit,
    onManageActivities: () -> Unit,
    onOpenReminders: () -> Unit,
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
    val stepSummary by vm.stepSummary.collectAsState()
    val stepError by vm.stepError.collectAsState()
    val reminderCount by vm.reminderCount.collectAsState()
    var pickedDay by remember { mutableStateOf<java.time.LocalDate?>(null) }

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
                                pluralStringResource(
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
                                    pluralStringResource(
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
                Text(pluralStringResource(R.plurals.sport_import_done, count, count))
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
            onCorrect = { value -> pickedDay = null; vm.correctDay(day, value) },
        )
    }

    if (stepError) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = vm::dismissStepError,
            text = { Text(stringResource(R.string.sport_steps_correct_failed)) },
            confirmButton = {
                TextButton(onClick = vm::dismissStepError) { Text(stringResource(R.string.action_ok)) }
            },
        )
    }

    // Keyed on Unit but re-entered on every return from the editor, because
    // Navigation Compose disposes a destination it navigates away from.
    LaunchedEffect(Unit) { vm.refresh() }
    // Steps move live while this screen is in front of the user, and only then.
    androidx.lifecycle.compose.LifecycleResumeEffect(pedometerOn) {
        vm.startLive()
        onPauseOrDispose { vm.stopLive() }
    }

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
                item { StepSummaryCard(summary = stepSummary, goal = goal) }
            }

            item {
                LinkRow(
                    icon = Icons.Filled.Notifications,
                    title = stringResource(R.string.sport_link_reminders),
                    subtitle = if (reminderCount == 0) {
                        stringResource(R.string.sport_link_reminders_none)
                    } else {
                        pluralStringResource(
                            R.plurals.sport_link_reminders_count, reminderCount, reminderCount,
                        )
                    },
                    onClick = onOpenReminders,
                )
            }
            item {
                LinkRow(
                    icon = Icons.Filled.FileDownload,
                    title = stringResource(R.string.sport_link_import),
                    subtitle = stringResource(R.string.sport_link_import_sub),
                    onClick = {
                        // Any MIME type: exporters label .gpx and .tcx as
                        // application/octet-stream, text/xml, application/xml
                        // and application/gpx+xml depending on where the file
                        // came from, and a filter that misses one greys out
                        // the very file the user is trying to pick.
                        importLauncher.launch(arrayOf("*/*"))
                    },
                )
            }
            item {
                LinkRow(
                    icon = Icons.Filled.Tune,
                    title = stringResource(R.string.sport_link_types),
                    subtitle = stringResource(R.string.sport_link_types_sub),
                    onClick = onManageActivities,
                )
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
 * A door: what it opens, one line on what is behind it, a chevron. The same
 * shape the treatment screen uses for its reminders, so the module's settings
 * read as settings rather than as more content.
 */
@Composable
private fun LinkRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    EggCard(
        variant = CardVariant.Low,
        padding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
        onClick = onClick,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(container = MaterialTheme.colorScheme.primaryContainer) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
                    java.text.NumberFormat.getIntegerInstance().format(dashboard.todaySteps),
                    java.text.NumberFormat.getIntegerInstance().format(goal),
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

/**
 * One day of the step calendar: what was counted, and a way to correct it.
 *
 * Correcting is the only way to bring a day down — the counter can only add —
 * and it is offered here rather than in a settings screen because this is
 * where a wrong number is noticed. Up or down, the figure typed replaces the
 * day's; walking later that same day adds to it.
 */
@Composable
private fun StepDayDialog(
    day: java.time.LocalDate,
    steps: Long?,
    onDismiss: () -> Unit,
    onCorrect: (Long) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf(steps?.takeIf { it > 0 }?.toString().orEmpty()) }
    val numbers = remember { java.text.NumberFormat.getIntegerInstance() }
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
            if (editing) {
                androidx.compose.material3.OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it.filter(Char::isDigit).take(6) },
                    label = { Text(stringResource(R.string.sport_steps_correct_label)) },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                    ),
                    singleLine = true,
                )
            } else {
                Text(
                    if (steps == null || steps == 0L) stringResource(R.string.sport_steps_none)
                    else stringResource(R.string.sport_steps_count, numbers.format(steps))
                )
            }
        },
        confirmButton = {
            if (editing) {
                TextButton(
                    onClick = { typed.toLongOrNull()?.let(onCorrect) },
                    enabled = typed.toLongOrNull() != null,
                ) { Text(stringResource(R.string.action_save)) }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) }
            }
        },
        dismissButton = {
            if (editing) {
                TextButton(onClick = { editing = false }) { Text(stringResource(R.string.action_cancel)) }
            } else {
                TextButton(onClick = { editing = true }) {
                    Text(stringResource(R.string.sport_steps_correct))
                }
            }
        },
    )
}

/**
 * Under the calendar: the month in four numbers, how it compares with the
 * previous one, the goal streak and the year so far.
 *
 * Words rather than a chart, like the dashboard: the calendar above already is
 * the picture, and what it cannot show at a glance is the sum.
 */
@Composable
private fun StepSummaryCard(summary: SportViewModel.StepSummary, goal: Int) {
    val numbers = remember { java.text.NumberFormat.getIntegerInstance() }
    EggCard(variant = CardVariant.Low) {
        val m = summary.month
        if (m.daysRecorded == 0) {
            Text(
                stringResource(R.string.sport_steps_month_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Stat(value = compactSteps(m.total), label = stringResource(R.string.sport_steps_month_total))
                Stat(value = numbers.format(m.average), label = stringResource(R.string.sport_steps_month_average))
                Stat(
                    value = m.daysAtGoal.toString(),
                    label = pluralStringResource(R.plurals.sport_steps_month_goal_days, m.daysAtGoal),
                )
            }
            Text(
                stringResource(R.string.sport_steps_month_best, numbers.format(m.best)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )
            summary.changeVsPrevious?.let { change ->
                Text(
                    stringResource(
                        R.string.sport_steps_month_change,
                        (if (change > 0) "+" else "") + change,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (goal > 0) {
            Text(
                pluralStringResource(
                    R.plurals.sport_steps_goal_streak, summary.goalStreak, summary.goalStreak,
                ) + if (summary.bestGoalStreak > summary.goalStreak) {
                    " · " + stringResource(R.string.sport_steps_goal_streak_best, summary.bestGoalStreak)
                } else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (summary.yearTotal > 0) {
            Text(
                stringResource(R.string.sport_steps_year_total, numbers.format(summary.yearTotal)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
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
