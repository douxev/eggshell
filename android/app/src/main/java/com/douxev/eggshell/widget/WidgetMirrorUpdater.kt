package com.douxev.eggshell.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import com.douxev.eggshell.R
import com.douxev.eggshell.data.SportPrefs
import com.douxev.eggshell.data.VaultRepository
import com.douxev.eggshell.modules.AppModule
import com.douxev.eggshell.reminders.LabReminderPrefs
import com.douxev.eggshell.security.VaultPrefs
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Fills [WidgetContentMirror] from the vault, and keeps it honest.
 *
 * The only writer. Every rule the mirror states is enforced in one place here
 * rather than at each call site, because the call sites are "somewhere a note
 * changed" and there will be more of them than anyone remembers to audit:
 *
 * - Only adds rows while the vault is open — there is nothing to read
 *   otherwise. A locked vault keeps the rows it has (the mirror outlives a
 *   lock on purpose, see [WidgetContentMirror]) but still loses those of any
 *   widget that was removed or whose opt-in was withdrawn.
 * - Erases everything in paranoid mode, whatever any widget was configured to
 *   do ([WidgetContentMirror.writable]).
 * - Erases everything while a decoy PIN is set — the widgets are disabled then
 *   anyway, but a placed one keeps its last render, and this is what makes sure
 *   that render had nothing in it.
 * - Only reads for widget instances that actually asked
 *   ([WidgetConfigPrefs.Config.showsContent]).
 *
 * **Cost.** Repositories ask for a rebuild after every write, so [refresh] does
 * not do the work: it queues it, on a scope of its own, behind a short
 * debounce. A burst of writes (a journal entry and its sliders, a pedometer
 * flush) costs one rebuild, and no write waits on it — a mirror that failed to
 * rebuild must never turn a saved entry into an error.
 */
