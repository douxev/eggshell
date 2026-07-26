package com.douxev.eggshell.ui.home

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.launch
import com.douxev.eggshell.R
import com.douxev.eggshell.ui.appointments.AddAppointmentScreen
import com.douxev.eggshell.ui.appointments.AppointmentsScreen
import com.douxev.eggshell.ui.bleeding.AddBleedingEntryScreen
import com.douxev.eggshell.ui.bleeding.BleedingScreen
import com.douxev.eggshell.ui.correlation.CorrelationScreen
import com.douxev.eggshell.ui.hormones.AddHormoneMeasurementScreen
import com.douxev.eggshell.ui.hormones.HormoneUnitsScreen
import com.douxev.eggshell.ui.hormones.HormonesScreen
import com.douxev.eggshell.ui.journal.AddJournalEntryScreen
import com.douxev.eggshell.ui.journal.JournalListScreen
import com.douxev.eggshell.ui.medication.AddMedicationScreen
import com.douxev.eggshell.ui.medication.AddScheduleScreen
import com.douxev.eggshell.ui.medication.LogDoseScreen
import com.douxev.eggshell.ui.medication.MedicationDetailScreen
import com.douxev.eggshell.ui.metrics.MetricEditorScreen
import com.douxev.eggshell.ui.photos.PhotosScreen
import com.douxev.eggshell.ui.reminders.RemindersScreen
import com.douxev.eggshell.ui.settings.SettingsHubScreen
import com.douxev.eggshell.ui.settings.SettingsScreen
import com.douxev.eggshell.ui.voice.VoiceScreen

/**
 * The whole navigation of the refonte.
 *
 * **No tab bar.** There is exactly one root — the launcher home — and
 * everything else is a pushed screen whose back arrow returns to it. Enabling
 * a module never adds a destination: every route always exists, only the
 * launcher tile comes and goes. That is what made the old eight-tab shell
 * unpredictable.
 *
 * The snackbar host lives here, above the NavHost, so a confirmation survives
 * the screen being popped after a save.
 */
