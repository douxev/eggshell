package com.douxev.eggshell.ui.home

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bloodtype
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalPharmacy
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.douxev.eggshell.R
import com.douxev.eggshell.ui.appointments.AddAppointmentScreen
import com.douxev.eggshell.ui.appointments.AppointmentsScreen
import com.douxev.eggshell.ui.bleeding.AddBleedingEntryScreen
import com.douxev.eggshell.ui.bleeding.BleedingScreen
import com.douxev.eggshell.ui.correlation.CorrelationScreen
import com.douxev.eggshell.ui.metrics.MetricEditorScreen
import com.douxev.eggshell.ui.hormones.AddHormoneMeasurementScreen
import com.douxev.eggshell.ui.hormones.HormoneUnitsScreen
import com.douxev.eggshell.ui.hormones.HormonesScreen
import com.douxev.eggshell.ui.journal.AddJournalEntryScreen
import com.douxev.eggshell.ui.journal.JournalListScreen
import com.douxev.eggshell.ui.medication.AddMedicationScreen
import com.douxev.eggshell.ui.medication.AddScheduleScreen
import com.douxev.eggshell.ui.medication.LogDoseScreen
import com.douxev.eggshell.ui.medication.MedicationDetailScreen
import com.douxev.eggshell.ui.photos.PhotosScreen
import com.douxev.eggshell.ui.reminders.RemindersScreen
import com.douxev.eggshell.ui.settings.SettingsHubScreen
import com.douxev.eggshell.ui.settings.SettingsScreen
import com.douxev.eggshell.ui.today.TodayScreen
import com.douxev.eggshell.ui.voice.VoiceScreen

/**
 * Top-level Home composable: bottom navigation (Medications, Journal, Hormones,
 * Photos, Settings) wrapping a single NavHost. Each tab is a route group, and
 * deeper destinations stack on top via the same NavHost.
 *
 * A `deepLinkProvider` can request the host to navigate to a specific
 * destination once the NavHost mounts — used by the home-screen widget's
 * "Noter" action to land directly on the journal-add screen.
 */
