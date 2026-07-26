package com.douxev.eggshell.ui.appointments

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.douxev.eggshell.R
import com.douxev.eggshell.data.AppointmentRepository
import com.douxev.eggshell.ui.common.ScreenHeader
import com.douxev.eggshell.ui.components.ActionBand
import com.douxev.eggshell.ui.components.CardRule
import com.douxev.eggshell.ui.components.CardVariant
import com.douxev.eggshell.ui.components.EggCard
import com.douxev.eggshell.ui.components.EggFab
import com.douxev.eggshell.ui.components.EmptyState
import com.douxev.eggshell.ui.components.IconTile
import com.douxev.eggshell.ui.components.ListRow
import com.douxev.eggshell.ui.components.MicroLabel
import com.douxev.eggshell.ui.components.SectionTitle
import com.douxev.eggshell.ui.components.SkeletonBlock
import com.douxev.eggshell.ui.medication.MedicationCatalog
import com.douxev.eggshell.ui.pdf.ReportPeriod
import com.douxev.eggshell.ui.pdf.ReportPrefs
import com.douxev.eggshell.ui.pdf.ReportShortcut
import com.douxev.eggshell.ui.pdf.reportOriginRes
import com.douxev.eggshell.ui.theme.EggDim
import uniffi.transition.Appointment
import uniffi.transition.NewAppointment

/** One line of [Appointment.todo], with its tick. */
data class AppointmentTodo(val label: String, val done: Boolean)

/**
 * `Appointment.todo` is one string, one task per line — there is no table for
 * this and the refonte does not add one. A ticked line is prefixed `- [x] `,
 * an open one `- [ ] `; a line written before this release carries no marker
 * at all and reads as open, so nothing anybody typed is ever lost.
 */
private val TODO_MARKER = Regex("""^\s*[-*]\s*\[\s*([xX ])\s*]\s*(.*)$""")

fun appointmentTodoItems(raw: String?): List<AppointmentTodo> =
    raw.orEmpty()
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { line ->
            val match = TODO_MARKER.matchEntire(line)
            if (match == null) {
                AppointmentTodo(line, done = false)
            } else {
                AppointmentTodo(
                    label = match.groupValues[2].trim(),
                    done = match.groupValues[1].equals("x", ignoreCase = true),
                )
            }
        }
        .filter { it.label.isNotEmpty() }
        .toList()

fun renderAppointmentTodo(items: List<AppointmentTodo>): String? =
    items.takeIf { it.isNotEmpty() }
        ?.joinToString("\n") { (if (it.done) "- [x] " else "- [ ] ") + it.label }

@HiltViewModel
class AppointmentsListViewModel @Inject constructor(
    private val repo: AppointmentRepository,
    private val reportPrefs: ReportPrefs,
) : ViewModel() {

    data class State(
        val loading: Boolean = true,
        /** The soonest appointment still ahead — the one the card is about. */
        val next: Appointment? = null,
        val todo: List<AppointmentTodo> = emptyList(),
        val later: List<Appointment> = emptyList(),
        /**
         * Past appointments stay reachable: they are the anchor of « depuis la
         * dernière consultation », and an entry you can no longer open is an
         * entry you can no longer correct or delete.
         */
        val past: List<Appointment> = emptyList(),
        val reportPeriod: ReportPeriod = ReportPeriod.M3,
        val reportShortcut: ReportShortcut = ReportShortcut.LAST_VISIT,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            val all = runCatching { repo.list() }.getOrDefault(emptyList())
            val now = System.currentTimeMillis()
            val upcoming = all.filter { it.atMs >= now }.sortedBy { it.atMs }
            _state.value = State(
                loading = false,
                next = upcoming.firstOrNull(),
                todo = appointmentTodoItems(upcoming.firstOrNull()?.todo),
                later = upcoming.drop(1),
                past = all.filter { it.atMs < now }.sortedByDescending { it.atMs },
                reportPeriod = reportPrefs.period(),
                reportShortcut = reportPrefs.shortcut(),
            )
        }
    }

    /**
     * Tick or untick one line of the next appointment's to-do list. The screen
     * shows the new state at once and the vault catches up; if the write fails
     * we reload rather than leave the UI claiming something it did not save.
     */
    fun toggleTodo(index: Int) {
        val snapshot = _state.value
        val appointment = snapshot.next ?: return
        if (index !in snapshot.todo.indices) return
        val updated = snapshot.todo.toMutableList()
        updated[index] = updated[index].copy(done = !updated[index].done)
        _state.value = snapshot.copy(todo = updated)
        viewModelScope.launch {
            runCatching {
                repo.update(
                    appointment.id,
                    NewAppointment(
                        atMs = appointment.atMs,
                        place = appointment.place,
                        professionalName = appointment.professionalName,
                        professionalRole = appointment.professionalRole,
                        notes = appointment.notes,
                        todo = renderAppointmentTodo(updated),
                        reminderAtMs = appointment.reminderAtMs,
                    ),
                )
            }
                .onSuccess { saved -> _state.value = _state.value.copy(next = saved) }
                .onFailure { refresh() }
        }
    }
}