@Composable
fun HomeNavHost(
    deepLinkProvider: com.douxev.eggshell.AppRootViewModel? = null,
) {
    val nav = rememberNavController()
    var showQuickLog by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val savedMsg = stringResource(R.string.journal_saved)
    val viewJournalLabel = stringResource(R.string.journal_saved_view)
    fun confirmSaved(offerViewJournal: Boolean) {
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = savedMsg,
                actionLabel = if (offerViewJournal) viewJournalLabel else null,
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) nav.navigate(Routes.FEELING)
        }
    }

    val pendingLink by (
        deepLinkProvider?.pendingDeepLink
            ?: kotlinx.coroutines.flow.MutableStateFlow(null)
        ).collectAsState()
    androidx.compose.runtime.LaunchedEffect(pendingLink) {
        when (pendingLink) {
            com.douxev.eggshell.AppRootViewModel.DeepLink.JournalAdd -> {
                nav.navigate(Routes.JOURNAL_ADD)
                deepLinkProvider?.consumeDeepLink()
            }
            null -> Unit
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // This scaffold exists only to host the snackbar above the NavHost. It
        // must not claim the system bars: every screen inside has its own
        // scaffold that reads the same insets, and Compose does not consume
        // them on the way down — so leaving the default here padded every
        // pushed screen twice, once by this one and once by its own.
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(padding),
            // A pushed screen slides in horizontally over 250 ms (README §8).
            enterTransition = {
                slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(250))
            },
            exitTransition = {
                slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(250))
            },
            popEnterTransition = {
                slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(250))
            },
            popExitTransition = {
                slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(250))
            },
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                    onOpenModules = { nav.navigate(Routes.SETTINGS_MODULES) },
                    onOpenMeds = { nav.navigate(Routes.MED_LIST) },
                    onOpenAppointments = { nav.navigate(Routes.APPOINTMENTS) },
                    onOpenJournal = { nav.navigate(Routes.FEELING) },
                    onOpenBleeding = { nav.navigate(Routes.BLEEDING) },
                    onOpenLabs = { nav.navigate(Routes.measures(MeasuresTab.HORMONES)) },
                    onOpenWeight = { nav.navigate(Routes.measures(MeasuresTab.WEIGHT)) },
                    onOpenPhotos = { nav.navigate(Routes.PHOTOS) },
                    onOpenVoice = { nav.navigate(Routes.VOICE) },
                    onOpenFullJournal = { nav.navigate(Routes.JOURNAL_ADD) },
                    onAddMedication = { nav.navigate(Routes.MED_ADD) },
                    onMoodSaved = { confirmSaved(offerViewJournal = true) },
                    onQuickLog = { showQuickLog = true },
                )
            }

            // ---- Médics -------------------------------------------------
            composable(Routes.MED_LIST) {
                MedicationListScreen(
                    onBack = { nav.popBackStack() },
                    onAddMedication = { nav.navigate(Routes.MED_ADD) },
                    onOpenMedication = { id -> nav.navigate(Routes.medDetail(id)) },
                )
            }
            composable(Routes.MED_ADD) {
                // Land straight on schedule setup after creating a medication:
                // without a schedule the new med never surfaces a next dose.
                AddMedicationScreen(onDone = { medId ->
                    nav.navigate(Routes.medSchedule(medId)) {
                        popUpTo(Routes.MED_ADD) { inclusive = true }
                    }
                })
            }
            composable(
                Routes.MED_EDIT,
                arguments = listOf(navArgument("id") { type = NavType.LongType }),
            ) { AddMedicationScreen(onDone = { nav.popBackStack() }) }
            composable(
                Routes.MED_DETAIL,
                arguments = listOf(navArgument("id") { type = NavType.LongType }),
            ) {
                val id = it.arguments!!.getLong("id")
                MedicationDetailScreen(
                    onLogDose = { nav.navigate(Routes.medLog(id)) },
                    onEditDose = { doseId -> nav.navigate(Routes.medDoseEdit(id, doseId)) },
                    onAddSchedule = { nav.navigate(Routes.medSchedule(id)) },
                    onEditSchedule = { sId -> nav.navigate(Routes.medScheduleEdit(id, sId)) },
                    onEditMedication = { nav.navigate(Routes.medEdit(id)) },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(
                Routes.MED_LOG_DOSE,
                arguments = listOf(
                    navArgument("id") { type = NavType.LongType },
                    navArgument("doseId") { type = NavType.LongType; defaultValue = -1L },
                ),
            ) { LogDoseScreen(onDone = { nav.popBackStack() }) }
            composable(
                Routes.MED_ADD_SCHEDULE,
                arguments = listOf(
                    navArgument("id") { type = NavType.LongType },
                    navArgument("scheduleId") { type = NavType.LongType; defaultValue = -1L },
                ),
            ) {
                AddScheduleScreen(onDone = { nav.popBackStack() }, onBack = { nav.popBackStack() })
            }

            // ---- Ressenti ------------------------------------------------
            composable(Routes.FEELING) {
                JournalListScreen(
                    onBack = { nav.popBackStack() },
                    onAdd = { nav.navigate(Routes.JOURNAL_ADD) },
                    onEdit = { id -> nav.navigate(Routes.journalEdit(id)) },
                    onOpenCorrelation = { nav.navigate(Routes.CORRELATION) },
                    onOpenBleeding = { nav.navigate(Routes.BLEEDING) },
                    onOpenSummary = { nav.navigate(Routes.SUMMARY) },
                )
            }
            composable(Routes.JOURNAL_ADD) {
                AddJournalEntryScreen(
                    onDone = {
                        nav.popBackStack()
                        confirmSaved(offerViewJournal = true)
                    },
                    onCustomize = { nav.navigate(Routes.metricEditor("journal")) },
                )
            }
            composable(
                Routes.JOURNAL_EDIT,
                arguments = listOf(navArgument("id") { type = NavType.LongType }),
            ) {
                AddJournalEntryScreen(
                    onDone = {
                        nav.popBackStack()
                        confirmSaved(offerViewJournal = false)
                    },
                    onCustomize = { nav.navigate(Routes.metricEditor("journal")) },
                )
            }
            composable(Routes.CORRELATION) { CorrelationScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.SUMMARY) {
                com.douxev.eggshell.ui.summary.SummaryScreen(onBack = { nav.popBackStack() })
            }

            // ---- Menstruations --------------------------------------------
            composable(Routes.BLEEDING) {
                BleedingScreen(
                    onBack = { nav.popBackStack() },
                    onAdd = { nav.navigate(Routes.BLEEDING_ADD) },
                    onEdit = { id -> nav.navigate(Routes.bleedingEdit(id)) },
                    onCustomize = { nav.navigate(Routes.metricEditor("bleeding")) },
                )
            }
            composable(Routes.BLEEDING_ADD) {
                AddBleedingEntryScreen(onDone = { nav.popBackStack() })
            }
            composable(
                Routes.BLEEDING_EDIT,
                arguments = listOf(navArgument("id") { type = NavType.LongType }),
            ) { AddBleedingEntryScreen(onDone = { nav.popBackStack() }) }
            composable(
                Routes.METRIC_EDITOR,
                arguments = listOf(navArgument("domain") { type = NavType.StringType }),
            ) { MetricEditorScreen(onBack = { nav.popBackStack() }) }

            // ---- Rendez-vous ---------------------------------------------
            composable(Routes.APPOINTMENTS) {
                AppointmentsScreen(
                    onBack = { nav.popBackStack() },
                    onAdd = { nav.navigate(Routes.APPOINTMENTS_ADD) },
                    onEdit = { id -> nav.navigate(Routes.appointmentEdit(id)) },
                    onPrepareVisit = { nav.navigate(Routes.REPORT) },
                )
            }
            composable(Routes.APPOINTMENTS_ADD) {
                AddAppointmentScreen(onDone = { nav.popBackStack() })
            }
            composable(
                Routes.APPOINTMENTS_EDIT,
                arguments = listOf(navArgument("id") { type = NavType.LongType }),
            ) { AddAppointmentScreen(onDone = { nav.popBackStack() }) }

            // The doctor report is reached from Rendez-vous — « Préparer ma
            // consultation » — and nowhere else. It left Réglages on purpose.
            composable(Routes.REPORT) {
                com.douxev.eggshell.ui.pdf.PdfExportScreen(onBack = { nav.popBackStack() })
            }

            // ---- Mesures --------------------------------------------------
            composable(
                Routes.MEASURES,
                arguments = listOf(
                    navArgument("tab") {
                        type = NavType.StringType
                        defaultValue = MeasuresTab.HORMONES
                    },
                ),
            ) {
                HormonesScreen(
                    onBack = { nav.popBackStack() },
                    initialTab = it.arguments?.getString("tab") ?: MeasuresTab.HORMONES,
                    onAdd = { nav.navigate(Routes.HORMONES_ADD) },
                    onImport = { nav.navigate(Routes.HORMONES_IMPORT) },
                )
            }
            composable(Routes.HORMONES_ADD) {
                AddHormoneMeasurementScreen(onDone = { nav.popBackStack() })
            }
            composable(Routes.HORMONES_IMPORT) {
                com.douxev.eggshell.ui.hormones.ImportLabResultScreen(
                    onDone = { nav.popBackStack() },
                )
            }

            // ---- Photos & Voix --------------------------------------------
            composable(Routes.PHOTOS) { PhotosScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.VOICE) { VoiceScreen(onBack = { nav.popBackStack() }) }

            // ---- Réglages : trois portes ----------------------------------
            composable(Routes.SETTINGS) {
                SettingsHubScreen(
                    onBack = { nav.popBackStack() },
                    onOpenModules = { nav.navigate(Routes.SETTINGS_MODULES) },
                    onOpenSecurity = { nav.navigate(Routes.SETTINGS_SECURITY) },
                    onOpenAppearance = { nav.navigate(Routes.SETTINGS_APPEARANCE) },
                    onOpenReminders = { nav.navigate(Routes.REMINDERS) },
                    onOpenResources = { nav.navigate(Routes.RESOURCES) },
                )
            }
            composable(Routes.SETTINGS_MODULES) {
                com.douxev.eggshell.ui.settings.FeaturesScreen(onBack = { nav.popBackStack() })
            }
            composable(Routes.SETTINGS_SECURITY) {
                SettingsScreen(onOpenReminders = { nav.navigate(Routes.REMINDERS) })
            }
            composable(Routes.SETTINGS_APPEARANCE) {
                com.douxev.eggshell.ui.theme.ThemePickerScreen(
                    onBack = { nav.popBackStack() },
                    onOpenHormoneUnits = { nav.navigate(Routes.HORMONE_UNITS) },
                )
            }
            composable(Routes.REMINDERS) {
                RemindersScreen(
                    onBack = { nav.popBackStack() },
                    onEditMedSchedule = { medId, scheduleId ->
                        nav.navigate(Routes.medScheduleEdit(medId, scheduleId))
                    },
                )
            }
            composable(Routes.HORMONE_UNITS) {
                HormoneUnitsScreen(onBack = { nav.popBackStack() })
            }
            composable(Routes.RESOURCES) {
                com.douxev.eggshell.ui.resources.ResourcesScreen(onBack = { nav.popBackStack() })
            }
        }
    }

    if (showQuickLog) {
        QuickLogSheet(
            onDismiss = { showQuickLog = false },
            onPick = { action ->
                showQuickLog = false
                when (action) {
                    QuickAction.Feel -> nav.navigate(Routes.JOURNAL_ADD)
                    QuickAction.Dose -> nav.navigate(Routes.MED_LIST)
                    QuickAction.Injection -> nav.navigate(Routes.MED_LIST)
                    QuickAction.Lab -> nav.navigate(Routes.HORMONES_ADD)
                    QuickAction.Photo -> nav.navigate(Routes.PHOTOS)
                    QuickAction.Voice -> nav.navigate(Routes.VOICE)
                }
            },
        )
    }
}