@Singleton
class WidgetMirrorUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vault: VaultRepository,
    private val prefs: VaultPrefs,
    private val sportPrefs: SportPrefs,
) {

    private val configs by lazy { WidgetConfigPrefs(context) }
    private val mirror by lazy { WidgetContentMirror(context) }

    /**
     * Outlives every screen: a rebuild asked for by a write must still happen
     * when the screen that made the write has gone. SupervisorJob so one failed
     * rebuild does not end the queue.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Conflated: any number of requests during a wait or a run fold into one. */
    private val requests = Channel<Unit>(Channel.CONFLATED)

    /** One rebuild at a time, queued or awaited. */
    private val rebuilding = Mutex()

    init {
        scope.launch {
            for (request in requests) {
                delay(DEBOUNCE_MS)
                // Whatever arrived during the wait is covered by this run.
                requests.tryReceive()
                try {
                    refreshNow()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    android.util.Log.e(TAG, "widget mirror rebuild failed", failure)
                }
            }
        }
    }

    /**
     * Ask for the mirror to be rebuilt soon, then repainted. Returns at once.
     *
     * Safe to call often and from anywhere, including the main thread: it is a
     * queue push. When nothing is opted in — the default, and for most users
     * permanent — the rebuild it leads to does nothing at all, not even a
     * repaint.
     */
    fun refresh() {
        requests.trySend(Unit)
    }

    /**
     * Rebuild now and return when it is done.
     *
     * For the one caller that has to wait: the configuration screen, which must
     * not hand the launcher its result before the widget it just configured has
     * its rows (see [WidgetConfigActivity]).
     */
    suspend fun refreshNow() {
        rebuilding.withLock { withContext(Dispatchers.IO) { rebuild() } }
    }

    private fun rebuild() {
        // Captured before anything is read. An erase or a session change from
        // here on makes whatever this run computes unwanted, and commitIf is
        // what notices.
        val epoch = WidgetContentMirror.epoch()

        if (decoyActive() || !WidgetContentMirror.writable(prefs.mode)) {
            // Paranoid or decoy: nothing may sit in the mirror, whatever any
            // widget was once configured to do. Already empty — the usual
            // case, since both are erased when they begin — means nothing to
            // withdraw and nothing to repaint: this runs after every write.
            if (mirror.all().isNotEmpty()) withdraw(context)
            return
        }

        val placed = placedWidgets() ?: return
        sweepConfigs(placed)

        // Grouped by module: the rows are built per module, and handing one
        // module's rows to another's widget would put content under a heading
        // that promises something else.
        val wanted = placed
            .filter { (module, _) -> module in MIRRORED_MODULES }
            .filter { (_, id) -> configs.get(id).showsContent }

        if (!vault.isUnlocked) {
            // Nothing to read, and nothing to add: the rows from the last open
            // session stay, by design. But a widget removed or opted out while
            // the app was shut loses its rows now, not at the next unlock.
            if (mirror.retainOnly(wanted.map { it.second }.toSet())) WidgetRefresh.refreshAll(context)
            return
        }

        if (wanted.isEmpty()) {
            // Nothing opted in. Make sure nothing is left over from a widget
            // that used to be opted in, and otherwise do nothing at all — no
            // repaint either: this runs after every write, for everyone.
            if (mirror.all().isNotEmpty()) {
                mirror.clear()
                WidgetRefresh.refreshAll(context)
            }
            return
        }

        val previous = mirror.all()
        val built = wanted.associate { (module, widgetId) ->
            val rows = try {
                rowsFor(module, configs.get(widgetId))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // Keep what the widget already shows rather than blanking it:
                // a read that failed (the vault locking under us, most often)
                // says nothing about whether the content changed.
                android.util.Log.e(TAG, "mirror rows failed for $module", failure)
                null
            }
            widgetId to (rows ?: previous[widgetId].orEmpty())
        }

        val committed = mirror.commitIf(epoch) {
            // Re-checked under the store's lock, so an erase cannot slip in
            // between this check and the write.
            if (decoyActive() || !WidgetContentMirror.writable(prefs.mode) || !vault.isUnlocked) {
                null
            } else {
                // Rows only for widgets still opted in now; everything else —
                // removed widgets, opt-outs — is dropped by not being carried.
                built.filterKeys { configs.get(it).showsContent }
            }
        }
        if (committed) WidgetRefresh.refreshAll(context)
    }

    private fun decoyActive(): Boolean = prefs.decoy() != null

    /**
     * Every placed module widget, with its module, or null when the system
     * could not be asked — in which case nothing is swept on its word.
     */
    private fun placedWidgets(): List<Pair<AppModule, Int>>? {
        val mgr = AppWidgetManager.getInstance(context)
        val out = mutableListOf<Pair<AppModule, Int>>()
        for ((component, provider) in moduleWidgets(context)) {
            val ids = try {
                mgr.getAppWidgetIds(component)
            } catch (failure: RuntimeException) {
                android.util.Log.w(TAG, "could not list widgets of $component", failure)
                return null
            }
            ids.forEach { out += provider.module to it }
        }
        return out
    }

    /** Drop configuration rows whose widget the system no longer knows about. */
    private fun sweepConfigs(placed: List<Pair<AppModule, Int>>) {
        val dropped = configs.retainOnly(placed.map { it.second }.toSet())
        if (dropped > 0) android.util.Log.i(TAG, "dropped $dropped stale widget configuration rows")
    }

    /**
     * The rows one module's widget should show.
     *
     * Each module answers the question someone glances at a home screen to
     * ask, which is rarely "list everything": the next few appointments, the
     * last check-in's gauges, how the week went. Lists where the next few is
     * the answer (Notes, Rendez-vous); a summary everywhere else.
     *
     * Read through the session rather than through each module's repository:
     * the repositories are the natural callers of [refresh] after a write, and
     * depending on them here would make that a Hilt cycle.
     */
    private fun rowsFor(
        module: AppModule,
        config: WidgetConfigPrefs.Config,
    ): List<WidgetContentMirror.Row> {
        val limit = config.rows.coerceIn(1, MAX_ROWS)
        val now = System.currentTimeMillis()
        return when (module) {
            AppModule.Notes -> notesRows(config, limit)
            AppModule.Journal -> journalRows(now)
            AppModule.Appointments -> appointmentRows(limit, now)
            AppModule.Bleeding -> bleedingRows(now)
            // Never a thumbnail. The launcher caches a widget's bitmaps in its
            // own process, where the app's onStop cache purge cannot reach them
            // — a progress photo put here would outlive every lock.
            AppModule.Photos -> mediaRows(
                vault.requireSession().listPhotoRecords(0, MEDIA_SCAN).map { it.atMs },
                R.plurals.widget_photos_count,
                R.string.widget_photos_count_capped,
                R.string.widget_photos_last,
                now,
            )
            AppModule.Voice -> mediaRows(
                vault.requireSession().listVoiceClips(0, MEDIA_SCAN).map { it.atMs },
                R.plurals.widget_voice_count,
                R.string.widget_voice_count_capped,
                R.string.widget_voice_last,
                now,
            )
            AppModule.Dreams -> dreamRows(now)
            AppModule.Sport -> sportRows(now)
            // Their content already lives off-vault and their providers read it
            // directly; nothing is mirrored for them. Poids has no widget.
            AppModule.Meds, AppModule.Labs, AppModule.Weight -> emptyList()
        }
    }

    // ------------------------------------------------------------ Notes --

    /**
     * The rows a Notes widget should show, per its configured target.
     *
     * `recent` is the root's newest notes; `folder` is one folder's, in the
     * manual order the user arranged; `note` is a single note, shown with the
     * opening of its body rather than one line — that is the whole point of
     * pointing a widget at one note.
     */
    private fun notesRows(
        config: WidgetConfigPrefs.Config,
        limit: Int,
    ): List<WidgetContentMirror.Row> {
        val session = vault.requireSession()
        return when (config.targetKind) {
            TARGET_NOTE -> {
                val id = config.targetId ?: return emptyList()
                val note = session.getNote(id) ?: return emptyList()
                listOf(
                    WidgetContentMirror.Row(
                        title = note.title.ifBlank { context.getString(R.string.notes_untitled) },
                        subtitle = note.body.trim(),
                        targetId = note.id,
                    )
                )
            }
            TARGET_FOLDER -> session.listNotes(config.targetId).take(limit).map(::toRow)
            // Default and "recent": the root, newest first. Root rather than
            // every folder flattened — a widget that surfaced notes out of a
            // folder the user filed them into would defeat the filing.
            else -> session.listNotes(null)
                .sortedByDescending { it.updatedMs }
                .take(limit)
                .map(::toRow)
        }
    }

    private fun toRow(note: uniffi.transition.Note) = WidgetContentMirror.Row(
        title = note.title.ifBlank { context.getString(R.string.notes_untitled) },
        subtitle = note.body.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
            .trim().take(PREVIEW_LIMIT),
        targetId = note.id,
    )

    // ---------------------------------------------------------- Journal --

    /**
     * The latest entry's gauges, and the week around it.
     *
     * Row [JournalRows.HEADER] says when the entry was written and how the
     * week went; the rows after it are one gauge each, in the order the
     * journal shows them, in the colour the journal gives them. The free text
     * is never here — see the layout's own note on that.
     */
    private fun journalRows(now: Long): List<WidgetContentMirror.Row> {
        val session = vault.requireSession()
        val recent = session.listJournalEntries(0, JOURNAL_SCAN)
        val last = recent.maxByOrNull { it.atMs } ?: return emptyList()

        val week = recent.filter { it.atMs >= now - WEEK_MS }
        val moods = week.mapNotNull { it.mood?.toInt() }
        val summary = listOfNotNull(
            context.resources.getQuantityString(R.plurals.widget_journal_week, week.size, week.size),
            moods.takeIf { it.isNotEmpty() }?.let { m ->
                context.getString(
                    R.string.widget_journal_mood_avg,
                    String.format(Locale.getDefault(), "%.1f", m.average()),
                )
            },
        ).joinToString(" · ")

        val rows = mutableListOf(
            WidgetContentMirror.Row(
                title = WidgetTime.since(context, last.atMs, now) + " · " + clock(last.atMs),
                subtitle = summary,
                targetId = last.id,
            )
        )

        val defs = session.listMetricDefinitions(DOMAIN_JOURNAL, false)
            .filter { it.enabled && !it.archived }
            .sortedBy { it.sortOrder }
        val custom by lazy {
            session.listMetricValues(DOMAIN_JOURNAL, last.id).associate { it.metricId to it.value }
        }
        defs.forEachIndexed { index, def ->
            val value = (def.columnName?.let { journalColumn(last, it) } ?: custom[def.id])
                ?: return@forEachIndexed
            if (rows.size > JournalRows.MAX_GAUGES) return@forEachIndexed
            rows += WidgetContentMirror.Row(
                // Built-in gauges are stored with an empty label and named by
                // the app, in the user's language — the same lookup the
                // journal screen does (metricLabel), outside Compose.
                title = WidgetRowRules.gaugeLabel(
                    label = def.label,
                    metricKey = def.metricKey,
                    builtinRes = com.douxev.eggshell.ui.common.builtinLabelRes(def.domain, def.metricKey),
                    resolve = context::getString,
                ),
                subtitle = "",
                targetId = last.id,
                badge = value.toString(),
                progress = value.toInt(),
                max = def.maxValue.toInt().coerceAtLeast(1),
                accent = WidgetPalette.Role.forMetric(def.metricKey, index).ordinal,
            )
        }
        return rows
    }

    /** Built-in journal gauges keep their value in the entry's own columns. */
    private fun journalColumn(entry: uniffi.transition.JournalEntry, column: String): UInt? =
        when (column) {
            "mood" -> entry.mood
            "dysphoria" -> entry.dysphoria
            "euphoria" -> entry.euphoria
            "libido" -> entry.libido
            "energy" -> entry.energy
            else -> null
        }

    // ------------------------------------------------------ Rendez-vous --

    private fun appointmentRows(limit: Int, now: Long): List<WidgetContentMirror.Row> =
        vault.requireSession().listAppointments(0, APPOINTMENT_SCAN)
            .filter { it.atMs >= now }
            .sortedBy { it.atMs }
            .take(limit)
            .map { a ->
                WidgetContentMirror.Row(
                    title = a.professionalName?.takeIf { it.isNotBlank() }
                        ?: a.place?.takeIf { it.isNotBlank() }
                        ?: context.getString(R.string.widget_appointment_generic),
                    subtitle = WidgetTime.relative(context, a.atMs, now) + " · " + clock(a.atMs),
                    targetId = a.id,
                    badge = WidgetTime.dateTile(a.atMs),
                )
            }

    // ---------------------------------------------------- Menstruations --

    /**
     * The cycle day — "J4" is what someone tracking a cycle actually reads —
     * and how many days were recorded over the last thirty.
     *
     * The day is counted from the *first* day of the latest bleed, not from the
     * latest entry: counting from the latest entry read "J1" every day of a
     * period logged day by day, and afterwards the days since it ended, neither
     * of which is a cycle day. Spotting does not start a bleed. See
     * [WidgetRowRules.cycleDay] for what counts as one bleed.
     *
     * No prediction: the module records what happened and never guesses what
     * will, and the widget keeps to the same rule.
     */
    private fun bleedingRows(now: Long): List<WidgetContentMirror.Row> {
        val entries = vault.requireSession().listBleedingEntries(0, BLEEDING_SCAN)
        val last = entries.filter { it.isSpotting != true }.maxByOrNull { it.atMs }
            ?: entries.maxByOrNull { it.atMs }
            ?: return emptyList()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val bleedingDays = entries
            .filter { it.isSpotting != true }
            .map { Instant.ofEpochMilli(it.atMs).atZone(zone).toLocalDate() }
        val cycleDay = WidgetRowRules.cycleDay(bleedingDays, today)

        val noted = entries
            .filter { it.atMs >= now - THIRTY_DAYS_MS }
            .map { Instant.ofEpochMilli(it.atMs).atZone(zone).toLocalDate() }
            .distinct()
            .size

        return listOf(
            WidgetContentMirror.Row(
                title = context.getString(R.string.widget_bleeding_last),
                subtitle = WidgetTime.since(context, last.atMs, now),
                targetId = last.id,
                // No tile when only spotting was ever logged: there is no bleed
                // to count from, and a made-up day would be the guess the
                // module refuses to make.
                badge = cycleDay?.let { context.getString(R.string.widget_bleeding_day, it) }.orEmpty(),
            ),
            WidgetContentMirror.Row(
                title = context.resources.getQuantityString(
                    R.plurals.widget_bleeding_days_30, noted, noted,
                ),
                subtitle = "",
                targetId = 0L,
            ),
        )
    }

    // ---------------------------------------------------- Photos / Voix --

    /**
     * "12 photos · dernière il y a 3 j".
     *
     * The next capture reminder is not mirrored: it already lives off-vault
     * ([LabReminderPrefs]) and the provider reads it at draw time
     * ([WidgetReminderRow]), so it moves on when the alarm fires rather than at
     * the next vault write.
     *
     * The count is read over at most [MEDIA_SCAN] rows — the core has no count
     * query — so a library that fills the scan says « 500+ » instead of
     * claiming exactly 500.
     */
    private fun mediaRows(
        timestamps: List<Long>,
        @androidx.annotation.PluralsRes countPlural: Int,
        @androidx.annotation.StringRes cappedFmt: Int,
        @androidx.annotation.StringRes lastFmt: Int,
        now: Long,
    ): List<WidgetContentMirror.Row> {
        if (timestamps.isEmpty()) return emptyList()
        val count = timestamps.size
        val last = timestamps.max()
        val countText = if (WidgetRowRules.isCapped(count, MEDIA_SCAN)) {
            context.getString(cappedFmt, MEDIA_SCAN.toInt())
        } else {
            context.resources.getQuantityString(countPlural, count, count)
        }
        return listOf(
            WidgetContentMirror.Row(
                title = countText,
                subtitle = context.getString(lastFmt, WidgetTime.since(context, last, now)),
                targetId = 0L,
                badge = WidgetTime.dateTile(last),
            ),
        )
    }

    // ------------------------------------------------------------ Rêves --

    private fun dreamRows(now: Long): List<WidgetContentMirror.Row> {
        val session = vault.requireSession()
        val last = session.listDreams(null, 1, 0).firstOrNull() ?: return emptyList()
        val zone = ZoneId.systemDefault()
        val monthStart = LocalDate.now(zone).withDayOfMonth(1).atStartOfDay(zone)
            .toInstant().toEpochMilli()
        val nights = session.listDreamsBetween(monthStart, now)
            .map { Instant.ofEpochMilli(it.nightMs).atZone(zone).toLocalDate() }
            .distinct()
            .size
        return listOfNotNull(
            WidgetContentMirror.Row(
                title = last.title.ifBlank { context.getString(R.string.widget_dream_untitled) },
                subtitle = listOfNotNull(
                    WidgetTime.since(context, last.nightMs, now),
                    context.getString(R.string.widget_dream_lucid).takeIf { last.lucid },
                ).joinToString(" · "),
                targetId = last.id,
                badge = WidgetTime.dateTile(last.nightMs),
            ),
            WidgetContentMirror.Row(
                title = context.resources.getQuantityString(
                    R.plurals.widget_dreams_month, nights, nights,
                ),
                subtitle = "",
                targetId = 0L,
            ),
            // The next dream reminder is drawn live by the provider, like the
            // media widgets' (see mediaRows).
        )
    }

    // ------------------------------------------------------------ Sport --

    /**
     * The dashboard, row by row in the order [SportRows] fixes: three stat
     * tiles, today's steps, the next planned session, the last one logged. The
     * next session's slot is left blank: the provider fills it at draw time
     * from the off-vault reminder store.
     *
     * Positions are fixed and blanks are kept, because the provider reads by
     * index. Computed the same way the module screen computes them
     * ([com.douxev.eggshell.ui.sport.SportStats]) rather than re-derived here:
     * a widget and a screen disagreeing about whether someone's run is still
     * alive is worse than either being wrong on its own.
     */
    private fun sportRows(now: Long): List<WidgetContentMirror.Row> {
        val session = vault.requireSession()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val firstDayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek
        val weekStart = today.with(TemporalAdjusters.previousOrSame(firstDayOfWeek))
        val fromMs = weekStart.atStartOfDay(zone).toInstant().toEpochMilli()
        val toMs = weekStart.plusWeeks(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val sessions = session.listSportSessions(0, SPORT_SCAN)
        val stats = com.douxev.eggshell.ui.sport.SportStats
        val minutes = stats.minutesBetween(sessions, fromMs, toMs)
        val count = stats.countBetween(sessions, fromMs, toMs)
        val streak = stats.currentStreak(stats.sessionDays(sessions, zone), today)

        val blank = WidgetContentMirror.Row("", "", 0L)
        val rows = arrayOfNulls<WidgetContentMirror.Row>(SportRows.COUNT)
        rows[SportRows.WEEK_SESSIONS] = WidgetContentMirror.Row(
            title = count.toString(),
            subtitle = context.getString(R.string.sport_stat_week_sessions),
            targetId = 0L,
        )
        rows[SportRows.WEEK_TIME] = WidgetContentMirror.Row(
            title = duration(minutes * 60),
            subtitle = context.getString(R.string.sport_stat_week_time),
            targetId = 0L,
        )
        rows[SportRows.STREAK] = WidgetContentMirror.Row(
            title = streak.toString(),
            subtitle = context.getString(R.string.sport_stat_streak),
            targetId = 0L,
        )

        if (sportPrefs.pedometer.value) {
            val steps = runCatching {
                session.getStepDay(com.douxev.eggshell.data.SportRepository.dayKey(today))?.steps
            }.getOrNull() ?: 0L
            val goal = sportPrefs.dailyGoal.value
            val numbers = java.text.NumberFormat.getIntegerInstance()
            rows[SportRows.STEPS] = WidgetContentMirror.Row(
                title = context.getString(R.string.widget_sport_steps, numbers.format(steps)),
                subtitle = context.getString(R.string.widget_sport_steps_goal, numbers.format(goal)),
                // The day this count belongs to. The provider tops it up from
                // the pedometer's ledger at draw time and must know when the
                // mirrored figure is yesterday's — it used to read "8 000 pas
                // aujourd'hui" all morning after an 8 000-step day.
                targetId = today.toEpochDay(),
                progress = steps.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                max = goal,
                accent = WidgetPalette.Role.SUCCESS.ordinal,
            )
        }

        // SportRows.NEXT stays blank here: the next session comes from the
        // off-vault reminder store, and the provider reads it at draw time so
        // it moves on when the alarm fires (WidgetReminderRow).

        sessions.maxByOrNull { it.startedMs }?.let { last ->
            val name = last.activityId?.let { id ->
                session.listSportActivities(true).firstOrNull { it.id == id }?.name
            } ?: context.getString(R.string.sport_no_activity)
            rows[SportRows.LAST] = WidgetContentMirror.Row(
                title = name,
                subtitle = WidgetTime.since(context, last.startedMs, now) + " · " +
                    duration(last.durationS),
                targetId = last.id,
            )
        }
        return rows.map { it ?: blank }
    }

    /** "1 h 05" or "45 min" — the module's own format, outside Compose. */
    private fun duration(seconds: Long): String {
        val minutes = seconds / 60
        return if (minutes >= 60) {
            context.getString(R.string.sport_duration_hm, (minutes / 60).toInt(), (minutes % 60).toInt())
        } else {
            context.getString(R.string.sport_duration_m, minutes.toInt())
        }
    }

    // ---------------------------------------------------------- helpers --

    private fun clock(atMs: Long): String =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
            .withLocale(Locale.getDefault())
            .format(Instant.ofEpochMilli(atMs).atZone(ZoneId.systemDefault()))

    /** Where each Journal row lands; the provider reads by these. */
    object JournalRows {
        const val HEADER = 0
        /** What widget_journal.xml has room for. */
        const val MAX_GAUGES = 6
    }

    /** Where each Sport row lands; the provider reads by these. */
    object SportRows {
        const val WEEK_SESSIONS = 0
        const val WEEK_TIME = 1
        const val STREAK = 2
        const val STEPS = 3
        const val NEXT = 4
        const val LAST = 5
        const val COUNT = 6
    }

    companion object {
        private const val TAG = "WidgetMirror"
        const val TARGET_FOLDER = "folder"
        const val TARGET_NOTE = "note"
        /** A Traitements widget pointed at one medication; the id is its medicationId. */
        const val TARGET_MEDICATION = "medication"
        /** What widget_content.xml has room for. */
        const val MAX_ROWS = 4
        /** One line of a list preview. A single note is allowed more. */
        const val PREVIEW_LIMIT = 90

        private const val DOMAIN_JOURNAL = "journal"
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val WEEK_MS = 7 * DAY_MS
        private const val THIRTY_DAYS_MS = 30 * DAY_MS

        /**
         * How deep to read for the modules with no "give me the newest N" query.
         * Bounded rather than unbounded: this runs on every note edit and every
         * unlock, and a vault with years of photos should not pay for it.
         */
        private const val APPOINTMENT_SCAN = 200L
        private const val JOURNAL_SCAN = 200L
        /** Enough to reach back to the first day of the latest bleed. */
        private const val BLEEDING_SCAN = 120L
        private const val MEDIA_SCAN = 500L
        private const val SPORT_SCAN = 1_000L

        /**
         * How long a requested rebuild waits for more requests. Long enough to
         * fold a journal entry and its sliders, or a batch of bleeding days,
         * into one rebuild; short enough that the home screen has caught up by
         * the time anyone goes to look.
         */
        private const val DEBOUNCE_MS = 400L

        /**
         * The modules whose widgets read the opt-in mirror.
         *
         * Traitements and Analyses are absent on purpose: their content already
         * lives off-vault so their reminders can fire with the app shut, their
         * providers read it directly, and nothing about them needs an opt-in.
         */
        private val MIRRORED_MODULES = listOf(
            AppModule.Notes,
            AppModule.Journal,
            AppModule.Appointments,
            AppModule.Bleeding,
            AppModule.Photos,
            AppModule.Voice,
            AppModule.Dreams,
            AppModule.Sport,
        )

        /**
         * Erase every mirrored row and repaint, so nothing is left on the home
         * screen.
         *
         * Both halves, always: a launcher keeps the last RemoteViews it was
         * handed until something replaces them, so an erase without a repaint
         * leaves the withdrawn content on screen exactly as before.
         *
         * Static, because the callers that need it most — the vault, the decoy
         * switch — sit below this class in the dependency graph and cannot
         * inject it.
         */
        fun withdraw(context: Context) {
            runCatching { WidgetContentMirror(context).clear() }
            runCatching { WidgetRefresh.refreshAll(context) }
        }

        /**
         * Forget widgets entirely — they were removed from the home screen.
         *
         * Both rows go: launchers reuse widget ids, and a row left behind is the
         * next widget placed in that slot inheriting this one's target, its
         * content, and a consent it was never given.
         */
        fun forget(context: Context, widgetIds: IntArray) {
            runCatching { WidgetConfigPrefs(context).remove(widgetIds) }
            runCatching { WidgetContentMirror(context).remove(widgetIds) }
        }

        /**
         * Withdraw every opt-in, permanently, and erase what they put out.
         *
         * For the move to paranoid mode and for a decoy PIN being set: the
         * consent was given under a different promise, and quietly honouring it
         * afterwards — or once the decoy is removed — would put content back on
         * the home screen that the user's latest choice says cannot be there.
         * They turn it back on widget by widget if they want it.
         */
        fun revokeAllOptIns(context: Context) {
            runCatching { WidgetConfigPrefs(context).revokeAllContent() }
            withdraw(context)
        }

        /**
         * A vault wipe: every row of both stores goes, configuration included —
         * the widgets' targets point into a vault that no longer exists.
         */
        fun eraseAll(context: Context) {
            runCatching { WidgetConfigPrefs(context).clearAll() }
            withdraw(context)
        }
    }
}
