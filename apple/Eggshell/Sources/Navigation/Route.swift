import SwiftUI

/// Per-tab navigation path. Inject via environmentObject so any view (including
/// sheets) can push programmatically: `router.push(.medicationDetail(id: 3))`.
/// NavigationLink(value: Route.x) also works for in-stack pushes.
@MainActor
final class Router: ObservableObject {
    @Published var path = NavigationPath()
    func push(_ route: Route) { path.append(route) }
    func popToRoot() { path = NavigationPath() }
}

// In-app push destinations (used with NavigationStack path / NavigationLink(value:)).
// Each tab is its own NavigationStack and shares this destination table.
enum Route: Hashable {
    case medicationList
    case addMedication
    case editMedication(id: Int64)
    case medicationDetail(id: Int64)
    case addSchedule(medId: Int64)
    case logDose(medId: Int64)

    case addJournal(id: Int64?)
    case correlation
    case metricEditor(domain: String)

    case addBleeding(id: Int64?)

    case addAppointment(id: Int64?)
    case summary

    case addHormone
    case hormoneUnits
    case importLab

    case settingsHub
    case features
    case themePicker
    case reminders
    case resources
    case advancedSettings
    case pdfExport
}

// Central destination table. Every screen the fan-out produces is referenced
// here by its exact type name + initializer — this is the contract.
@ViewBuilder
func routeDestination(_ route: Route) -> some View {
    switch route {
    case .medicationList:            MedicationListView()
    case .addMedication:             AddMedicationView()
    case .editMedication(let id):    AddMedicationView(editId: id)
    case .medicationDetail(let id):  MedicationDetailView(medId: id)
    case .addSchedule(let medId):    AddScheduleView(medId: medId)
    case .logDose(let medId):        LogDoseView(medId: medId)

    case .addJournal(let id):        AddJournalEntryView(entryId: id)
    case .correlation:               CorrelationView()
    case .metricEditor(let domain):  MetricEditorView(domain: domain)

    case .addBleeding(let id):       AddBleedingEntryView(entryId: id)

    case .addAppointment(let id):    AddAppointmentView(entryId: id)
    case .summary:                   SummaryView()

    case .addHormone:                AddHormoneMeasurementView()
    case .hormoneUnits:              HormoneUnitsView()
    case .importLab:                 ImportLabResultView()

    case .settingsHub:               SettingsHubView()
    case .features:                  FeaturesView()
    case .themePicker:               ThemePickerView()
    case .reminders:                 RemindersView()
    case .resources:                 ResourcesView()
    case .advancedSettings:          AdvancedSettingsView()
    case .pdfExport:                 PdfExportView()
    }
}