/** Which segment the Mesures screen opens on. */
object MeasuresTab {
    const val HORMONES = "hormones"
    const val WEIGHT = "weight"
}

object Routes {
    const val HOME = "home"

    const val MED_LIST = "med/list"
    const val MED_ADD = "med/add"
    const val MED_EDIT = "med/edit/{id}"
    const val MED_DETAIL = "med/detail/{id}"
    const val MED_LOG_DOSE = "med/log/{id}?doseId={doseId}"
    const val MED_ADD_SCHEDULE = "med/schedule/{id}?scheduleId={scheduleId}"

    /** « Ressenti » — the journal screen with its Journal/Menstruations/Corrélations segments. */
    const val FEELING = "feeling"
    const val JOURNAL_ADD = "journal/add"
    const val JOURNAL_EDIT = "journal/edit/{id}"
    const val CORRELATION = "correlation"
    const val SUMMARY = "summary"

    const val BLEEDING = "bleeding"
    const val BLEEDING_ADD = "bleeding/add"
    const val BLEEDING_EDIT = "bleeding/edit/{id}"
    const val METRIC_EDITOR = "metrics/editor/{domain}"

    const val APPOINTMENTS = "appointments"
    const val APPOINTMENTS_ADD = "appointments/add"
    const val APPOINTMENTS_EDIT = "appointments/edit/{id}"
    /** « Rapport médecin » — reached only from Rendez-vous. */
    const val REPORT = "appointments/report"

