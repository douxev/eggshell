import SwiftUI

/// The one navigation path of the app. There is a single `NavigationStack`
/// (`AppShell`) and a single `Router` in the environment, so any view — sheets
/// included — can push with `router.push(.medicationDetail(id: 3))`.
/// `NavigationLink(value: Route.x)` works for in-stack pushes too.
@MainActor
final class Router: ObservableObject {
    @Published var path = NavigationPath()
    func push(_ route: Route) { path.append(route) }
    func pop() { if !path.isEmpty { path.removeLast() } }
    func popToRoot() { path = NavigationPath() }
}

/// Every destination of the navigation tree of §2.3. Accueil is **not** here:
/// it is the root, never a push, and every back button leads to it.
///
/// This table is the contract the screen work is built on — a case names a view
/// that exists today, with the initializer it has today. A screen may be
/// rewritten from the inside out; its type name and init stay put.
enum Route: Hashable {
    // Médics
    case medicationList
    case addMedication
    case editMedication(id: Int64)
    case medicationDetail(id: Int64)
    case addSchedule(medId: Int64)
    case editSchedule(medId: Int64, scheduleId: Int64)
    case logDose(medId: Int64)
    case editDose(medId: Int64, doseId: Int64)

    // Rendez-vous → « Préparer ma consultation »
    case appointments
    case addAppointment(id: Int64?)
    case pdfExport

    // Ressenti
    case journal
    /// « Journal complet » (§6.2) — the detailed entry, new or edited.
    case addJournal(id: Int64?)
    case correlation
    case summary
    case metricEditor(domain: String)

    // Menstruations
    case bleeding
    case addBleeding(id: Int64?)

    // Mesures — the Analyses and Poids tiles land on the same screen, which
    // carries its own hormones / poids selector.
    case labs
    case weight
    case addHormone
    case importLab
    case hormoneUnits

    // Médias
    case photos
    case voice

    // Autres
    case notes
    /// A nested folder level. The name travels with the id so the pushed screen
    /// can title itself without a second round-trip to the vault.
    case notesFolder(id: Int64, name: String)
    case noteEditor(id: Int64)

    // Réglages — three doors (§2.4) plus the pages they link out to.
    case settingsHub
    case settingsModules
    case settingsSecurity
    case settingsAppearance
    case reminders
    case resources

    // Names the pre-refonte settings screens still use. They resolve to the
    // three new doors, so no file owned elsewhere had to be edited; drop them
    // once Réglages has been rewritten.
    static let features = Route.settingsModules
    static let themePicker = Route.settingsAppearance
    static let advancedSettings = Route.settingsSecurity
}

/// Central destination table, registered exactly once — on the root stack.
/// Registering it a second time inside a pushed screen makes SwiftUI pick a
/// winner unpredictably.
@ViewBuilder
func routeDestination(_ route: Route) -> some View {
    switch route {
    case .medicationList:            MedicationListView()
    case .addMedication:             AddMedicationView()
    case .editMedication(let id):    AddMedicationView(editId: id)
    case .medicationDetail(let id):  MedicationDetailView(medId: id)
    case .addSchedule(let medId):    AddScheduleView(medId: medId)
    case .editSchedule(let medId, let scheduleId):
                                     AddScheduleView(medId: medId, editScheduleId: scheduleId)
    case .logDose(let medId):        LogDoseView(medId: medId)
    case .editDose(let medId, let doseId):
                                     LogDoseView(medId: medId, editDoseId: doseId)

    case .appointments:              AppointmentsView()
    case .addAppointment(let id):    AddAppointmentView(entryId: id)
    case .pdfExport:                 PdfExportView()

    case .journal:                   JournalView()
    case .addJournal(let id):        AddJournalEntryView(entryId: id)
    case .correlation:               CorrelationView()
    case .summary:                   SummaryView()
    case .metricEditor(let domain):  MetricEditorView(domain: domain)

    case .bleeding:                  BleedingView()
    case .addBleeding(let id):       AddBleedingEntryView(entryId: id)

    case .labs:                      HormonesView(initialTab: .hormones)
    // Same screen, but the « Poids » tile has to land on the weight segment:
    // opening it on Analyses makes the tile look like a duplicate of the other.
    case .weight:                    HormonesView(initialTab: .weight)
    case .addHormone:                AddHormoneMeasurementView()
    case .importLab:                 ImportLabResultView()
    case .hormoneUnits:              HormoneUnitsView()

    case .photos:                    PhotosView()
    case .voice:                     VoiceView()

    case .notes:                     NotesView()
    case .notesFolder(let id, let name):
                                     NotesView(folderId: id, folderName: name)
    case .noteEditor(let id):        NoteEditorView(noteId: id)

    case .settingsHub:               SettingsHubView()
    case .settingsModules:           FeaturesView()
    case .settingsSecurity:          AdvancedSettingsView()
    case .settingsAppearance:        ThemePickerView()
    case .reminders:                 RemindersView()
    case .resources:                 ResourcesView()
    }
}