@Composable
fun AppointmentsScreen(
    onAdd: () -> Unit,
    onEdit: (Long) -> Unit,
    onBack: () -> Unit = {},
    /** « Préparer ma consultation » — the only entry point of the PDF export. */
    onPrepareVisit: () -> Unit = {},
    vm: AppointmentsListViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    LaunchedEffect(Unit) { vm.refresh() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        bottomBar = {
            ActionBand {
                EggFab(
                    icon = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.rdv_add),
                    onClick = onAdd,
                )
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(
                start = EggDim.ScreenMargin,
                end = EggDim.ScreenMargin,
                top = 4.dp,
                bottom = EggDim.BlockGap,
            ),
            verticalArrangement = Arrangement.spacedBy(EggDim.BlockGap),
        ) {
            item {
                ScreenHeader(title = stringResource(R.string.appointments_title), onBack = onBack)
            }

            item {
                val next = state.next
                when {
                    state.loading -> SkeletonBlock(height = 220.dp)
                    next == null -> EmptyState(
                        message = stringResource(R.string.rdv_empty),
                        actionLabel = stringResource(R.string.rdv_add),
                        onAction = onAdd,
                    )
                    else -> NextAppointmentCard(
                        appointment = next,
                        todo = state.todo,
                        onToggleTodo = vm::toggleTodo,
                        onOpen = { onEdit(next.id) },
                    )
                }
            }

            // Stays put even with an empty agenda: preparing a visit must not
            // depend on having already booked one (§6.6 states).
            item {
                PrepareVisitCard(
                    period = state.reportPeriod,
                    shortcut = state.reportShortcut,
                    onClick = onPrepareVisit,
                )
            }

            if (state.later.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.rdv_later)) }
                items(state.later, key = { it.id }) { entry ->
                    AppointmentRow(entry = entry, onClick = { onEdit(entry.id) })
                }
            }

            if (state.past.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.rdv_past)) }
                items(state.past, key = { it.id }) { entry ->
                    AppointmentRow(entry = entry, onClick = { onEdit(entry.id) })
                }
            }
        }
    }
}

/**
 * The next appointment, in one card: when, with whom, where, and what you told
 * yourself to bring up. The to-do list is the point of the card — it is what
 * you actually open the screen for, five minutes before going in.
 */
@Composable
private fun NextAppointmentCard(
    appointment: Appointment,
    todo: List<AppointmentTodo>,
    onToggleTodo: (Int) -> Unit,
    onOpen: () -> Unit,
) {
    EggCard(variant = CardVariant.Tertiary, onClick = onOpen) {
        val ink = LocalContentColor.current
        MicroLabel(countdownLabel(appointment.atMs), color = ink.copy(alpha = 0.75f))
        Text(
            whenLabel(appointment.atMs),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 4.dp),
        )

        val proName = appointment.professionalName?.takeIf { it.isNotBlank() }
        val proRole = appointment.professionalRole?.takeIf { it.isNotBlank() }
        if (proName != null || proRole != null) {
            DetailLine(
                icon = Icons.Filled.Person,
                title = proName ?: proRole.orEmpty(),
                subtitle = if (proName != null) proRole else null,
                topPadding = 16.dp,
            )
        }
        appointment.place?.takeIf { it.isNotBlank() }?.let { place ->
            // The place can be two lines the way it is typed — building, then
            // floor — so the first line stays the address and the rest follows.
            val lines = place.lines().map { it.trim() }.filter { it.isNotEmpty() }
            DetailLine(
                icon = Icons.Filled.Place,
                title = lines.firstOrNull().orEmpty(),
                subtitle = lines.drop(1).joinToString(MedicationCatalog.SEP).ifBlank { null },
                topPadding = 12.dp,
            )
        }

        if (todo.isNotEmpty()) {
            CardRule(modifier = Modifier.padding(top = 18.dp), alpha = 0.22f)
            MicroLabel(
                stringResource(R.string.rdv_todo_label),
                color = ink.copy(alpha = 0.75f),
                modifier = Modifier.padding(top = 16.dp),
            )
            todo.forEachIndexed { index, item ->
                TodoRow(item = item, onToggle = { onToggleTodo(index) })
            }
        }
    }
}