    const val MEASURES = "measures?tab={tab}"
    const val HORMONES_ADD = "hormones/add"
    const val HORMONES_IMPORT = "hormones/import"

    const val PHOTOS = "photos"
    const val VOICE = "voice"

    const val SETTINGS = "settings"
    const val SETTINGS_MODULES = "settings/modules"
    const val SETTINGS_SECURITY = "settings/security"
    const val SETTINGS_APPEARANCE = "settings/appearance"
    const val REMINDERS = "settings/reminders"
    const val HORMONE_UNITS = "settings/hormone-units"
    const val RESOURCES = "settings/resources"

    fun medDetail(id: Long) = "med/detail/$id"
    fun medEdit(id: Long) = "med/edit/$id"
    fun medLog(id: Long) = "med/log/$id"
    fun medDoseEdit(medId: Long, doseId: Long) = "med/log/$medId?doseId=$doseId"
    fun medSchedule(id: Long) = "med/schedule/$id"
    fun medScheduleEdit(medId: Long, scheduleId: Long) = "med/schedule/$medId?scheduleId=$scheduleId"
    fun journalEdit(id: Long) = "journal/edit/$id"
    fun bleedingEdit(id: Long) = "bleeding/edit/$id"
    fun metricEditor(domain: String) = "metrics/editor/$domain"
    fun appointmentEdit(id: Long) = "appointments/edit/$id"
    fun measures(tab: String) = "measures?tab=$tab"
}