@Composable
fun HomeNavHost(
    prefsVm: HomeTabsViewModel = androidx.hilt.navigation.compose.hiltViewModel(),
    deepLinkProvider: com.douxev.eggshell.AppRootViewModel? = null,
) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    var showQuickLog by remember { mutableStateOf(false) }

    // Snackbar host lives at the nav level (not inside an entry screen) so a
    // confirmation survives the screen being popped after a save.
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val savedMsg = stringResource(R.string.journal_saved)
    val viewJournalLabel = stringResource(R.string.journal_saved_view)
    // Show a "saved!" confirmation; optionally offer a shortcut to the journal
    // history. Resolved strings are captured so this plain fun stays non-@Composable.
    fun confirmJournalSaved(offerViewJournal: Boolean) {
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = savedMsg,
                actionLabel = if (offerViewJournal) viewJournalLabel else null,
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) {
                nav.navigate(Routes.JOURNAL)
            }
        }
    }
    val showMeds by prefsVm.showMeds.collectAsState()
    val showJournal by prefsVm.showJournal.collectAsState()
    val showHormones by prefsVm.showHormones.collectAsState()
    val showPhoto by prefsVm.showPhoto.collectAsState()
    val showVoice by prefsVm.showVoice.collectAsState()
    val showBleeding by prefsVm.showBleeding.collectAsState()
    val showAppointments by prefsVm.showAppointments.collectAsState()
    val tabs = remember(showMeds, showJournal, showHormones, showPhoto, showVoice, showBleeding, showAppointments) {
        bottomTabs(showMeds, showJournal, showHormones, showPhoto, showVoice, showBleeding, showAppointments)
    }

    val pendingLink by (deepLinkProvider?.pendingDeepLink ?: kotlinx.coroutines.flow.MutableStateFlow(null)).collectAsState()
    androidx.compose.runtime.LaunchedEffect(pendingLink) {
        when (pendingLink) {
            com.douxev.eggshell.AppRootViewModel.DeepLink.JournalAdd -> {
                nav.navigate(Routes.JOURNAL_ADD)
                deepLinkProvider?.consumeDeepLink()
            }
            null -> { /* nothing pending */ }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            // The "Noter" quick-log FAB only makes sense on the home screen.
            // Each inner screen (Med list, Journal, Hormones…) has its own
            // per-context FAB; stacking the two clashes visually and conflates
            // unrelated actions (quick log vs. catalog management).
            if (currentRoute == Routes.TODAY) {
                FloatingActionButton(
                    onClick = { showQuickLog = true },
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = stringResource(R.string.quicklog_fab),
                    )
                }
            }
        },
        bottomBar = {
            NavigationBar {
                tabs.forEach { tab ->
                    BottomTabItem(
                        tab = tab,
                        selected = currentRoute == tab.route,
                        onClick = {
                            // Always pop back to the start destination so the
                            // user never gets "stuck" inside a deep settings
                            // sub-screen when they tap a tab. We don't restore
                            // saved state: each tab tap reloads cleanly.
                            nav.navigate(tab.route) {
                                popUpTo(nav.graph.startDestinationId) {
                                    inclusive = false
                                }
                                launchSingleTop = true
                            }
                        },
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Routes.TODAY,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.TODAY) {
                TodayScreen(
                    onOpenMed = { id -> nav.navigate(Routes.medDetail(id)) },
                    onOpenJournalEntry = { nav.navigate(Routes.JOURNAL_ADD) },
                    onOpenLabs = { nav.navigate(Routes.HORMONES) },
                    onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                    onAddMedication = { nav.navigate(Routes.MED_ADD) },
                    onOpenMedList = { nav.navigate(Routes.MED_LIST) },
                    onOpenSummary = { nav.navigate(Routes.SUMMARY) },
                )
            }
            composable(Routes.MED_LIST) {
                MedicationListScreen(
                    onAddMedication = { nav.navigate(Routes.MED_ADD) },
                    onOpenMedication = { id -> nav.navigate(Routes.medDetail(id)) },
                    onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                )
            }
            composable(Routes.MED_ADD) {
                // After creating a medication, land straight on its schedule
                // setup — otherwise the new med never surfaces a "next dose"
                // and the Today hero card stays empty. The schedule screen is
                // skippable (back arrow), and dropping MED_ADD from the stack
                // means "back" returns to the medication list, not the form.
                AddMedicationScreen(onDone = { medId ->
                    nav.navigate(Routes.medSchedule(medId)) {
                        popUpTo(Routes.MED_ADD) { inclusive = true }
                    }
                })
            }
            composable(Routes.MED_EDIT, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                AddMedicationScreen(onDone = { nav.popBackStack() })
            }
            composable(
                Routes.MED_DETAIL,
                arguments = listOf(navArgument("id") { type = NavType.LongType }),
            ) {
                val id = it.arguments!!.getLong("id")
                MedicationDetailScreen(
                    onLogDose = { nav.navigate(Routes.medLog(id)) },
                    onAddSchedule = { nav.navigate(Routes.medSchedule(id)) },
                    onEditMedication = { nav.navigate(Routes.medEdit(id)) },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(
                Routes.MED_LOG_DOSE,
                arguments = listOf(navArgument("id") { type = NavType.LongType }),
            ) { LogDoseScreen(onDone = { nav.popBackStack() }) }
            composable(
                Routes.MED_ADD_SCHEDULE,
                arguments = listOf(navArgument("id") { type = NavType.LongType }),
            ) { AddScheduleScreen(onDone = { nav.popBackStack() }, onBack = { nav.popBackStack() }) }

            composable(Routes.JOURNAL) {
                JournalListScreen(
                    onAdd = { nav.navigate(Routes.JOURNAL_ADD) },
                    onEdit = { id -> nav.navigate(Routes.journalEdit(id)) },
                    onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                    onOpenCorrelation = { nav.navigate(Routes.CORRELATION) },
                )
            }
            composable(Routes.JOURNAL_ADD) {
                AddJournalEntryScreen(
                    onDone = {
                        nav.popBackStack()
                        confirmJournalSaved(offerViewJournal = true)
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
                        confirmJournalSaved(offerViewJournal = false)
                    },
                    onCustomize = { nav.navigate(Routes.metricEditor("journal")) },
                )
            }

            composable(Routes.BLEEDING) {
                BleedingScreen(
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
            ) {
                AddBleedingEntryScreen(onDone = { nav.popBackStack() })
            }
            composable(
                Routes.METRIC_EDITOR,
                arguments = listOf(navArgument("domain") { type = NavType.StringType }),
            ) {
                MetricEditorScreen(onBack = { nav.popBackStack() })
            }
            composable(Routes.CORRELATION) {
                CorrelationScreen(onBack = { nav.popBackStack() })
            }
            composable(Routes.SUMMARY) {
                com.douxev.eggshell.ui.summary.SummaryScreen(onBack = { nav.popBackStack() })
            }

            composable(Routes.APPOINTMENTS) {
                AppointmentsScreen(
                    onAdd = { nav.navigate(Routes.APPOINTMENTS_ADD) },
                    onEdit = { id -> nav.navigate(Routes.appointmentEdit(id)) },
                )
            }
            composable(Routes.APPOINTMENTS_ADD) {
                AddAppointmentScreen(onDone = { nav.popBackStack() })
            }
            composable(
                Routes.APPOINTMENTS_EDIT,
                arguments = listOf(navArgument("id") { type = NavType.LongType }),
            ) {
                AddAppointmentScreen(onDone = { nav.popBackStack() })
            }

            composable(Routes.HORMONES) {
                HormonesScreen(
                    onAdd = { nav.navigate(Routes.HORMONES_ADD) },
                    onImport = { nav.navigate(Routes.HORMONES_IMPORT) },
                    onOpenSettings = { nav.navigate(Routes.SETTINGS) },
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

            composable(Routes.PHOTOS) {
                PhotosScreen(onOpenSettings = { nav.navigate(Routes.SETTINGS) })
            }
            composable(Routes.VOICE) {
                VoiceScreen(
                    onBack = { nav.popBackStack() },
                    onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsHubScreen(
                    onOpenFeatures = { nav.navigate(Routes.FEATURES) },
                    onOpenReminders = { nav.navigate(Routes.REMINDERS) },
                    onOpenAdvanced = { nav.navigate(Routes.SETTINGS_ADVANCED) },
                    onOpenPdf = { nav.navigate(Routes.PDF_EXPORT) },
                    onOpenHormoneUnits = { nav.navigate(Routes.HORMONE_UNITS) },
                    onOpenTheme = { nav.navigate(Routes.THEME_PICKER) },
                    onOpenResources = { nav.navigate(Routes.RESOURCES) },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(Routes.FEATURES) {
                com.douxev.eggshell.ui.settings.FeaturesScreen(onBack = { nav.popBackStack() })
            }
            composable(Routes.SETTINGS_ADVANCED) {
                SettingsScreen(
                    onOpenReminders = { nav.navigate(Routes.REMINDERS) },
                )
            }
            composable(Routes.REMINDERS) {
                RemindersScreen(onBack = { nav.popBackStack() })
            }
            composable(Routes.HORMONE_UNITS) {
                HormoneUnitsScreen(onBack = { nav.popBackStack() })
            }
            composable(Routes.PDF_EXPORT) {
                com.douxev.eggshell.ui.pdf.PdfExportScreen(onBack = { nav.popBackStack() })
            }
            composable(Routes.THEME_PICKER) {
                com.douxev.eggshell.ui.theme.ThemePickerScreen(onBack = { nav.popBackStack() })
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
            visibility = QuickLogVisibility(
                medications = showMeds,
                journal = showJournal,
                hormones = showHormones,
                photos = showPhoto,
                voice = showVoice,
            ),
        )
    }
}

object Routes {
    const val TODAY = "today"
    const val MED_LIST = "med/list"
    const val MED_ADD = "med/add"
    const val MED_EDIT = "med/edit/{id}"
    const val MED_DETAIL = "med/detail/{id}"
    const val MED_LOG_DOSE = "med/log/{id}"
    const val MED_ADD_SCHEDULE = "med/schedule/{id}"
    const val JOURNAL = "journal"
    const val JOURNAL_ADD = "journal/add"
    const val JOURNAL_EDIT = "journal/edit/{id}"
    const val BLEEDING = "bleeding"
    const val BLEEDING_ADD = "bleeding/add"
    const val BLEEDING_EDIT = "bleeding/edit/{id}"
    const val METRIC_EDITOR = "metrics/editor/{domain}"
    const val CORRELATION = "correlation"
    const val SUMMARY = "summary"
    const val APPOINTMENTS = "appointments"
    const val APPOINTMENTS_ADD = "appointments/add"
    const val APPOINTMENTS_EDIT = "appointments/edit/{id}"
    const val HORMONES = "hormones"
    const val HORMONES_ADD = "hormones/add"
    const val HORMONES_IMPORT = "hormones/import"
    const val PHOTOS = "photos"
    const val VOICE = "voice"
    const val SETTINGS = "settings"
    const val SETTINGS_ADVANCED = "settings/advanced"
    const val REMINDERS = "reminders"
    const val HORMONE_UNITS = "settings/hormone-units"
    const val PDF_EXPORT = "settings/pdf"
    const val THEME_PICKER = "settings/theme"
    const val RESOURCES = "settings/resources"
    const val FEATURES = "settings/features"

    fun medDetail(id: Long) = "med/detail/$id"
    fun medEdit(id: Long) = "med/edit/$id"
    fun medLog(id: Long) = "med/log/$id"
    fun medSchedule(id: Long) = "med/schedule/$id"
    fun journalEdit(id: Long) = "journal/edit/$id"
    fun bleedingEdit(id: Long) = "bleeding/edit/$id"
    fun metricEditor(domain: String) = "metrics/editor/$domain"
    fun appointmentEdit(id: Long) = "appointments/edit/$id"
}

private data class BottomTab(
    val route: String,
    val icon: @Composable () -> androidx.compose.ui.graphics.vector.ImageVector,
    val labelRes: Int,
)

/**
 * Icon-only bottom tab that surfaces its label as a plain tooltip on long
 * press. Material3's `NavigationBarItem` doesn't ship a label-on-long-press
 * affordance, so we wrap it in `TooltipBox` and pass `alwaysShowLabel = false`
 * with a null `label` slot.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun androidx.compose.foundation.layout.RowScope.BottomTabItem(
    tab: BottomTab,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val label = stringResource(tab.labelRes)
    val tooltipState = rememberTooltipState(isPersistent = false)
    // The tooltip lives inside the icon slot so NavigationBarItem keeps its
    // own RowScope layout (and even weight distribution across the bar) —
    // wrapping the whole item in TooltipBox strips the RowScope and the
    // first tab eats the entire width.
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = {
            TooltipBox(
                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = { PlainTooltip { Text(label) } },
                state = tooltipState,
            ) {
                Icon(tab.icon(), contentDescription = label)
            }
        },
        label = null,
        alwaysShowLabel = false,
    )
}

/** Builds the bottom-tab list from the user's feature flags. Today is the
 *  only always-on tab; every other tab is gated on its FeaturesPrefs flag. */
private fun bottomTabs(
    showMeds: Boolean,
    showJournal: Boolean,
    showHormones: Boolean,
    showPhoto: Boolean,
    showVoice: Boolean,
    showBleeding: Boolean,
    showAppointments: Boolean,
): List<BottomTab> = buildList {
    add(BottomTab(Routes.TODAY, { Icons.Filled.Home }, R.string.nav_today))
    if (showMeds) add(BottomTab(Routes.MED_LIST, { Icons.Filled.LocalPharmacy }, R.string.nav_medications))
    if (showJournal) add(BottomTab(Routes.JOURNAL, { Icons.Filled.EditNote }, R.string.nav_journal))
    if (showBleeding) add(BottomTab(Routes.BLEEDING, { Icons.Filled.Bloodtype }, R.string.nav_bleeding))
    if (showHormones) add(BottomTab(Routes.HORMONES, { Icons.Filled.Timeline }, R.string.nav_hormones))
    if (showAppointments) add(BottomTab(Routes.APPOINTMENTS, { Icons.Filled.Event }, R.string.nav_appointments))
    if (showPhoto) add(BottomTab(Routes.PHOTOS, { Icons.Filled.PhotoCamera }, R.string.nav_photos))
    if (showVoice) add(BottomTab(Routes.VOICE, { Icons.Filled.GraphicEq }, R.string.nav_voice))
}

@dagger.hilt.android.lifecycle.HiltViewModel
class HomeTabsViewModel @javax.inject.Inject constructor(
    prefs: com.douxev.eggshell.data.FeaturesPrefs,
) : androidx.lifecycle.ViewModel() {
    val showMeds: kotlinx.coroutines.flow.StateFlow<Boolean> = prefs.medications
    val showJournal: kotlinx.coroutines.flow.StateFlow<Boolean> = prefs.journal
    val showHormones: kotlinx.coroutines.flow.StateFlow<Boolean> = prefs.hormones
    val showPhoto: kotlinx.coroutines.flow.StateFlow<Boolean> = prefs.photoTab
    val showVoice: kotlinx.coroutines.flow.StateFlow<Boolean> = prefs.voiceTab
    val showBleeding: kotlinx.coroutines.flow.StateFlow<Boolean> = prefs.bleeding
    val showAppointments: kotlinx.coroutines.flow.StateFlow<Boolean> = prefs.appointments
}
