package com.douxev.eggshell.data.pdf

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import com.douxev.eggshell.R
import com.douxev.eggshell.data.AppointmentRepository
import com.douxev.eggshell.data.BleedingRepository
import com.douxev.eggshell.data.HormoneUnitPrefs
import com.douxev.eggshell.data.HormonesRepository
import com.douxev.eggshell.data.JournalRepository
import com.douxev.eggshell.data.MedicationRepository
import com.douxev.eggshell.data.MetricsRepository
import com.douxev.eggshell.data.PdfReportExporter
import com.douxev.eggshell.data.PhotosRepository
import com.douxev.eggshell.data.PlannedDoses
import com.douxev.eggshell.data.ScheduleRepository
import com.douxev.eggshell.data.SettingsRepository
import com.douxev.eggshell.data.VoiceRepository
import com.douxev.eggshell.punctuality.DeltaLabel
import com.douxev.eggshell.punctuality.DosePoint
import com.douxev.eggshell.punctuality.axisLabel
import com.douxev.eggshell.punctuality.punctualityAxis
import com.douxev.eggshell.ui.appointments.appointmentTodoItems
import com.douxev.eggshell.ui.hormones.HormoneCatalog
import com.douxev.eggshell.ui.medication.MedicationCatalog
import uniffi.transition.DoseSchedule
import uniffi.transition.HormoneMeasurement
import uniffi.transition.JournalEntry
import uniffi.transition.TreatmentChange

/**
 * Reads the vault and produces the [ReportModel] the painter draws.
 *
 * Everything the document claims is assembled here, and the rule that governs
 * the whole file is that **no figure is invented**. A section with nothing to
 * say is dropped rather than printed above an « Aucune donnée » line, an
 * estimate says it is an estimate, and a statistic that cannot be computed is
 * replaced by the sentence explaining why — a paper handed to a doctor is the
 * last place for a reassuring zero.
 */