@Composable
private fun DetailLine(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String?,
    topPadding: androidx.compose.ui.unit.Dp,
) {
    val ink = LocalContentColor.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = topPadding),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(19.dp),
            tint = ink.copy(alpha = 0.8f),
        )
        Column {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = ink.copy(alpha = 0.78f),
                )
            }
        }
    }
}

/**
 * A drawn 17 dp circle rather than a `Checkbox`: the refonte's icon set has no
 * empty-circle glyph, and a stock checkbox would be the only square corner on
 * the card. The whole row is the touch target — 17 dp is not one.
 */
@Composable
private fun TodoRow(item: AppointmentTodo, onToggle: () -> Unit) {
    val ink = LocalContentColor.current
    val container = MaterialTheme.colorScheme.tertiaryContainer
    val doneLabel = stringResource(R.string.rdv_todo_done)
    val pendingLabel = stringResource(R.string.rdv_todo_pending)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = item.done,
                onValueChange = { onToggle() },
                role = Role.Checkbox,
            )
            .semantics { stateDescription = if (item.done) doneLabel else pendingLabel }
            .heightIn(min = EggDim.TouchTarget),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(17.dp)
                .then(
                    if (item.done) {
                        Modifier.background(ink, CircleShape)
                    } else {
                        Modifier.border(1.6.dp, ink.copy(alpha = 0.55f), CircleShape)
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (item.done) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(11.dp),
                    tint = container,
                )
            }
        }
        Text(
            item.label,
            style = MaterialTheme.typography.bodyMedium,
            color = ink.copy(alpha = if (item.done) 0.6f else 1f),
        )
    }
}

@Composable
private fun PrepareVisitCard(
    period: ReportPeriod,
    shortcut: ReportShortcut,
    onClick: () -> Unit,
) {
    EggCard(
        variant = CardVariant.Low,
        padding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
        onClick = onClick,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.PictureAsPdf,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.rdv_prepare_title),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    // The subtitle quotes the period the export is actually set
                    // to, not a figure baked into the copy.
                    stringResource(
                        R.string.rdv_prepare_sub_fmt,
                        stringResource(reportOriginRes(period, shortcut)),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(19.dp),
                tint = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun AppointmentRow(entry: Appointment, onClick: () -> Unit) {
    val proName = entry.professionalName?.takeIf { it.isNotBlank() }
    val proRole = entry.professionalRole?.takeIf { it.isNotBlank() }
    val place = entry.place?.takeIf { it.isNotBlank() }?.lines()?.firstOrNull()?.trim()
    val title = proRole ?: proName ?: place ?: stringResource(R.string.rdv_no_details)
    val subtitle = listOfNotNull(
        dayLabel(entry.atMs),
        timeLabel(entry.atMs),
        proName?.takeIf { it != title },
    ).joinToString(MedicationCatalog.SEP)
    ListRow(
        title = title,
        subtitle = subtitle,
        onClick = onClick,
        leading = {
            IconTile {
                Icon(
                    Icons.Filled.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

// ---------------------------------------------------------------------------
// Dates
// ---------------------------------------------------------------------------

/** « DANS 17 JOURS » — uppercase inside the string, never a text transform. */
@Composable
private fun countdownLabel(atMs: Long): String {
    val zone = remember { ZoneId.systemDefault() }
    val days = ChronoUnit.DAYS.between(
        LocalDate.now(zone),
        Instant.ofEpochMilli(atMs).atZone(zone).toLocalDate(),
    ).toInt()
    return when {
        days <= 0 -> stringResource(R.string.rdv_next_today)
        days == 1 -> stringResource(R.string.rdv_next_tomorrow)
        else -> pluralStringResource(R.plurals.rdv_next_in_days, days, days)
    }
}

/** « Mercredi 12 août · 14:30 ». */
@Composable
private fun whenLabel(atMs: Long): String =
    dayLabel(atMs) + MedicationCatalog.SEP + timeLabel(atMs)

@Composable
private fun dayLabel(atMs: Long): String {
    val locale = Locale.getDefault()
    val zone = remember { ZoneId.systemDefault() }
    return DateTimeFormatter.ofPattern("EEEE d MMMM", locale)
        .format(Instant.ofEpochMilli(atMs).atZone(zone))
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
}

@Composable
private fun timeLabel(atMs: Long): String {
    val locale = Locale.getDefault()
    val zone = remember { ZoneId.systemDefault() }
    return DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        .withLocale(locale)
        .format(Instant.ofEpochMilli(atMs).atZone(zone))
}
