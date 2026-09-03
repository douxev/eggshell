package com.douxev.eggshell.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import com.douxev.eggshell.R
import com.douxev.eggshell.data.VaultRepository
import com.douxev.eggshell.modules.AppModule
import com.douxev.eggshell.security.VaultPrefs
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fills [WidgetContentMirror] from the vault, and empties it again.
 *
 * The only writer. Every rule the mirror states is enforced in one place here
 * rather than at each call site, because the call sites are "somewhere a note
 * changed" and there will be more of them than anyone remembers to audit:
 *
 * - Refuses outright unless the vault is open. There is nothing to read
 *   otherwise, and a stale mirror is worse than an empty one.
 * - Refuses in paranoid mode, whatever any widget was configured to do
 *   ([WidgetContentMirror.writable]).
 * - Refuses while a decoy PIN is set — the widgets are disabled then anyway,
 *   but a placed one keeps its last render, and this is what makes sure that
 *   render had nothing in it.
 * - Only reads for widget instances that actually asked
 *   ([WidgetConfigPrefs.Config.showsContent]).
 */
@Singleton
class WidgetMirrorUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vault: VaultRepository,
    private val prefs: VaultPrefs,
) {

    private val configs by lazy { WidgetConfigPrefs(context) }
    private val mirror by lazy { WidgetContentMirror(context) }

    /**
     * Rebuild the mirror for every placed widget, then repaint.
     *
     * Safe to call often and from anywhere: it is a no-op when nothing opted
     * in, which is the default and, for most users, permanent.
     */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        if (!allowed()) {
            withdraw()
            return@withContext
        }
        // Grouped by module: the rows are built per module, and handing one
        // module's rows to another's widget would put content under a heading
        // that promises something else.
        val wanted = MIRRORED_MODULES
            .flatMap { module -> placedWidgetIds(module).map { module to it } }
            .filter { (_, id) -> configs.get(id).showsContent }
        if (wanted.isEmpty()) {
            // Nothing opted in. Make sure nothing is left over from a widget
            // that used to be opted in and no longer is.
            if (mirror.all().isNotEmpty()) mirror.clear()
            return@withContext
        }
        wanted.forEach { (module, widgetId) ->
            val rows = runCatching { rowsFor(module, configs.get(widgetId)) }
                .getOrDefault(emptyList())
            mirror.put(widgetId, rows)
        }
        // Drop rows belonging to widgets that are gone or no longer opted in.
        val stale = mirror.all().keys - wanted.map { it.second }.toSet()
        if (stale.isNotEmpty()) mirror.remove(stale.toIntArray())

        WidgetRefresh.refreshAll(context)
    }

    /**
     * Empty the mirror and repaint, so nothing is left on the home screen.
     *
     * Called on lock. Not merely tidy: a launcher keeps the last RemoteViews it
     * was handed until something replaces them, so without both halves — the
     * erase *and* the repaint — locking the app would leave the note titles
     * sitting on the home screen exactly as before.
     */
    fun withdraw() {
        runCatching { mirror.clear() }
        runCatching { WidgetRefresh.refreshAll(context) }
    }

    /** Forget one widget entirely — it was removed from the home screen. */
    fun forget(widgetIds: IntArray) {
        runCatching { configs.remove(widgetIds) }
        runCatching { mirror.remove(widgetIds) }
    }

    /**
     * Withdraw every opt-in, permanently.
     *
     * For the move to paranoid mode: the consent was given under a different
     * promise, and quietly honouring it afterwards would leave content on the
     * home screen that the mode the user just chose says cannot be there.
     */
    fun revokeAllOptIns() {
        runCatching { configs.revokeAllContent() }
        withdraw()
    }

    private fun allowed(): Boolean =
        vault.isUnlocked &&
            WidgetContentMirror.writable(prefs.mode) &&
            prefs.decoy() == null

    private fun placedWidgetIds(module: AppModule): List<Int> {
        val mgr = AppWidgetManager.getInstance(context)
        return moduleWidgets(context)
            .filter { (_, provider) -> provider.module == module }
            .flatMap { (component, _) ->
                // Throws when the component is disabled, which is the decoy
                // state — and there, no ids is the right answer anyway.
                runCatching { mgr.getAppWidgetIds(component).toList() }.getOrDefault(emptyList())
            }
    }

    /**
     * The rows one module\'s widget should show.
     *
     * Two shapes, and which one a module gets is a judgement about what is
     * worth putting on a home screen rather than a technical limit:
     *
     * - **Lists** (Notes, Journal, Rendez-vous) — several rows, because the
     *   next few of them is the useful answer.
     * - **Summaries** (Poids, Menstruations, Photos, Voix, Rêves) — one row,
     *   because "how long since" and "how many" is what you glance at, and a
     *   list of dates is not.
     *
     * Read through the session rather than through each module\'s repository:
     * the repositories are the natural callers of [refresh] after a write, and
     * depending on them here would make that a Hilt cycle.
     */
    private fun rowsFor(
        module: AppModule,
        config: WidgetConfigPrefs.Config,
    ): List<WidgetContentMirror.Row> {
        val limit = config.rows.coerceIn(1, MAX_ROWS)
        val session = vault.requireSession()
        val now = System.currentTimeMillis()
        return when (module) {
            AppModule.Notes -> notesRows(config, limit)

            AppModule.Journal -> session.listJournalEntries(0, limit.toLong()).map { e ->
                WidgetContentMirror.Row(
                    title = WidgetTime.since(context, e.atMs),
                    // The gauges, not the free text. A journal entry\'s prose is
                    // the most private thing in this app and has no business on
                    // a home screen, opt-in or not.
                    subtitle = gaugeDigest(e),
                    targetId = e.id,
                )
            }

            AppModule.Appointments -> session.listAppointments(0, APPOINTMENT_SCAN)
                .filter { it.atMs >= now }
                .sortedBy { it.atMs }
                .take(limit)
                .map { a ->
                    WidgetContentMirror.Row(
                        title = a.professionalName
                            ?: a.place
                            ?: context.getString(R.string.widget_appointment_generic),
                        subtitle = WidgetTime.relative(context, a.atMs),
                        targetId = a.id,
                    )
                }

            AppModule.Weight -> session
                .listHormoneMeasurements(WEIGHT_KEY, 0, WEIGHT_SCAN)
                .sortedByDescending { it.atMs }
                .let { history ->
                    val last = history.firstOrNull() ?: return emptyList()
                    listOf(
                        WidgetContentMirror.Row(
                            title = "%.1f %s".format(last.value, last.unit),
                            subtitle = trendLabel(history, now),
                            targetId = last.id,
                        )
                    )
                }

            AppModule.Bleeding -> session.listBleedingEntries(0, 1)
                .firstOrNull()
                ?.let {
                    listOf(
                        WidgetContentMirror.Row(
                            title = context.getString(R.string.widget_bleeding_last),
                            subtitle = WidgetTime.since(context, it.atMs),
                            targetId = it.id,
                        )
                    )
                }
                .orEmpty()

            // Never a thumbnail. The launcher caches a widget\'s bitmaps in its
            // own process, where the app\'s onStop cache purge cannot reach them
            // — a progress photo put here would outlive every lock.
            AppModule.Photos -> countAndLast(
                session.listPhotoRecords(0, MEDIA_SCAN).map { it.atMs },
                R.plurals.widget_photos_count,
                now,
            )

            AppModule.Voice -> countAndLast(
                session.listVoiceClips(0, MEDIA_SCAN).map { it.atMs },
                R.plurals.widget_voice_count,
                now,
            )

            AppModule.Dreams -> session.listDreams(null, 1, 0)
                .firstOrNull()
                ?.let {
                    listOf(
                        WidgetContentMirror.Row(
                            title = it.title.ifBlank {
                                context.getString(R.string.widget_dream_untitled)
                            },
                            subtitle = WidgetTime.since(context, it.nightMs),
                            targetId = it.id,
                        )
                    )
                }
                .orEmpty()

            // Their content already lives off-vault and their providers read it
            // directly; nothing is mirrored for them.
            AppModule.Meds, AppModule.Labs -> emptyList()

            // The dashboard's numbers rather than a list of sessions: a widget
            // showing "3 séances cette semaine · 4 jours d'affilée" answers the
            // question someone glances at their home screen to ask. A list of
            // dates does not.
            AppModule.Sport -> sportRows(now)
        }
    }

    /**
     * The rows a Notes widget should show, per its configured target.
     *
     * `recent` is the root\'s newest notes; `folder` is one folder\'s, in the
     * manual order the user arranged; `note` is a single note.
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
                listOf(toRow(note))
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
        subtitle = note.body.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty(),
        targetId = note.id,
    )

    /**
     * This week and the current streak, as one line each.
     *
     * The streak is computed the same way the module screen computes it
     * ([com.douxev.eggshell.ui.sport.SportStats]) rather than re-derived here:
     * a widget and a screen disagreeing about whether someone's run is still
     * alive is worse than either being wrong on its own.
     */
    private fun sportRows(now: Long): List<WidgetContentMirror.Row> {
        val zone = java.time.ZoneId.systemDefault()
        val today = java.time.LocalDate.now(zone)
        val firstDayOfWeek = java.time.temporal.WeekFields
            .of(java.util.Locale.getDefault()).firstDayOfWeek
        val weekStart = today.with(
            java.time.temporal.TemporalAdjusters.previousOrSame(firstDayOfWeek)
        )
        val fromMs = weekStart.atStartOfDay(zone).toInstant().toEpochMilli()
        val toMs = weekStart.plusWeeks(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val sessions = vault.requireSession().listSportSessions(0, SPORT_SCAN)
        val minutes = com.douxev.eggshell.ui.sport.SportStats
            .minutesBetween(sessions, fromMs, toMs)
        val count = com.douxev.eggshell.ui.sport.SportStats
            .countBetween(sessions, fromMs, toMs)
        val streak = com.douxev.eggshell.ui.sport.SportStats.currentStreak(
            com.douxev.eggshell.ui.sport.SportStats.sessionDays(sessions, zone), today,
        )

        val rows = mutableListOf(
            WidgetContentMirror.Row(
                title = context.resources.getQuantityString(
                    R.plurals.widget_sport_week, count, count,
                ),
                subtitle = context.getString(R.string.widget_sport_minutes, minutes.toInt()),
                targetId = 0L,
            )
        )
        // Only when there is one. "0 jours d'affilée" is a reproach, not
        // information, and it is the last thing this app should put on someone's
        // home screen.
        if (streak > 0) {
            rows += WidgetContentMirror.Row(
                title = context.resources.getQuantityString(
                    R.plurals.widget_sport_streak, streak, streak,
                ),
                subtitle = "",
                targetId = 0L,
            )
        }
        runCatching {
            vault.requireSession().getStepDay(
                com.douxev.eggshell.data.SportRepository.dayKey(today)
            )
        }.getOrNull()?.let { day ->
            if (day.steps > 0) {
                rows += WidgetContentMirror.Row(
                    title = context.getString(R.string.widget_sport_steps, day.steps.toInt()),
                    subtitle = "",
                    targetId = 0L,
                )
            }
        }
        return rows
    }

    /** "12 photos · 3 j" — how many there are, and how long since the last. */
    private fun countAndLast(
        timestamps: List<Long>,
        @androidx.annotation.PluralsRes countPlural: Int,
        now: Long,
    ): List<WidgetContentMirror.Row> {
        if (timestamps.isEmpty()) return emptyList()
        val count = timestamps.size
        return listOf(
            WidgetContentMirror.Row(
                title = context.resources.getQuantityString(countPlural, count, count),
                subtitle = WidgetTime.since(context, timestamps.max(), now),
                targetId = 0L,
            )
        )
    }

    /** The gauges an entry actually carries, as "Humeur 4 · Énergie 2". */
    private fun gaugeDigest(e: uniffi.transition.JournalEntry): String = listOfNotNull(
        e.mood?.let { context.getString(R.string.widget_gauge_mood, it.toInt()) },
        e.energy?.let { context.getString(R.string.widget_gauge_energy, it.toInt()) },
    ).joinToString(" · ")

    /**
     * Change over the last [TREND_WINDOW_MS], or nothing when there is no
     * earlier reading to compare against — an invented baseline would make a
     * first weigh-in look like a loss.
     */
    private fun trendLabel(
        history: List<uniffi.transition.HormoneMeasurement>,
        now: Long,
    ): String {
        val last = history.firstOrNull() ?: return ""
        val earlier = history.firstOrNull { it.atMs <= now - TREND_WINDOW_MS } ?: return ""
        val delta = last.value - earlier.value
        return context.getString(R.string.widget_weight_trend, "%+.1f".format(delta), last.unit)
    }

    companion object {
        const val TARGET_FOLDER = "folder"
        const val TARGET_NOTE = "note"
        /** What widget_content.xml has room for. */
        const val MAX_ROWS = 4

        /** Weight is a hormone row under this key — see HormoneCatalog. */
        private const val WEIGHT_KEY = "weight"
        /** 30 days: the window the weight trend is measured over. */
        private const val TREND_WINDOW_MS = 30L * 24 * 60 * 60 * 1000
        /**
         * How deep to read for the modules with no "give me the newest N" query.
         * Bounded rather than unbounded: this runs on every note edit and every
         * unlock, and a vault with years of photos should not pay for it.
         */
        private const val APPOINTMENT_SCAN = 200L
        private const val WEIGHT_SCAN = 200L
        private const val MEDIA_SCAN = 500L
        private const val SPORT_SCAN = 1_000L

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
            AppModule.Weight,
            AppModule.Bleeding,
            AppModule.Photos,
            AppModule.Voice,
            AppModule.Dreams,
            AppModule.Sport,
        )
    }
}
