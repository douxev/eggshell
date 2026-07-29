package com.douxev.eggshell.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bloodtype
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.douxev.eggshell.R
import com.douxev.eggshell.data.ModuleBadgePrefs
import com.douxev.eggshell.ui.components.ActionBand
import com.douxev.eggshell.ui.components.CardVariant
import com.douxev.eggshell.ui.components.EggCard
import com.douxev.eggshell.ui.components.EggFab
import com.douxev.eggshell.ui.components.HomeHeader
import com.douxev.eggshell.ui.components.MicroLabel
import com.douxev.eggshell.ui.components.ProgressRing
import com.douxev.eggshell.ui.components.SectionTitle
import com.douxev.eggshell.ui.theme.EggColors
import com.douxev.eggshell.ui.theme.EggDim
import com.douxev.eggshell.ui.theme.EggShapes
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale

/**
 * Accueil — the only root screen of the refonte.
 *
 * Three ideas govern it: the daily gesture (tick a dose, tap a face) happens
 * here without navigating; the map of the app is visible above the fold as an
 * eight-module launcher; and enabling a module never adds a destination, it
 * only adds a tile.
 */
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onOpenModules: () -> Unit,
    onOpenMeds: () -> Unit,
    onOpenAppointments: () -> Unit,
    onOpenJournal: () -> Unit,
    onOpenBleeding: () -> Unit,
    onOpenLabs: () -> Unit,
    onOpenWeight: () -> Unit,
    onOpenPhotos: () -> Unit,
    onOpenVoice: () -> Unit,
    onOpenNotes: () -> Unit,
    onOpenFullJournal: () -> Unit,
    onAddMedication: () -> Unit,
    onMoodSaved: () -> Unit,
    onQuickLog: () -> Unit,
    vm: HomeViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    val markingTaken by vm.markingTaken.collectAsState()
    LaunchedEffect(Unit) { vm.refresh() }

    val date = remember {
        SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(Date())
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        // The band is reserved, not floating: without it the FAB would sit on
        // top of the last launcher row.
        bottomBar = {
            ActionBand {
                EggFab(
                    icon = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.home_fab_note),
                    label = stringResource(R.string.home_fab_note),
                    onClick = onQuickLog,
                )
            }
        },
    ) { padding ->
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = EggDim.ScreenMargin),
        verticalArrangement = Arrangement.spacedBy(EggDim.BlockGap),
    ) {
        HomeHeader(
            title = date,
            settingsContentDescription = stringResource(R.string.home_settings),
            onOpenSettings = onOpenSettings,
        )

        if (state.modules.meds) {
            DoseCard(
                state = state,
                markingTaken = markingTaken,
                onMarkTaken = { state.nextDose?.let(vm::markTakenNow) },
                onSnooze = { state.nextDose?.let(vm::snooze) },
                onAddMedication = onAddMedication,
            )
        }

        if (state.modules.journal) {
            MoodCard(
                selectedFace = state.moodFace,
                onPick = { face ->
                    vm.setMoodFace(face)
                    onMoodSaved()
                },
                onOpenDetail = onOpenFullJournal,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionTitle(
                text = stringResource(R.string.home_section_title),
                action = stringResource(R.string.home_section_modules),
                onAction = onOpenModules,
            )
            FamilyLegend()
        }

        LauncherGrid(
            state = state,
            onOpen = { module ->
                vm.markModuleOpened(module)
                when (module) {
                    ModuleBadgePrefs.Module.Meds -> onOpenMeds()
                    ModuleBadgePrefs.Module.Appointments -> onOpenAppointments()
                    ModuleBadgePrefs.Module.Journal -> onOpenJournal()
                    ModuleBadgePrefs.Module.Bleeding -> onOpenBleeding()
                    ModuleBadgePrefs.Module.Labs -> onOpenLabs()
                    ModuleBadgePrefs.Module.Weight -> onOpenWeight()
                    ModuleBadgePrefs.Module.Photos -> onOpenPhotos()
                    ModuleBadgePrefs.Module.Voice -> onOpenVoice()
                    ModuleBadgePrefs.Module.Notes -> onOpenNotes()
                }
            },
        )

        Spacer(Modifier.height(12.dp))
    }
    }
}

// ---------------------------------------------------------------- dose card

