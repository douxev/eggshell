package com.douxev.eggshell.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MoodBad
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Vaccines
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.douxev.eggshell.R
import com.douxev.eggshell.ui.medication.MedicationCatalog

/**
 * "Aujourd'hui" — homepage modelled on the Transi design's `screens-today.jsx`.
 * Hero: progress ring + next dose. Below: today's schedule list, journal CTA,
 * reminders.
 */
@Composable
fun TodayScreen(
    onOpenMed: (Long) -> Unit,
    onOpenJournalEntry: () -> Unit,
    onOpenLabs: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onAddMedication: () -> Unit = {},
    onOpenMedList: () -> Unit = {},
    vm: TodayViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    LaunchedEffect(Unit) { vm.refresh() }

    val items = state.items
    val done = items.count { it.done }
    val next = items.firstOrNull { !it.done }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item { Header(onOpenSettings = onOpenSettings) }

        // Hero next-dose card. Only rendered when the Médics feature is on
        // — otherwise the "progress ring + next dose" headline doesn't
        // apply to the user's setup.
        if (state.gates.medications) {
            item {
                HeroCard(
                    doneCount = done,
                    totalCount = items.size,
                    next = next,
                    onMarkTaken = { next?.let(vm::markDoneNow) },
                    onSetupSchedule = {
                        // Route to the right setup screen: pick an existing med
                        // to schedule, or create the first one if there's none.
                        if (state.hasMedications) onOpenMedList() else onAddMedication()
                    },
                )
            }
        }

        // Today's schedule list. Already empty-by-construction when Médics
        // is off (TodayViewModel skips medication queries), but the guard
        // keeps the section + its title from showing up either way.
        if (state.gates.medications && items.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.today_section_today)) }
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                        items.forEachIndexed { index, item ->
                            DoseRow(item = item, onToggle = { vm.markDoneNow(item) })
                            if (index < items.size - 1) {
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                )
                            }
                        }
                    }
                }
            }
        }

        // Quick journal CTA card — hidden when Journal feature is off.
        if (state.gates.journal) {
            item { SectionTitle(stringResource(R.string.today_section_feel)) }
            item {
            Card(
                onClick = onOpenJournalEntry,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                ),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.today_feel_title),
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Text(
                                stringResource(R.string.today_feel_sub),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(
                                    MaterialTheme.colorScheme.tertiary,
                                    CircleShape,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.Mood,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onTertiary,
                            )
                        }
                    }
                    if (state.moodTrend.size >= 2) {
                        Row(
                            verticalAlignment = Alignment.Bottom,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.today_feel_trend_label).uppercase(Locale.getDefault()),
                                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.5.sp),
                                )
                                Text(
                                    stringResource(R.string.today_feel_trend_rising),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                            Sparkline(
                                values = state.moodTrend,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier
                                    .size(width = 130.dp, height = 36.dp),
                            )
                        }
                    }
                }
            }
        }
        }

        // Reminders.
        item { SectionTitle(stringResource(R.string.today_section_reminders)) }
        item {
            val upcoming = state.upcomingReminders
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(24.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (upcoming.isEmpty()) {
                    Text(
                        stringResource(R.string.today_reminders_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(20.dp),
                    )
                } else {
                    Column {
                        upcoming.forEachIndexed { i, rem ->
                            val (icon, tint) = when (rem.kind) {
                                TodayViewModel.UpcomingReminder.Kind.Medication ->
                                    Icons.Filled.Vaccines to MaterialTheme.colorScheme.secondary
                                TodayViewModel.UpcomingReminder.Kind.Lab ->
                                    Icons.Filled.Science to MaterialTheme.colorScheme.primary
                                TodayViewModel.UpcomingReminder.Kind.Photo ->
                                    Icons.Filled.PhotoCamera to MaterialTheme.colorScheme.tertiary
                                TodayViewModel.UpcomingReminder.Kind.Voice ->
                                    Icons.Filled.GraphicEq to MaterialTheme.colorScheme.tertiary
                            }
                            ReminderRow(
                                icon = icon,
                                tint = tint,
                                title = rem.title,
                                sub = rem.subtitle,
                                trailing = formatRelative(rem.dueAtMs),
                                onClick = {
                                    when (rem.kind) {
                                        TodayViewModel.UpcomingReminder.Kind.Medication ->
                                            rem.medicationId?.let { onOpenMed(it) }
                                        TodayViewModel.UpcomingReminder.Kind.Lab -> onOpenLabs()
                                        else -> { /* no specific deeplink for photo/voice yet */ }
                                    }
                                },
                            )
                            if (i < upcoming.size - 1) {
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        item { Box(modifier = Modifier.height(80.dp)) }
    }
}

@Composable
private fun Header(onOpenSettings: () -> Unit) {
    val date = remember {
        SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(Date())
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.today_greeting),
                style = MaterialTheme.typography.headlineLarge,
            )
            Text(
                date,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onOpenSettings) {
            Icon(
                Icons.Filled.Settings,
                contentDescription = stringResource(R.string.more_title),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HeroCard(
    doneCount: Int,
    totalCount: Int,
    next: TodayViewModel.TodayItem?,
    onMarkTaken: () -> Unit,
    onSetupSchedule: () -> Unit,
) {
    // No schedule yet: a "0/0" ring next to "Tout est pris ✓" reads as
    // contradictory and dead-ends the user. Show a dedicated empty state with
    // a clear call-to-action instead.
    val empty = totalCount == 0
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProgressRing(
                    value = if (empty) 0f else doneCount / totalCount.toFloat(),
                    color = MaterialTheme.colorScheme.primary,
                    track = MaterialTheme.colorScheme.primary.copy(alpha = 0.20f),
                    diameter = 70.dp,
                    stroke = 7.dp,
                ) {
                    if (empty) {
                        Icon(
                            Icons.Filled.Schedule,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                            modifier = Modifier.size(26.dp),
                        )
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text("$doneCount", fontSize = 20.sp, fontWeight = FontWeight.W700)
                                Text(
                                    "/$totalCount",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
                                )
                            }
                            Text(
                                stringResource(R.string.today_ring_label),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.W700,
                                letterSpacing = 0.5.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                            )
                        }
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.today_next_dose_label).uppercase(Locale.getDefault()),
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.5.sp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    )
                    when {
                        empty -> {
                            Text(
                                stringResource(R.string.today_no_schedule_title),
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Text(
                                stringResource(R.string.today_no_schedule_sub),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                            )
                        }
                        next != null -> {
                            Text(next.medication.name, style = MaterialTheme.typography.titleLarge)
                            val sub = buildString {
                                next.medication.defaultDose?.let { append("${formatNumber(it)} ") }
                                append(next.medication.defaultDoseUnit.orEmpty())
                                append(" · à ")
                                append(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(next.scheduledAtMs)))
                            }
                            Text(
                                sub.trim(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                            )
                        }
                        else -> {
                            Text(
                                stringResource(R.string.today_all_taken),
                                style = MaterialTheme.typography.titleLarge,
                            )
                        }
                    }
                }
            }
            if (empty || next != null) {
                Box(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                    Button(
                        onClick = if (empty) onSetupSchedule else onMarkTaken,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                        shape = CircleShape,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) {
                        Icon(
                            if (empty) Icons.Filled.Schedule else Icons.Filled.CheckCircle,
                            contentDescription = null,
                        )
                        Text(
                            "  " + stringResource(
                                if (empty) R.string.today_setup_schedule else R.string.today_mark_taken,
                            ),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DoseRow(item: TodayViewModel.TodayItem, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 11.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .background(
                    color = if (item.done) MaterialTheme.colorScheme.primary else Color.Transparent,
                    shape = RoundedCornerShape(8.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (item.done) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(18.dp),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .background(Color.Transparent, RoundedCornerShape(8.dp)),
                )
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.size(2.dp))
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.medication.name,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.W600),
                color = if (item.done) {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Text(
                buildString {
                    item.medication.defaultDose?.let { append("${formatNumber(it)} ") }
                    append(item.medication.defaultDoseUnit.orEmpty())
                }.trim(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(item.scheduledAtMs)),
            style = MaterialTheme.typography.labelLarge,
            color = if (item.done) {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

@Composable
private fun ReminderRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    title: String,
    sub: String,
    trailing: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceContainerHigh,
                    RoundedCornerShape(12.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(trailing, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
    )
}

@Composable
private fun ProgressRing(
    value: Float,
    color: Color,
    track: Color,
    diameter: androidx.compose.ui.unit.Dp,
    stroke: androidx.compose.ui.unit.Dp,
    content: @Composable () -> Unit,
) {
    val v = value.coerceIn(0f, 1f)
    Box(modifier = Modifier.size(diameter), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(modifier = Modifier.size(diameter)) {
            val strokePx = stroke.toPx()
            drawArc(
                color = track,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = strokePx),
                topLeft = Offset(strokePx / 2, strokePx / 2),
                size = Size(size.width - strokePx, size.height - strokePx),
            )
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 360f * v,
                useCenter = false,
                style = Stroke(width = strokePx, cap = StrokeCap.Round),
                topLeft = Offset(strokePx / 2, strokePx / 2),
                size = Size(size.width - strokePx, size.height - strokePx),
            )
        }
        content()
    }
}

@Composable
private fun Sparkline(values: List<Int>, color: Color, modifier: Modifier = Modifier) {
    if (values.size < 2) return
    val min = values.min()
    val max = values.max()
    val range = (max - min).coerceAtLeast(1)
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val step = w / (values.size - 1)
        val pts = values.mapIndexed { i, v ->
            Offset(i * step, h - 4f - ((v - min).toFloat() / range) * (h - 8f))
        }
        // Area fill
        val area = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            pts.drop(1).forEach { lineTo(it.x, it.y) }
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        drawPath(area, color.copy(alpha = 0.12f))
        // Line
        val line = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            pts.drop(1).forEach { lineTo(it.x, it.y) }
        }
        drawPath(line, color, style = Stroke(width = 2.5f.dp.toPx(), cap = StrokeCap.Round))
        // End dot
        drawCircle(color, radius = 3.5f.dp.toPx(), center = pts.last())
    }
}

private fun formatNumber(v: Double): String {
    val s = v.toString()
    return if (s.endsWith(".0")) s.dropLast(2) else s
}

/**
 * Compact relative-time label for the reminders widget. Returns "demain",
 * "ven.", "dans 3 j", "8 mai" etc. so the trailing column stays short.
 */
private fun formatRelative(atMs: Long): String {
    val now = System.currentTimeMillis()
    val diffMs = atMs - now
    if (diffMs < 0) return "—"
    val days = (diffMs / 86_400_000L).toInt()
    return when {
        days == 0 -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(atMs))
        days == 1 -> "demain"
        days in 2..6 -> SimpleDateFormat("EEE", Locale.getDefault())
            .format(Date(atMs)).trimEnd('.')
        days in 7..29 -> "dans $days j"
        else -> SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(atMs))
    }
}