internal class ReportBuilder(
    private val context: Context,
    private val medications: MedicationRepository,
    private val schedules: ScheduleRepository,
    private val plannedDoses: PlannedDoses,
    private val hormones: HormonesRepository,
    private val units: HormoneUnitPrefs,
    private val journals: JournalRepository,
    private val metrics: MetricsRepository,
    private val bleeding: BleedingRepository,
    private val voice: VoiceRepository,
    private val appointments: AppointmentRepository,
    private val photos: PhotosRepository,
    private val settings: SettingsRepository,
    private val locale: Locale = Locale.getDefault(),
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val f = ReportFormats(locale)

    suspend fun build(options: PdfReportExporter.Options): ReportModel {
        val from = options.fromMs
        val to = options.toMs
        val days = ChronoUnit.DAYS.between(dateOf(from), dateOf(to)).toInt().coerceAtLeast(0)

        val sections = ArrayList<Section>()
        if (options.treatments) {
            treatmentsSection()?.let(sections::add)
            changesSection(from, to)?.let(sections::add)
            regularitySection(from, to)?.let(sections::add)
        }
        if (options.hormones) {
            hormonesSection(from, to, markers = options.treatments)?.let(sections::add)
        }
        if (options.weight) weightSection(from, to)?.let(sections::add)
        if (options.feelings) feelingsSection(from, to, days)?.let(sections::add)
        if (options.bleeding) bleedingSection(from, to)?.let(sections::add)
        if (options.voice) voiceSection(from, to)?.let(sections::add)
        if (options.questions) questionsSection()?.let(sections::add)
        if (options.photos) photosSection(from, to)?.let(sections::add)

        return ReportModel(
            title = title(from, to),
            subtitle = s(
                R.string.pdfdoc_subtitle_fmt,
                plural(R.plurals.pdfdoc_days, days),
                f.prose(System.currentTimeMillis()),
            ),
            identity = identity(),
            sections = sections,
            disclaimerLead = s(R.string.pdfdoc_disclaimer_lead),
            disclaimerBody = s(R.string.pdfdoc_disclaimer_body),
        )
    }

    /**
     * The boxed header of §7.4.2 — « PERSONNE SUIVIE » and « NÉE LE ».
     *
     * Both or neither: a frame carrying a name above an empty slot reads as a
     * form somebody forgot to finish, and §7.4.2 forbids it outright. One field
     * on its own therefore yields no box, and a vault that holds nothing yields
     * no box either — the document simply opens on section 1.
     *
     * Resolved once, here, so the measuring pass and the drawing pass are handed
     * the very same model and cannot disagree about whether the box exists: the
     * printed « n / N » depends on them laying out the same page.
     */
    private suspend fun identity(): Identity? {
        val name = safe { settings.reportPersonName() } ?: return null
        val birth = safe { settings.reportBirthDate() } ?: return null
        // The vault keeps ISO-8601; the document speaks its own locale, so the
        // long form is rebuilt here rather than stored.
        return Identity(
            name = name,
            birthDate = f.prose(birth.atStartOfDay(zone).toInstant().toEpochMilli()),
        )
    }

    private fun title(from: Long, to: Long): String {
        val sameYear = dateOf(from).year == dateOf(to).year
        val start = if (sameYear) f.proseNoYear(from) else f.prose(from)
        return s(R.string.pdfdoc_title_fmt, start, f.prose(to))
    }

    // -----------------------------------------------------------------------
    // 1 — Traitements en cours
    // -----------------------------------------------------------------------

    private suspend fun treatmentsSection(): Section? {
        val meds = safe { medications.list(includeArchived = false) }.orEmpty()
        if (meds.isEmpty()) return null
        val rows = meds.map { m ->
            val plan = safe { schedules.listForMedication(m.id, includeInactive = false) }.orEmpty()
            listOf(
                m.name,
                m.defaultDose?.let { f.value(it, m.defaultDoseUnit) }.orEmpty(),
                s(MedicationCatalog.routeLabelRes(m.route)),
                rhythm(plan),
                f.slashed(m.createdAtMs),
            )
        }
        return Section(
            title = s(R.string.pdfdoc_s_treatments),
            blocks = listOf(
                Block.Table(
                    columns = listOf(
                        Column(s(R.string.pdfdoc_col_molecule), 1.45f, strong = true),
                        Column(s(R.string.pdfdoc_col_dose), 0.70f),
                        Column(s(R.string.pdfdoc_col_route), 0.95f),
                        Column(s(R.string.pdfdoc_col_rhythm), 1.15f),
                        Column(s(R.string.pdfdoc_col_since), 0.85f, alignRight = true),
                    ),
                    rows = rows,
                    rowPadPt = px(11f),
                ),
                Block.Paragraph(s(R.string.pdfdoc_treatments_note), note = true),
            ),
        )
    }

    /**
     * « 2 × / j — 8 h, 20 h ». There is no stored text for this: the cadence is
     * read back off the schedules, which is also why a treatment with no
     * reminder says so instead of showing a blank cell.
     */
    private fun rhythm(plan: List<DoseSchedule>): String {
        if (plan.isEmpty()) return s(R.string.pdfdoc_rhythm_none)
        val daily = plan.filter { it.kind == "daily" }
        if (daily.size == plan.size) {
            val times = daily
                .sortedWith(compareBy({ it.dailyHour?.toInt() ?: 0 }, { it.dailyMinute?.toInt() ?: 0 }))
                .joinToString(", ") { clock(it) }
            return s(R.string.pdfdoc_rhythm_daily_fmt, daily.size, times)
        }
        return plan.joinToString(MedicationCatalog.SEP) { one ->
            when (one.kind) {
                "daily" -> s(R.string.pdfdoc_rhythm_daily_fmt, 1, clock(one))
                "days_interval" ->
                    s(R.string.pdfdoc_rhythm_days_fmt, one.intervalDays?.toInt() ?: 1, clock(one))
                "interval" -> {
                    val minutes = one.intervalMinutes?.toInt() ?: 0
                    if (minutes > 0 && minutes % 60 == 0) {
                        s(R.string.pdfdoc_rhythm_hours_fmt, minutes / 60)
                    } else {
                        s(R.string.pdfdoc_rhythm_minutes_fmt, minutes)
                    }
                }
                else -> s(R.string.pdfdoc_rhythm_none)
            }
        }
    }

    private fun clock(schedule: DoseSchedule): String {
        val hour = schedule.dailyHour?.toInt() ?: 0
        val minute = schedule.dailyMinute?.toInt() ?: 0
        return if (minute == 0) {
            s(R.string.pdfdoc_hour_fmt, hour)
        } else {
            s(R.string.pdfdoc_hour_minute_fmt, hour, minute)
        }
    }

    // -----------------------------------------------------------------------
    // 2 — Modifications sur la période
    // -----------------------------------------------------------------------

    private suspend fun changesSection(from: Long, to: Long): Section? {
        val changes = safe { medications.listTreatmentChanges(from, to) }.orEmpty()
        if (changes.isEmpty()) return null
        val names = safe { medications.list(includeArchived = true) }.orEmpty()
            .associate { it.id to it.name }
        val rows = changes.sortedByDescending { it.atMs }
            .map { change -> f.slashed(change.atMs) to sentence(change, names[change.medicationId].orEmpty()) }
        // Only what the treatment itself changed — dose, unit, route. Shifts in
        // the time of an intake are not a treatment change: they are the
        // per-dose offset, and section 3 measures every one of them.
        return Section(
            title = s(R.string.pdfdoc_s_changes),
            blocks = listOf(Block.DatedList(rows)),
        )
    }

    /** The sentences are composed here — the core stores fields, not prose. */
    private fun sentence(change: TreatmentChange, name: String): String {
        val unset = s(R.string.pdfdoc_value_unset)
        fun numeric(raw: String?): String =
            raw?.toDoubleOrNull()?.let { f.number(it) } ?: raw?.takeIf { it.isNotBlank() } ?: unset
        fun plain(raw: String?): String = raw?.takeIf { it.isNotBlank() } ?: unset
        fun route(raw: String?): String =
            raw?.takeIf { it.isNotBlank() }?.let { s(MedicationCatalog.routeLabelRes(it)) } ?: unset
        return when (change.field) {
            "dose" -> s(R.string.pdfdoc_change_dose_fmt, name, numeric(change.oldValue), numeric(change.newValue))
            "unit" -> s(R.string.pdfdoc_change_unit_fmt, name, plain(change.oldValue), plain(change.newValue))
            "route" -> s(R.string.pdfdoc_change_route_fmt, name, route(change.oldValue), route(change.newValue))
            else -> s(
                R.string.pdfdoc_change_other_fmt,
                name,
                change.field.replace('_', ' '),
                plain(change.oldValue),
                plain(change.newValue),
            )
        }
    }

    // -----------------------------------------------------------------------
    // 3 — Régularité des prises
    // -----------------------------------------------------------------------

    private suspend fun regularitySection(from: Long, to: Long): Section? {
        val window = safe { plannedDoses.window(from, to) } ?: return null
        val occurrences = window.occurrences
        // Intakes measured against a projected occurrence carry a real offset,
        // so they belong in the figures; only what no schedule explains at all
        // is set aside and disclosed.
        val offGrid = window.offGrid
        val adHoc = window.withoutPlannedTime
        if (occurrences.isEmpty() && offGrid.isEmpty() && adHoc.isEmpty()) return null

        val points = window.points
        val stats = window.stats
        val paired = points.filter { it.deltaMin != null }

        val rows = ArrayList<Stat>()
        rows += Stat(s(R.string.pdfdoc_stat_planned), f.integer(occurrences.size))
        rows += Stat(
            s(R.string.pdfdoc_stat_logged),
            if (occurrences.isEmpty()) {
                f.integer(offGrid.size + adHoc.size)
            } else {
                s(R.string.pdfdoc_stat_logged_fmt, stats.loggedCount, stats.adherencePercent)
            },
        )
        rows += Stat(s(R.string.pdfdoc_stat_missed), f.integer(stats.missedCount))
        if (paired.isNotEmpty()) {
            rows += Stat(s(R.string.pdfdoc_stat_mean_delay), delay(stats.meanDelayMin))
            rows += Stat(
                s(R.string.pdfdoc_stat_over_two_hours),
                f.integer(paired.count { (it.deltaMin ?: 0) > TWO_HOURS_MIN }),
            )
        }

        // Only the most recent doses are plotted: past a certain density the
        // dots stop being readable, and the caption names the number shown.
        val shown = points.takeLast(PUNCTUALITY_DOTS)
        val chart = if (shown.isEmpty()) null else punctuality(shown)

        val note = buildString {
            append(s(R.string.pdfdoc_punct_estimate))
            if (paired.isEmpty()) {
                append(' ')
                append(s(R.string.pdfdoc_punct_none))
            }
            if (adHoc.isNotEmpty()) {
                append(' ')
                append(plural(R.plurals.pdfdoc_punct_excluded, adHoc.size))
            }
        }

        return Section(
            title = s(R.string.pdfdoc_s_regularity),
            blocks = listOf(
                Block.StatChart(
                    stats = rows,
                    caption = chart?.let { s(R.string.pdfdoc_caption_punctuality_fmt, shown.size) },
                    chart = null,
                    punctuality = chart,
                    note = note,
                ),
            ),
        )
    }

    private fun punctuality(points: List<DosePoint>): PunctualitySpec {
        val axis = punctualityAxis(points)
        val first = points.minOf { it.atMs }
        val last = points.maxOf { it.atMs }
        return PunctualitySpec(
            widthPt = Geo.CHART_W,
            points = points,
            axis = axis,
            fromMs = first,
            toMs = if (last > first) last else first + 1L,
            tickLabels = axis.ticks.map { tick(axisLabel(it)) },
            missedLabel = s(R.string.pdfdoc_axis_missed_fmt, axis.missedCount),
        )
    }

    /**
     * No `else`. An unhandled shape used to return the empty string, and a
     * gradation with no label is worse than a missing chart: the line is still
     * drawn and every dot around it is read against a scale nobody stated.
     * Leaving the `when` total means the compiler, not a reader of the printed
     * page, catches the next label the axis learns to produce.
     */
    private fun tick(label: DeltaLabel): String = when (label) {
        DeltaLabel.OnTime -> s(R.string.pdfdoc_axis_on_time)
        DeltaLabel.Missed -> s(R.string.pdfdoc_axis_missed)
        is DeltaLabel.Early -> s(R.string.pdfdoc_axis_early_fmt, label.minutes)
        is DeltaLabel.Minutes -> s(R.string.pdfdoc_axis_minutes_fmt, label.minutes)
        is DeltaLabel.Hours -> s(R.string.pdfdoc_axis_hours_fmt, label.hours)
        is DeltaLabel.HoursMinutes ->
            s(R.string.pdfdoc_axis_hours_minutes_fmt, label.hours, label.minutes)
    }

    private fun delay(minutes: Int): String {
        val magnitude = abs(minutes)
        val body = if (magnitude >= 60) {
            s(R.string.pdfdoc_delay_hours_fmt, magnitude / 60, magnitude % 60)
        } else {
            s(R.string.pdfdoc_delay_minutes_fmt, magnitude)
        }
        return (if (minutes < 0) "−" else "+") + body
    }

    // -----------------------------------------------------------------------
    // 4 — Taux hormonaux
    // -----------------------------------------------------------------------

    private data class Analyte(
        val id: String,
        val label: String,
        val unit: String,
        val points: List<Sample>,
    )

    private data class Sample(
        val atMs: Long,
        val value: Double,
        val unit: String,
        val raw: HormoneMeasurement,
    )

    private suspend fun hormonesSection(from: Long, to: Long, markers: Boolean): Section? {
        val ids = safe { hormones.distinct() }.orEmpty().filter { it != HormoneCatalog.WEIGHT }
        val analytes = ids.mapNotNull { id -> analyte(id, from, to) }
        if (analytes.isEmpty()) return null

        val ordered = analytes.sortedByDescending { it.points.size }
        val main = analytes.firstOrNull { it.id == "estradiol" } ?: ordered.first()
        val secondary = analytes.firstOrNull { it.id == "testosterone" && it.id != main.id }
            ?: ordered.firstOrNull { it.id != main.id }

        val blocks = ArrayList<Block>()
        blocks += Block.HeadValues(head(main), secondary?.let { head(it) })

        val factor = scaleFactor(main, secondary)
        val series = ArrayList<ChartSeries>()
        series += ChartSeries(
            points = main.points.map { TimedValue(it.atMs, it.value) },
            dashed = false,
            dots = true,
            secondary = false,
        )
        secondary?.let {
            series += ChartSeries(
                points = it.points.map { p -> TimedValue(p.atMs, p.value * factor) },
                dashed = true,
                dots = false,
                secondary = true,
            )
        }
        val values = series.flatMap { it.points }.map { it.value }
        if (values.size >= 2) {
            val changes = if (markers) {
                safe { medications.listTreatmentChanges(from, to) }.orEmpty()
                    .filter { it.field == "dose" }
                    .map { ChartMarker(it.atMs, s(R.string.pdfdoc_marker_dose_fmt, f.dayMonth(it.atMs))) }
            } else {
                emptyList()
            }
            val legend = ArrayList<LegendItem>()
            legend += LegendItem(
                s(R.string.pdfdoc_legend_plain_fmt, main.label, main.unit),
                dashed = false,
                secondary = false,
            )
            secondary?.let {
                legend += LegendItem(
                    if (factor > 1) {
                        s(R.string.pdfdoc_legend_scaled_fmt, it.label, it.unit, factor)
                    } else {
                        s(R.string.pdfdoc_legend_plain_fmt, it.label, it.unit)
                    },
                    dashed = true,
                    secondary = true,
                )
            }
            blocks += Block.WideChart(
                chart = ChartSpec(
                    widthPt = Geo.CONTENT_W,
                    heightPt = px(224f),
                    gutterPt = 0f,
                    insetPt = px(17f),
                    plotTopPt = px(20.6f),
                    baselinePt = px(203.4f),
                    gridlinesPt = listOf(px(44.8f), px(103.4f), px(162f)),
                    yTickLabels = emptyList(),
                    fromMs = from,
                    toMs = to,
                    yMin = values.min(),
                    yMax = values.max(),
                    series = series,
                    markers = changes,
                ),
                legend = legend,
                legendTail = s(R.string.pdfdoc_legend_axis_fmt, f.monthShort(from), f.monthLong(to)),
            )
        }

        blocks += pairedTable(main, secondary)
        val others = analytes.filter { it.id != main.id && it.id != secondary?.id }
        if (others.isNotEmpty()) {
            blocks += Block.Caption(s(R.string.pdfdoc_caption_other_labs))
            blocks += Block.Table(
                columns = listOf(
                    Column(s(R.string.pdfdoc_col_sample), 1.0f),
                    Column(s(R.string.pdfdoc_col_analyte), 1.1f, strong = true),
                    Column(s(R.string.pdfdoc_col_value), 1.1f, strong = true),
                    Column(s(R.string.pdfdoc_col_lab), 1.3f, alignRight = true, muted = true),
                ),
                rows = others.flatMap { a ->
                    a.points.sortedByDescending { it.atMs }.map { p ->
                        listOf(f.slashed(p.atMs), a.label, f.value(p.value, p.unit), lab(p))
                    }
                },
                rowPadPt = px(10f),
            )
        }
        blocks += Block.Paragraph(s(R.string.pdfdoc_hormones_note), note = true)
        return Section(s(R.string.pdfdoc_s_hormones), blocks)
    }

    private suspend fun analyte(id: String, from: Long, to: Long): Analyte? {
        val raw = safe { hormones.listForHormone(id, 0, MEASURE_LIMIT) }.orEmpty()
            .filter { it.atMs in from..to }
            .sortedBy { it.atMs }
        if (raw.isEmpty()) return null
        val target = units.getEffective(id)
        val points = raw.map { m ->
            // A reading the core cannot convert keeps **both** its number and
            // the unit it was recorded in. Relabelling it with the preferred
            // unit would print a prolactin of 260 mIU/mL as « 260 ng/mL » — the
            // same figure under a unit twenty times away from it.
            val converted = target
                ?.takeIf { it != m.unit }
                ?.let { unit ->
                    hormones.convert(m.value, m.unit, unit, id)?.let { Sample(m.atMs, it, unit, m) }
                }
            converted ?: Sample(m.atMs, m.value, m.unit, m)
        }
        return Analyte(id, analyteLabel(id), points.last().unit, points)
    }

    private fun head(a: Analyte): HeadValue {
        val last = a.points.last()
        // The original reading is kept beside the converted one: the doctor's
        // laboratory report says one of the two, and it may not be ours.
        val conversion = if (last.unit != last.raw.unit) {
            "· " + f.value(last.raw.value, last.raw.unit)
        } else {
            null
        }
        return HeadValue(
            caption = s(R.string.pdfdoc_head_last_fmt, a.label.uppercase(locale)),
            value = f.number(last.value),
            unit = last.unit,
            conversion = conversion,
        )
    }

    /**
     * The secondary curve shares the main axis, so it is multiplied by the
     * power of ten that brings it closest without overtaking — and the legend
     * prints the factor, because a hidden multiplier is a lie.
     */
    private fun scaleFactor(main: Analyte, secondary: Analyte?): Int {
        secondary ?: return 1
        val top = main.points.maxOf { it.value }
        val other = secondary.points.maxOf { it.value }
        if (other <= 0.0 || top <= 0.0) return 1
        var factor = 1
        while (factor < MAX_SCALE_FACTOR && other * factor * 10 <= top) factor *= 10
        return factor
    }

    private fun pairedTable(main: Analyte, secondary: Analyte?): Block.Table {
        val byDay = HashMap<LocalDate, Array<Sample?>>()
        main.points.forEach { p ->
            byDay.getOrPut(dateOf(p.atMs)) { arrayOfNulls(2) }[0] = p
        }
        secondary?.points?.forEach { p ->
            byDay.getOrPut(dateOf(p.atMs)) { arrayOfNulls(2) }[1] = p
        }
        val empty = s(R.string.pdfdoc_empty_cell)
        val columns = ArrayList<Column>()
        columns += Column(s(R.string.pdfdoc_col_sample), 1.0f)
        columns += Column(main.label.uppercase(locale), 1.1f, strong = true)
        if (secondary != null) columns += Column(secondary.label.uppercase(locale), 1.1f, strong = true)
        columns += Column(s(R.string.pdfdoc_col_lab), 1.3f, alignRight = true, muted = true)
        val rows = byDay.entries.sortedByDescending { it.key }.map { (day, pair) ->
            val row = ArrayList<String>()
            row += f.slashed(day.atStartOfDay(zone).toInstant().toEpochMilli())
            row += pair[0]?.let { f.value(it.value, it.unit) } ?: empty
            if (secondary != null) row += pair[1]?.let { f.value(it.value, it.unit) } ?: empty
            row += listOfNotNull(pair[0], pair[1]).firstOrNull()?.let { lab(it) } ?: empty
            row
        }
        return Block.Table(columns, rows, px(10f))
    }

    private fun lab(sample: Sample): String =
        sample.raw.labName?.takeIf { it.isNotBlank() } ?: s(R.string.pdfdoc_lab_manual)

    /**
     * Human label of an analyte. [HormoneCatalog.kindLabel] is `@Composable`
     * and unreachable from here, so the same resources are resolved directly.
     */
    private fun analyteLabel(id: String): String = when (id) {
        "estradiol" -> s(R.string.hormone_estradiol)
        "progesterone" -> s(R.string.hormone_progesterone)
        "testosterone" -> s(R.string.hormone_testosterone)
        "lh" -> s(R.string.hormone_lh)
        "fsh" -> s(R.string.hormone_fsh)
        "prolactin" -> s(R.string.hormone_prolactin)
        "shbg" -> s(R.string.hormone_shbg)
        "bp_systolic" -> s(R.string.hormone_bp_systolic)
        "bp_diastolic" -> s(R.string.hormone_bp_diastolic)
        "hemoglobin" -> s(R.string.hormone_hemoglobin)
        "hematocrit" -> s(R.string.hormone_hematocrit)
        HormoneCatalog.WEIGHT -> s(R.string.weight_kind)
        else -> f.capitalise(id.replace('_', ' '))
    }

    // -----------------------------------------------------------------------
    // 5 — Poids
    // -----------------------------------------------------------------------

    private suspend fun weightSection(from: Long, to: Long): Section? {
        val raw = safe { hormones.listForHormone(HormoneCatalog.WEIGHT, 0, MEASURE_LIMIT) }.orEmpty()
            .filter { it.atMs in from..to }
            .sortedBy { it.atMs }
        if (raw.isEmpty()) return null
        // Weight shares the hormone table but not its conversion: the Rust core
        // does not know kilograms, so the catalogue converts client-side.
        val unit = units.getEffective(HormoneCatalog.WEIGHT) ?: raw.last().unit
        val points = raw.map { m ->
            TimedValue(m.atMs, HormoneCatalog.convertWeight(m.value, m.unit, unit) ?: m.value)
        }
        val first = points.first().value
        val last = points.last().value
        val stats = listOf(
            Stat(s(R.string.pdfdoc_weight_current), "${f.score(last)} $unit"),
            Stat(s(R.string.pdfdoc_weight_start), "${f.score(first)} $unit"),
            Stat(s(R.string.pdfdoc_weight_delta), f.signed(last - first, unit)),
        )
        val chart = if (points.size < 2) null else ChartSpec(
            widthPt = Geo.CHART_W,
            heightPt = px(74f),
            gutterPt = 0f,
            insetPt = px(11f),
            plotTopPt = px(8f),
            baselinePt = px(66.6f),
            gridlinesPt = emptyList(),
            yTickLabels = emptyList(),
            fromMs = from,
            toMs = to,
            yMin = points.minOf { it.value },
            yMax = points.maxOf { it.value },
            series = listOf(
                ChartSeries(
                    points,
                    dashed = false,
                    dots = false,
                    secondary = false,
                    terminalDot = true,
                )
            ),
        )
        return Section(
            title = s(R.string.pdfdoc_s_weight),
            blocks = listOf(
                Block.StatChart(
                    stats = stats,
                    caption = null,
                    chart = chart,
                    punctuality = null,
                    note = null,
                    centred = true,
                ),
            ),
        )
    }

    // -----------------------------------------------------------------------
    // 6 — Ressenti déclaré
    // -----------------------------------------------------------------------

    private data class Indicator(val label: String, val current: List<TimedValue>, val previous: List<Double>)

    private suspend fun feelingsSection(from: Long, to: Long, days: Int): Section? {
        val all = safe { journals.list(0, ENTRY_LIMIT) }.orEmpty()
        val current = all.filter { it.atMs in from..to }.sortedBy { it.atMs }
        if (current.isEmpty()) return null
        val span = (to - from).coerceAtLeast(1L)
        val previous = all.filter { it.atMs in (from - span) until from }

        val indicators = ArrayList<Indicator>()
        BUILT_IN.forEach { (labelRes, read) ->
            val now = current.mapNotNull { e -> read(e)?.let { TimedValue(e.atMs, it.toDouble()) } }
            if (now.isNotEmpty()) {
                indicators += Indicator(s(labelRes), now, previous.mapNotNull { read(it)?.toDouble() })
            }
        }
        indicators += customIndicators(current, previous)

        val moodWeeks = weekly(
            current.mapNotNull { e -> e.mood?.let { TimedValue(e.atMs, it.toDouble()) } }
        )
        val effects = effects(current)
        // §7.7 forbids an orphan title; a table reduced to its header row is the
        // same defect one level down. Every built-in gauge can be hidden and
        // every entry left without a value, so nothing guarantees a row: the
        // table is printed only when it has some, and when that leaves the
        // framing sentence alone the section itself has nothing to say.
        if (indicators.isEmpty() && moodWeeks.size < 2 && effects.isEmpty()) return null

        val blocks = ArrayList<Block>()
        blocks += Block.Paragraph(
            s(
                R.string.pdfdoc_feel_intro_fmt,
                plural(R.plurals.pdfdoc_entries, current.size),
                plural(R.plurals.pdfdoc_days, days),
            )
        )
        if (indicators.isNotEmpty()) {
            val empty = s(R.string.pdfdoc_empty_cell)
            blocks += Block.Table(
                columns = listOf(
                    Column(s(R.string.pdfdoc_col_indicator), 1.4f, strong = true),
                    Column(s(R.string.pdfdoc_col_mean), 0.8f),
                    Column(s(R.string.pdfdoc_col_previous), 0.8f, muted = true),
                    Column(s(R.string.pdfdoc_col_trend), 1.6f),
                ),
                rows = indicators.map { indicator ->
                    val mean = indicator.current.map { it.value }.average()
                    val before = indicator.previous.takeIf { it.isNotEmpty() }?.average()
                    listOf(
                        indicator.label,
                        f.score(mean),
                        before?.let { f.score(it) } ?: empty,
                        trend(mean, before, indicator.current),
                    )
                },
                rowPadPt = px(10f),
            )
        }

        if (moodWeeks.size >= 2) {
            blocks += Block.Caption(s(R.string.pdfdoc_caption_mood_week))
            blocks += Block.WideChart(
                chart = ChartSpec(
                    widthPt = Geo.CONTENT_W,
                    heightPt = px(64f),
                    gutterPt = 0f,
                    insetPt = px(17f),
                    plotTopPt = px(14.9f),
                    baselinePt = px(57.6f),
                    gridlinesPt = listOf(px(14.9f)),
                    yTickLabels = emptyList(),
                    fromMs = from,
                    toMs = to,
                    yMin = 0.0,
                    yMax = SCALE_MAX,
                    series = listOf(
                        ChartSeries(
                            moodWeeks,
                            dashed = false,
                            dots = false,
                            secondary = false,
                            terminalDot = true,
                        )
                    ),
                ),
                legend = emptyList(),
                legendTail = null,
            )
        }

        if (effects.isNotEmpty()) {
            blocks += Block.Caption(s(R.string.pdfdoc_caption_effects))
            blocks += Block.Chips(effects)
        }
        return Section(s(R.string.pdfdoc_s_feel), blocks)
    }

    private suspend fun customIndicators(
        current: List<JournalEntry>,
        previous: List<JournalEntry>,
    ): List<Indicator> {
        val definitions = safe { metrics.definitions(MetricsRepository.DOMAIN_JOURNAL) }.orEmpty()
            .filter { !it.builtin && it.enabled }
        if (definitions.isEmpty()) return emptyList()

        suspend fun read(entries: List<JournalEntry>): Map<Long, MutableList<TimedValue>> {
            val out = HashMap<Long, MutableList<TimedValue>>()
            entries.forEach { entry ->
                val values = safe { metrics.values(MetricsRepository.DOMAIN_JOURNAL, entry.id) }.orEmpty()
                values.forEach { v ->
                    out.getOrPut(v.metricId) { ArrayList() }
                        .add(TimedValue(entry.atMs, v.value.toDouble()))
                }
            }
            return out
        }

        val now = read(current)
        val before = read(previous)
        return definitions.mapNotNull { definition ->
            val series = now[definition.id]?.sortedBy { it.atMs } ?: return@mapNotNull null
            if (series.isEmpty()) return@mapNotNull null
            Indicator(definition.label, series, before[definition.id].orEmpty().map { it.value })
        }
    }

    /**
     * The trend is written out, never an arrow: « En hausse » is readable by
     * someone skimming a printed page, a glyph is not. « régulière » is only
     * claimed when the weekly means actually move the same way three weeks in
     * a row.
     */
    private fun trend(mean: Double, previous: Double?, series: List<TimedValue>): String {
        previous ?: return s(R.string.pdfdoc_trend_new)
        val delta = mean - previous
        val weeks = weekly(series)
        val run = monotoneRun(weeks.map { it.value })
        if (run >= STEADY_WEEKS && abs(delta) >= TREND_CLEAR) {
            val since = f.capitalise(f.monthLong(weeks[weeks.size - run].atMs))
            return if (delta > 0) {
                s(R.string.pdfdoc_trend_steady_up_fmt, since)
            } else {
                s(R.string.pdfdoc_trend_steady_down_fmt, since)
            }
        }
        return when {
            abs(delta) < TREND_FLAT -> s(R.string.pdfdoc_trend_stable)
            abs(delta) < TREND_CLEAR ->
                s(if (delta > 0) R.string.pdfdoc_trend_slight_up else R.string.pdfdoc_trend_slight_down)
            else -> s(if (delta > 0) R.string.pdfdoc_trend_up else R.string.pdfdoc_trend_down)
        }
    }

    /** Length of the monotone tail of [values], counted in samples. */
    private fun monotoneRun(values: List<Double>): Int {
        if (values.size < 2) return 0
        var run = 1
        val rising = values.last() >= values[values.size - 2]
        for (i in values.size - 1 downTo 1) {
            val step = values[i] - values[i - 1]
            if ((rising && step >= 0) || (!rising && step <= 0)) run++ else break
        }
        return run
    }

    /** One point per calendar week, placed at the middle of its week. */
    private fun weekly(points: List<TimedValue>): List<TimedValue> = points
        .groupBy { dateOf(it.atMs).toEpochDay() / 7 }
        .toSortedMap()
        .map { (bucket, values) ->
            val midDay = LocalDate.ofEpochDay(bucket * 7 + 3)
            TimedValue(midDay.atStartOfDay(zone).toInstant().toEpochMilli(), values.map { it.value }.average())
        }

    /**
     * `sideEffects` is free text, comma-separated the same way the app splits
     * it into chips. Counting is done on a fold of case and accents so
     * « Céphalées » and « cephalees » are one effect, and the day is the unit —
     * three entries the same day are one day of fatigue, not three.
     */
    private fun effects(entries: List<JournalEntry>): List<Pair<String, String>> {
        val days = HashMap<String, MutableSet<LocalDate>>()
        val display = HashMap<String, String>()
        entries.forEach { entry ->
            val date = dateOf(entry.atMs)
            entry.sideEffects.orEmpty().split(',').forEach { chunk ->
                val label = chunk.trim()
                if (label.isEmpty()) return@forEach
                val key = fold(label)
                display.putIfAbsent(key, label)
                days.getOrPut(key) { HashSet() }.add(date)
            }
        }
        return days.entries
            .sortedWith(compareByDescending<Map.Entry<String, MutableSet<LocalDate>>> { it.value.size }
                .thenBy { display[it.key] })
            .take(MAX_EFFECTS)
            .map { display[it.key].orEmpty() to s(R.string.pdfdoc_effect_days_fmt, it.value.size) }
    }

    private fun fold(text: String): String = Normalizer
        .normalize(text.lowercase(locale), Normalizer.Form.NFD)
        .replace(DIACRITICS, "")

    // -----------------------------------------------------------------------
    // Règles
    // -----------------------------------------------------------------------

    private suspend fun bleedingSection(from: Long, to: Long): Section? {
        val entries = safe { bleeding.list(0, ENTRY_LIMIT) }.orEmpty()
            .filter { it.atMs in from..to }
            .sortedBy { it.atMs }
        if (entries.isEmpty()) return null

        // Consecutive days are one episode; the doctor reads spans, not rows.
        data class Span(var start: LocalDate, var end: LocalDate, var period: Boolean, var spotting: Boolean)
        val spans = ArrayList<Span>()
        entries.forEach { entry ->
            val day = dateOf(entry.atMs)
            val spotting = entry.isSpotting == true
            val last = spans.lastOrNull()
            if (last != null && !day.isAfter(last.end.plusDays(1))) {
                if (day.isAfter(last.end)) last.end = day
                if (spotting) last.spotting = true else last.period = true
            } else {
                spans += Span(day, day, period = !spotting, spotting = spotting)
            }
        }

        val rows = spans.asReversed().map { span ->
            val length = ChronoUnit.DAYS.between(span.start, span.end).toInt() + 1
            listOf(
                span.start.format(),
                span.end.format(),
                plural(R.plurals.pdfdoc_bleeding_days, length),
                when {
                    span.period && span.spotting -> s(R.string.pdfdoc_bleeding_both)
                    span.spotting -> s(R.string.pdfdoc_bleeding_spotting)
                    else -> s(R.string.pdfdoc_bleeding_period)
                },
            )
        }
        val blocks = ArrayList<Block>()
        blocks += Block.Table(
            columns = listOf(
                Column(s(R.string.pdfdoc_col_start), 1.0f, strong = true),
                Column(s(R.string.pdfdoc_col_end), 1.0f),
                Column(s(R.string.pdfdoc_col_duration), 0.9f),
                Column(s(R.string.pdfdoc_col_nature), 1.1f, alignRight = true, muted = true),
            ),
            rows = rows,
            rowPadPt = px(10f),
        )
        if (spans.size >= 2) {
            val gaps = spans.zipWithNext { a, b -> ChronoUnit.DAYS.between(a.start, b.start) }
            blocks += Block.Paragraph(
                s(R.string.pdfdoc_bleeding_note_fmt, gaps.average().roundToInt()),
                note = true,
            )
        }
        return Section(s(R.string.pdfdoc_s_bleeding), blocks)
    }

    // -----------------------------------------------------------------------
    // 7 — Voix
    // -----------------------------------------------------------------------

    private suspend fun voiceSection(from: Long, to: Long): Section? {
        val clips = safe { voice.list() }.orEmpty()
            .filter { it.atMs in from..to && it.pitchHz != null }
            .sortedBy { it.atMs }
        if (clips.isEmpty()) return null
        val points = clips.map { TimedValue(it.atMs, (it.pitchHz ?: 0).toDouble()) }
        val first = points.first().value
        val last = points.last().value
        val stats = listOf(
            Stat(s(R.string.pdfdoc_voice_current), s(R.string.pdfdoc_hz_fmt, last.roundToInt())),
            Stat(
                f.capitalise(f.monthLong(points.first().atMs)),
                s(R.string.pdfdoc_hz_fmt, first.roundToInt()),
            ),
            Stat(s(R.string.pdfdoc_voice_delta), f.signed(last - first, HERTZ, oneDecimal = false)),
            Stat(s(R.string.pdfdoc_voice_count), f.integer(clips.size)),
        )
        val scale = hertzScale(points.minOf { it.value }, points.maxOf { it.value })
        // The gradations are derived from the data, so their gridlines are
        // placed by the same mapping the curve uses rather than pinned to the
        // prototype's demo scale of 180 / 160 / 140 Hz.
        val top = px(15.1f)
        val baseline = px(93.1f)
        val chart = if (points.size < 2) null else ChartSpec(
            widthPt = Geo.CHART_W,
            heightPt = px(104f),
            gutterPt = VOICE_GUTTER,
            insetPt = px(6f),
            plotTopPt = top,
            baselinePt = baseline,
            gridlinesPt = scale.ticks.map { value ->
                val fraction = (value - scale.min) / (scale.max - scale.min)
                baseline - (baseline - top) * fraction.toFloat()
            },
            yTickLabels = scale.ticks.map { s(R.string.pdfdoc_hz_fmt, it.roundToInt()) },
            fromMs = from,
            toMs = to,
            yMin = scale.min,
            yMax = scale.max,
            series = listOf(ChartSeries(points, dashed = false, dots = true, secondary = false)),
            bounds = f.monthShort(points.first().atMs) to f.monthShort(points.last().atMs),
        )
        return Section(
            title = s(R.string.pdfdoc_s_voice),
            blocks = listOf(
                Block.StatChart(
                    stats = stats,
                    caption = chart?.let { s(R.string.pdfdoc_caption_voice) },
                    chart = chart,
                    punctuality = null,
                    note = s(R.string.pdfdoc_voice_note),
                ),
            ),
        )
    }

    private data class Scale(val min: Double, val max: Double, val ticks: List<Double>)

    /** Round gradations in the unit the reader thinks in: 20 Hz at a time. */
    private fun hertzScale(low: Double, high: Double): Scale {
        var step = HERTZ_STEP
        val bottom = floor(low / step) * step
        var top = ceil(high / step) * step
        if (top <= bottom) top = bottom + step
        while ((top - bottom) / step > MAX_TICKS) step *= 2
        val ticks = ArrayList<Double>()
        var value = top
        while (value >= bottom - 0.001) {
            ticks += value
            value -= step
        }
        return Scale(min = bottom - step * 0.35, max = top, ticks = ticks)
    }

    // -----------------------------------------------------------------------
    // 8 — Questions à aborder
    // -----------------------------------------------------------------------

    private suspend fun questionsSection(): Section? {
        val now = System.currentTimeMillis()
        val next = safe { appointments.list(0, ENTRY_LIMIT) }.orEmpty()
            .filter { it.atMs > now }
            .minByOrNull { it.atMs } ?: return null
        val items = appointmentTodoItems(next.todo).map { it.label }
        if (items.isEmpty()) return null
        return Section(s(R.string.pdfdoc_s_questions), listOf(Block.Checklist(items)))
    }

    // -----------------------------------------------------------------------
    // Photos d'évolution
    // -----------------------------------------------------------------------

    private suspend fun photosSection(from: Long, to: Long): Section? {
        val records = safe { photos.list(0, ENTRY_LIMIT) }.orEmpty()
            .filter { it.atMs in from..to }
            .sortedBy { it.atMs }
            .takeLast(MAX_PHOTOS)
        if (records.isEmpty()) return null
        // Decrypted in memory and handed straight to the renderer: no plaintext
        // image is ever written next to the PDF.
        val tiles = records.mapNotNull { record ->
            safe { photos.decryptToBytes(record) }?.let { PhotoTile(f.slashed(record.atMs), it) }
        }
        if (tiles.isEmpty()) return null
        return Section(
            title = s(R.string.pdfdoc_s_photos),
            blocks = listOf(
                Block.Photos(tiles),
                Block.Paragraph(s(R.string.pdfdoc_photos_note), note = true),
            ),
        )
    }

    // -----------------------------------------------------------------------
    // Plumbing
    // -----------------------------------------------------------------------

    private fun dateOf(atMs: Long): LocalDate = Instant.ofEpochMilli(atMs).atZone(zone).toLocalDate()

    private fun LocalDate.format(): String =
        f.slashed(atStartOfDay(zone).toInstant().toEpochMilli())

    private fun s(@StringRes id: Int): String = context.getString(id)

    private fun s(@StringRes id: Int, vararg args: Any): String = context.getString(id, *args)

    private fun plural(@PluralsRes id: Int, count: Int): String =
        context.resources.getQuantityString(id, count, count)

    /**
     * Every vault read goes through here. A locked or half-migrated vault must
     * degrade to a shorter document, never to a crash in the user's hands —
     * the previous exporter left exactly one call unguarded and it was the one
     * that could throw.
     */
    private suspend fun <T> safe(block: suspend () -> T): T? = runCatching { block() }.getOrNull()

    private companion object {
        const val MEASURE_LIMIT = 2000L
        const val ENTRY_LIMIT = 5000L
        const val MAX_SCALE_FACTOR = 1000
        const val PUNCTUALITY_DOTS = 60
        const val TWO_HOURS_MIN = 120
        const val MAX_EFFECTS = 6
        const val MAX_PHOTOS = 8
        const val SCALE_MAX = 10.0
        const val TREND_FLAT = 0.3
        const val TREND_CLEAR = 0.8
        const val STEADY_WEEKS = 3
        const val HERTZ = "Hz"
        const val HERTZ_STEP = 20.0
        const val MAX_TICKS = 3

        /** Room for « 180 Hz » to the left of the voice plot (§B4.5). */
        const val VOICE_GUTTER = 38.5f

        val DIACRITICS = Regex("\\p{Mn}+")

        /** The five built-in gauges, in catalogue order (§6.2). */
        val BUILT_IN: List<Pair<Int, (JournalEntry) -> Int?>> = listOf(
            R.string.gauge_mood to { e: JournalEntry -> e.mood?.toInt() },
            R.string.gauge_dysphoria to { e: JournalEntry -> e.dysphoria?.toInt() },
            R.string.gauge_euphoria to { e: JournalEntry -> e.euphoria?.toInt() },
            R.string.gauge_libido to { e: JournalEntry -> e.libido?.toInt() },
            R.string.gauge_energy to { e: JournalEntry -> e.energy?.toInt() },
        )
    }
}
