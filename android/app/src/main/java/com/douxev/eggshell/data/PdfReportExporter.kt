package com.douxev.eggshell.data

import android.content.Context
import android.graphics.pdf.PdfDocument
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.douxev.eggshell.BuildConfig
import com.douxev.eggshell.R
import com.douxev.eggshell.data.pdf.ReportBuilder
import com.douxev.eggshell.data.pdf.ReportFormats
import com.douxev.eggshell.data.pdf.ReportPainter

/**
 * Produces the PDF a user hands to their doctor.
 *
 * The document is not the app in black and white. It borrows the grammar of a
 * medical summary — hairlines, tables on a grid, one accent colour reserved for
 * the section numbers and the curves, no icons, always the light palette — so
 * that it survives a photocopier and reads like something a practitioner has
 * seen a thousand times (§7.1).
 *
 * Three properties matter more than the layout:
 *
 * * **It is made offline and it says so.** Every page carries the footer, and
 *   nothing leaves the device until the user picks a target in the share sheet.
 * * **It never invents a figure.** Expected doses are replayed from the saved
 *   schedules and the document calls that an estimate; a statistic that cannot
 *   be computed is replaced by the sentence saying why. See [ReportBuilder].
 * * **Nothing survives on disk.** The file stays inside `cacheDir/pdf_export/`
 *   — the only sub-directory `res/xml/file_provider_paths.xml` exposes; writing
 *   anywhere else makes `FileProvider.getUriForFile` throw and kills the share
 *   — and [purgeExports] erases it as soon as the app leaves the foreground.
 *
 * The work is split three ways: [ReportBuilder] turns the vault into a value,
 * [ReportPainter] turns that value into pages, and this class owns the file.
 */
@Singleton
class PdfReportExporter @Inject constructor(
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
    @ApplicationContext private val context: Context,
) {
    /**
     * The period, and the eight modules of §6.12. A module that is off, or on
     * with nothing in the period, produces no section at all — and the
     * remaining sections renumber themselves so the document never shows a gap.
     */
    data class Options(
        val fromMs: Long,
        val toMs: Long,
        /** Sections 1 to 3: treatments, their changes, and punctuality. */
        val treatments: Boolean = true,
        val hormones: Boolean = true,
        val weight: Boolean = true,
        val feelings: Boolean = true,
        val questions: Boolean = true,
        val bleeding: Boolean = false,
        val voice: Boolean = false,
        /** Never on by default — the only module with that rule (§6.12.4). */
        val photos: Boolean = false,
    )

    suspend fun generate(request: Options): File = withContext(Dispatchers.IO) {
        // A period is a pair of ordered bounds. Handed them backwards, every
        // window filter would come back empty and the title would read from the
        // future to the past — so they are put back in order, not guessed at.
        val options = if (request.toMs >= request.fromMs) {
            request
        } else {
            request.copy(fromMs = request.toMs, toMs = request.fromMs)
        }
        val locale = Locale.getDefault()
        val model = ReportBuilder(
            context = context,
            medications = medications,
            schedules = schedules,
            plannedDoses = plannedDoses,
            hormones = hormones,
            units = units,
            journals = journals,
            metrics = metrics,
            bleeding = bleeding,
            voice = voice,
            appointments = appointments,
            photos = photos,
            settings = settings,
            locale = locale,
        ).build(options)

        val painter = ReportPainter(
            bannerLeft = context.getString(R.string.pdfdoc_banner),
            footerLeft = context.getString(R.string.pdfdoc_footer_fmt, BuildConfig.VERSION_NAME),
        )
        // Two passes: the first lays the document out with no canvas to learn
        // N, the second draws it knowing what « n / N » should say. The layout
        // does not depend on N, so the two passes always agree (D3).
        val pages = painter.measure(model)
        val doc = PdfDocument()
        painter.paint(model, doc, pages)

        val dir = exportDir.apply { mkdirs() }
        val formats = ReportFormats(locale)
        val out = File(dir, "suivi-${formats.iso(options.fromMs)}_${formats.iso(options.toMs)}.pdf")
        // Purge first: a previous export is decrypted health data with a
        // filename visible in the share sheet, and it has no reason to outlive
        // the next one. The file we are about to write is spared so a re-export
        // of the same period never leaves an empty stub behind.
        dir.listFiles()?.forEach { if (it != out) it.delete() }
        out.outputStream().use { doc.writeTo(it) }
        doc.close()
        out
    }

    /**
     * Erase every generated report.
     *
     * What sits in that directory is the most concentrated plaintext the app
     * ever produces: the identity block, every hormone value, the punctuality
     * figures, the bleeding episodes, and decrypted progress photos when that
     * module is on. Until now it only disappeared when the *next* export
     * overwrote the directory — so a single export left it readable for ever,
     * to anyone who later got the phone unlocked.
     *
     * Called from the process-lifecycle observer that already purges the photo
     * and voice caches, i.e. the moment the app leaves the foreground — which
     * is also once the share sheet has taken what it needed.
     */
    fun purgeExports() {
        runCatching { exportDir.listFiles()?.forEach { it.delete() } }
    }

    private val exportDir: File get() = File(context.cacheDir, "pdf_export")
}