@Composable
private fun DoseCard(
    state: HomeViewModel.State,
    markingTaken: Boolean,
    onMarkTaken: () -> Unit,
    onSnooze: () -> Unit,
    onAddMedication: () -> Unit,
) {
    // Until the vault has answered we do not know whether there is a treatment,
    // and flashing "tu n'as pas encore de traitement" at someone who has three
    // is worse than showing nothing. §5.3: a skeleton at the real dimensions.
    if (state.loading && !state.hasMedications) {
        com.douxev.eggshell.ui.components.SkeletonBlock(height = 186.dp)
        return
    }

    // No treatment at all is not a "0/0" state — it dead-ends the user. It gets
    // the unified empty card with a priming button instead (§5.3).
    if (!state.hasMedications) {
        com.douxev.eggshell.ui.components.EmptyState(
            message = stringResource(R.string.home_no_medication),
            actionLabel = stringResource(R.string.home_no_medication_action),
            onAction = onAddMedication,
        )
        return
    }

    val view = LocalView.current
    val scheme = MaterialTheme.colorScheme
    val next = state.nextDose
    val planned = state.plannedCount
    val taken = state.takenCount
    // "2/3" reads as nothing out loud; the ring announces what it counts.
    val ringDescription = stringResource(R.string.home_dose_ring_cd, taken, planned)

    EggCard(variant = CardVariant.Primary) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            ProgressRing(
                value = if (planned == 0) 0f else taken / planned.toFloat(),
                diameter = 64.dp,
                stroke = 6.dp,
                color = scheme.primary,
                track = scheme.surfaceContainerHighest,
                modifier = Modifier.semantics { contentDescription = ringDescription },
            ) {
                Text(
                    "$taken/$planned",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.W700,
                    color = scheme.onPrimaryContainer,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                when {
                    planned == 0 -> Text(
                        stringResource(R.string.home_no_schedule),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.W600,
                    )
                    next == null -> Text(
                        stringResource(R.string.home_all_taken),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.W600,
                    )
                    else -> {
                        MicroLabel(
                            stringResource(
                                R.string.home_next_dose,
                                timeFormat.format(Date(next.scheduledAtMs)),
                            ),
                            color = scheme.onPrimaryContainer.copy(alpha = 0.72f),
                        )
                        Text(
                            doseTitle(next),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.W600,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                }
            }
        }

        if (next != null || planned == 0) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp),
            ) {
                Button(
                    onClick = {
                        if (next != null) {
                            view.performHapticFeedback(
                                android.view.HapticFeedbackConstants.CONFIRM,
                            )
                            onMarkTaken()
                        } else {
                            onAddMedication()
                        }
                    },
                    // Dead while the intake is being written: a second tap
                    // would log the same occurrence twice.
                    enabled = next == null || !markingTaken,
                    shape = EggShapes.Pill,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = scheme.primary,
                        contentColor = scheme.onPrimary,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                ) {
                    Icon(
                        if (next != null) Icons.Filled.Check else Icons.Filled.Schedule,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(
                            if (next != null) R.string.home_mark_taken else R.string.home_schedule_dose,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (next != null) {
                    // The DS "tonal" icon-button pairs on-surface-variant with
                    // surface-container-highest, which is a foreign pair inside
                    // a primary-container card — derive the pair from the card
                    // instead so the contrast rule holds in all 15 palettes.
                    Surface(
                        onClick = onSnooze,
                        shape = CircleShape,
                        color = scheme.onPrimaryContainer.copy(alpha = 0.12f),
                        contentColor = scheme.onPrimaryContainer,
                        modifier = Modifier.size(EggDim.TouchTarget),
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            Icon(
                                Icons.Filled.Schedule,
                                contentDescription = stringResource(R.string.home_snooze),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }

        state.reminder?.let { reminder ->
            Column(modifier = Modifier.padding(top = 12.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(scheme.onPrimaryContainer.copy(alpha = 0.20f)),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                ) {
                    Icon(
                        Icons.Filled.Notifications,
                        contentDescription = null,
                        modifier = Modifier
                            .size(17.dp)
                            .alpha(0.70f),
                    )
                    Text(
                        // « Puis bilan hormonal, dans 3 j » — the delay is the
                        // actionable half of the line; the label alone cannot
                        // tell tomorrow from six weeks away.
                        stringResource(
                            R.string.home_reminder_next,
                            reminder.text,
                            relativeDelay(reminder.dueAtMs),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .alpha(0.85f),
                    )
                    if (reminder.othersCount > 0) {
                        MicroLabel(
                            if (reminder.othersCount == 1) {
                                stringResource(R.string.home_reminder_more_one)
                            } else {
                                stringResource(R.string.home_reminder_more, reminder.othersCount)
                            },
                            color = scheme.onPrimaryContainer.copy(alpha = 0.70f),
                        )
                    }
                }
            }
        }
    }
}

private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

/**
 * Short relative delay for the reminder line: « à 20:00 », « demain », « dans 3 j ».
 *
 * Counted in **calendar days**, not in elapsed milliseconds: at 23:00, a
 * reminder due tomorrow at 09:00 is ten hours away and an elapsed-time
 * division would call it « à 09:00 » as if it were still today — while
 * something due the day after tomorrow at 08:00 would read « demain ».
 */
@Composable
private fun relativeDelay(atMs: Long): String {
    val zone = ZoneId.systemDefault()
    val due = Instant.ofEpochMilli(atMs).atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(LocalDate.now(zone), due).toInt()
    return when {
        days <= 0 -> stringResource(R.string.home_reminder_at, timeFormat.format(Date(atMs)))
        days == 1 -> stringResource(R.string.home_reminder_tomorrow)
        else -> stringResource(R.string.home_reminder_in_days, days)
    }
}

private fun doseTitle(item: HomeViewModel.DoseItem): String = buildString {
    append(item.medication.name)
    val dose = item.medication.defaultDose
    if (dose != null) {
        append(" · ")
        append(if (dose % 1.0 == 0.0) dose.toInt().toString() else dose.toString())
        item.medication.defaultDoseUnit?.let { append(" $it") }
    }
}

// ---------------------------------------------------------------- mood card

private val MOOD_EMOJI = listOf("😞", "🙁", "😐", "🙂", "😄")

@Composable
private fun MoodCard(
    selectedFace: Int?,
    onPick: (Int) -> Unit,
    onOpenDetail: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val labels = listOf(
        stringResource(R.string.mood_face_1),
        stringResource(R.string.mood_face_2),
        stringResource(R.string.mood_face_3),
        stringResource(R.string.mood_face_4),
        stringResource(R.string.mood_face_5),
    )
    EggCard(
        variant = CardVariant.Low,
        padding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            MicroLabel(stringResource(R.string.home_mood_question))
            // Empty until something is recorded today — that *is* the empty state.
            Text(
                selectedFace?.let { labels[it - 1] }.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.primary,
                maxLines = 1,
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
        ) {
            MOOD_EMOJI.forEachIndexed { index, emoji ->
                val face = index + 1
                val selected = selectedFace == face
                val scale by animateFloatAsState(
                    targetValue = if (selected) 1.04f else 1f,
                    animationSpec = tween(150),
                    label = "mood-face-scale",
                )
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .height(EggDim.TouchTarget)
                        .scale(scale)
                        .background(
                            if (selected) scheme.primaryContainer else scheme.surfaceContainerHigh,
                            RoundedCornerShape(14.dp),
                        )
                        .then(
                            if (selected) {
                                Modifier.border(2.dp, scheme.primary, RoundedCornerShape(14.dp))
                            } else {
                                Modifier
                            }
                        )
                        .clickable { onPick(face) }
                        .semantics { contentDescription = labels[index] },
                ) {
                    Text(
                        emoji,
                        fontSize = 25.sp,
                        textAlign = TextAlign.Center,
                        // Compose has no saturation filter on text; dimming the
                        // unselected faces keeps the chosen one legible.
                        modifier = Modifier.alpha(if (selected) 1f else 0.75f),
                    )
                }
            }
        }

        Surface(
            onClick = onOpenDetail,
            shape = EggShapes.Pill,
            color = scheme.surfaceContainerHigh,
            contentColor = scheme.primary,
            modifier = Modifier
                .padding(top = 9.dp)
                .fillMaxWidth()
                .height(40.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    Icons.Filled.EditNote,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    stringResource(R.string.home_mood_detail),
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.W600,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ------------------------------------------------------------ family legend

@Composable
private fun FamilyLegend() {
    val scheme = MaterialTheme.colorScheme
    Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        LegendDot(scheme.primaryContainer, stringResource(R.string.family_treatment))
        LegendDot(scheme.tertiaryContainer, stringResource(R.string.family_feeling))
        LegendDot(EggColors.evolutionContainer, stringResource(R.string.family_evolution))
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, CircleShape),
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
        )
    }
}

// ------------------------------------------------------------ launcher grid

private data class LauncherTile(
    val module: ModuleBadgePrefs.Module,
    val labelRes: Int,
    val icon: ImageVector,
    val family: Family,
)

private enum class Family { Treatment, Feeling, Evolution }

@Composable
private fun LauncherGrid(
    state: HomeViewModel.State,
    onOpen: (ModuleBadgePrefs.Module) -> Unit,
) {
    val m = state.modules
    val tiles = buildList {
        if (m.meds) add(LauncherTile(ModuleBadgePrefs.Module.Meds, R.string.module_meds, Icons.Filled.Medication, Family.Treatment))
        if (m.appointments) add(LauncherTile(ModuleBadgePrefs.Module.Appointments, R.string.module_appointments, Icons.Filled.CalendarMonth, Family.Treatment))
        if (m.journal) add(LauncherTile(ModuleBadgePrefs.Module.Journal, R.string.module_journal, Icons.Filled.Mood, Family.Feeling))
        if (m.bleeding) add(LauncherTile(ModuleBadgePrefs.Module.Bleeding, R.string.module_bleeding, Icons.Filled.Bloodtype, Family.Feeling))
        if (m.labs) add(LauncherTile(ModuleBadgePrefs.Module.Labs, R.string.module_labs, Icons.Filled.Science, Family.Evolution))
        if (m.weight) add(LauncherTile(ModuleBadgePrefs.Module.Weight, R.string.module_weight, Icons.Filled.Straighten, Family.Evolution))
        if (m.photos) add(LauncherTile(ModuleBadgePrefs.Module.Photos, R.string.module_photos, Icons.Filled.PhotoCamera, Family.Evolution))
        if (m.voice) add(LauncherTile(ModuleBadgePrefs.Module.Voice, R.string.module_voice, Icons.Filled.GraphicEq, Family.Evolution))
        if (m.notes) add(LauncherTile(ModuleBadgePrefs.Module.Notes, R.string.module_notes, Icons.Filled.Description, Family.Evolution))
    }
    // A fixed 4-column grid inside an already-scrolling column: laid out by
    // hand rather than with LazyVerticalGrid, which cannot nest in a scroll.
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.chunked(4).forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                row.forEach { tile ->
                    LauncherCell(
                        tile = tile,
                        badge = state.badges[tile.module],
                        onClick = { onOpen(tile.module) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // Keep the last row's tiles on the same 4-track grid.
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun LauncherCell(
    tile: LauncherTile,
    badge: HomeViewModel.Badge?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val label = stringResource(tile.labelRes)
    val container = when (tile.family) {
        Family.Treatment -> scheme.primaryContainer
        Family.Feeling -> scheme.tertiaryContainer
        Family.Evolution -> EggColors.evolutionContainer
    }
    val content = when (tile.family) {
        Family.Treatment -> scheme.onPrimaryContainer
        Family.Feeling -> scheme.onTertiaryContainer
        Family.Evolution -> EggColors.onEvolutionContainer
    }
    val accessible = when (badge) {
        is HomeViewModel.Badge.Counter ->
            stringResource(R.string.home_badge_pending_cd, label, badge.count)
        HomeViewModel.Badge.News -> stringResource(R.string.home_badge_news_cd, label)
        null -> label
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp),
        modifier = modifier
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = accessible },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .background(container, EggShapes.LauncherTile),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    tile.icon,
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier.size(27.dp),
                )
            }
            when (badge) {
                is HomeViewModel.Badge.Counter -> Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(3.dp, (-3).dp)
                        .size(19.dp)
                        // The ring is the screen background punching a hole in
                        // the tile, not white.
                        .border(2.dp, scheme.surface, CircleShape)
                        .padding(2.dp)
                        .background(scheme.error, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (badge.count > 9) stringResource(R.string.home_badge_overflow) else "${badge.count}",
                        color = scheme.onError,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.W700,
                    )
                }
                HomeViewModel.Badge.News -> Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(2.dp, (-2).dp)
                        .size(14.dp)
                        .border(2.dp, scheme.surface, CircleShape)
                        .padding(2.dp)
                        .background(scheme.error, CircleShape),
                )
                null -> Unit
            }
        }
        LauncherLabel(label, scheme.onSurfaceVariant)
    }
}

/**
 * The launcher label, shrunk until it fits its track on **one line**.
 *
 * The grid is four fixed tracks wide (~76 dp at 360 dp), and a label that
 * wraps would make its row taller than the next one — the whole grid would
 * lose its alignment. « Menstruations » is the longest label we ship and does
 * not fit at the nominal size, so the text steps down instead of being clipped
 * or wrapped. Shrinking also absorbs a large system font scale, which clipping
 * would simply swallow.
 */
@Composable
private fun LauncherLabel(label: String, color: Color) {
    val base = MaterialTheme.typography.labelSmall
    // Keyed on the label so a locale change starts the search over.
    var style by remember(label, base) { mutableStateOf(base) }
    var measured by remember(label, base) { mutableStateOf(false) }
    Text(
        label,
        style = style,
        color = color,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        onTextLayout = { result ->
            when {
                !result.didOverflowWidth -> measured = true
                // Tracking goes first: 0.5 sp on thirteen glyphs is most of the
                // overflow, and dropping it is far less visible than a word set
                // smaller than its neighbours.
                style.letterSpacing != 0.sp -> style = style.copy(letterSpacing = 0.sp)
                style.fontSize > LauncherLabelMinSize ->
                    style = style.copy(fontSize = (style.fontSize.value * 0.94f).sp)
                // Nothing left to give: draw it rather than hide it.
                else -> measured = true
            }
        },
        // Drawn only once it fits, so the step-down is never a visible flicker.
        modifier = Modifier
            .clearAndSetSemantics {}
            .drawWithContent { if (measured) drawContent() },
    )
}

/** Floor for [LauncherLabel]: below this the label stops being readable. */
private val LauncherLabelMinSize = 8.sp
