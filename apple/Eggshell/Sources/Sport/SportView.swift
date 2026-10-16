import CoreMotion
import SwiftUI
import TransitionCore
import UniformTypeIdentifiers

@MainActor
final class SportViewModel: ObservableObject {

    /// What the header card shows. All of it derived, none of it stored.
    struct Dashboard {
        var weekSessions = 0
        var weekMinutes: Int64 = 0
        var currentStreak = 0
        var longestStreak = 0
        var todaySteps: Int64 = 0
    }

    @Published var sessions: [SportSession] = []
    /// The activity catalogue indexed by id, archived types included: a
    /// session logged under a type that was later archived still belongs to
    /// it, and relabelling it « sans type » would be the app lying about the
    /// user's own history.
    @Published var activities: [Int64: SportActivity] = [:]
    @Published var dashboard = Dashboard()
    @Published var steps: [Date: Int64] = [:]
    @Published var loading = true
    @Published var importPreview: WatchImporter.Preview?
    /// Non-nil once an import finished: how many sessions it wrote.
    @Published var importResult: Int?

    private let counter = StepCounter()

    /// The numbers under the step calendar. All derived, none of it stored.
    struct StepSummary {
        var month = StepStats.Month()
        /// This month's daily average against last month's, in percent.
        var changeVsPrevious: Int?
        var goalStreak = 0
        var bestGoalStreak = 0
        var yearTotal: Int64 = 0
    }

    @Published var stepSummary = StepSummary()
    /// Set when a correction could not be written, so the sheet can say so.
    @Published var stepError: String?

    func load(session: VaultService, pedometer: Bool, month: Date, goal: Int = SportPrefs.defaultGoal) async {
        // Santé first, so a workout recorded on the watch since the last visit
        // is in the list below rather than one screen behind. Fail-soft: the
        // importer reports its own errors on its card and never throws here.
        if HealthKitImporter.isAvailable && SportPrefs.healthImportIsOn() {
            await HealthKitImporter.shared.sync(session: session)
        }
        activities = Dictionary(
            uniqueKeysWithValues: (await SportStore.activities(session, includeArchived: true)).map { ($0.id, $0) })
        let all = await SportStore.sessions(session)
        sessions = all
        let (fromMs, toMs) = SportStats.weekBounds(containing: Date())
        let days = SportStats.sessionDays(all)
        dashboard = Dashboard(
            weekSessions: SportStats.countBetween(all, fromMs: fromMs, toMs: toMs),
            weekMinutes: SportStats.minutesBetween(all, fromMs: fromMs, toMs: toMs),
            currentStreak: SportStats.currentStreak(sessionDays: days, today: Date()),
            longestStreak: SportStats.longestStreak(sessionDays: days),
            todaySteps: dashboard.todaySteps)
        loading = false
        if pedometer {
            // Read the sensor first, then the vault, so today's number is
            // current rather than one screen behind.
            await counter.sync(into: session)
            await loadSteps(session: session, month: month, goal: goal)
        }
    }

    func loadSteps(session: VaultService, month: Date, goal: Int = SportPrefs.defaultGoal) async {
        let cal = Calendar.current
        guard let interval = cal.dateInterval(of: .month, for: month) else { return }
        let last = cal.date(byAdding: .day, value: -1, to: interval.end) ?? interval.end
        let shown = await SportStore.stepDays(session, from: interval.start, to: last)
        steps = shown
        var previous: [Date: Int64] = [:]
        if let before = cal.date(byAdding: .month, value: -1, to: interval.start),
           let prevInterval = cal.dateInterval(of: .month, for: before) {
            let prevLast = cal.date(byAdding: .day, value: -1, to: prevInterval.end) ?? prevInterval.end
            previous = await SportStore.stepDays(session, from: prevInterval.start, to: prevLast)
        }
        // A year and a bit back: enough for this year's total and for any
        // streak someone could realistically be on.
        let today = cal.startOfDay(for: Date())
        let recent = await SportStore.stepDays(
            session, from: cal.date(byAdding: .day, value: -400, to: today) ?? today, to: today)
        let current = StepStats.month(shown, goal: goal)
        let year = cal.component(.year, from: today)
        stepSummary = StepSummary(
            month: current,
            changeVsPrevious: StepStats.change(
                current: current.average, previous: StepStats.month(previous, goal: goal).average),
            goalStreak: StepStats.goalStreak(recent, goal: goal, today: today),
            bestGoalStreak: StepStats.bestGoalStreak(recent, goal: goal),
            yearTotal: recent.filter { cal.component(.year, from: $0.key) == year }.values.reduce(0, +))
        dashboard.todaySteps = recent[today] ?? 0
    }

    // MARK: Live steps

    /// Today's count as Core Motion reports it right now, while the screen is
    /// up. Display only: nothing is written per step — the sync on load and on
    /// unlock stays the one writer, and keeps the vault's max rule.
    @Published private(set) var liveTodaySteps: Int64?
    private var livePedometer: CMPedometer?

    /// What « pas aujourd'hui » shows: the larger of what the vault holds and
    /// what the sensor says now. The vault can be ahead (a Santé import that
    /// counted the watch), the sensor can be ahead (the walk since the last
    /// sync); the larger is the true one either way.
    var todayStepsShown: Int64 { max(dashboard.todaySteps, liveTodaySteps ?? 0) }

    /// A calendar cell's count, with today's raised to the live figure.
    func stepsShown(on day: Date) -> Int64? {
        let stored = steps[day]
        guard let live = liveTodaySteps, Calendar.current.isDateInToday(day) else { return stored }
        return max(stored ?? 0, live)
    }

    func startLiveSteps(enabled: Bool) {
        stopLiveSteps()
        guard enabled, StepCounter.isAvailable, !StepCounter.isDenied else { return }
        let start = Calendar.current.startOfDay(for: Date())
        // Read through today's correction, as the sync does: a day the user
        // brought down must not jump back up on screen.
        let offset = SportPrefs.stepOffsets()[SportFormat.dayKey(start)] ?? 0
        let pedometer = CMPedometer()
        livePedometer = pedometer
        pedometer.startUpdates(from: start, withHandler: Self.liveHandler { [weak self] steps in
            Task { @MainActor in self?.applyLive(steps: steps, day: start, offset: offset) }
        })
    }

    func stopLiveSteps() {
        livePedometer?.stopUpdates()
        livePedometer = nil
        liveTodaySteps = nil
    }

    private func applyLive(steps: Int64, day: Date, offset: Int64) {
        guard livePedometer != nil else { return }   // stopped while this was in flight
        // Past midnight the count still starts yesterday: restart from the new
        // day rather than credit yesterday's walk to today.
        guard Calendar.current.isDate(day, inSameDayAs: Date()) else {
            startLiveSteps(enabled: true)
            return
        }
        liveTodaySteps = max(0, steps - offset)
    }

    /// Built outside the main actor on purpose: Core Motion calls this on a
    /// queue of its own, and a closure formed inside this `@MainActor` class
    /// would be taken as main-actor code. It only hands the number over.
    nonisolated private static func liveHandler(
        _ sink: @escaping @Sendable (Int64) -> Void
    ) -> CMPedometerHandler {
        { data, error in
            guard error == nil, let data else { return }
            sink(data.numberOfSteps.int64Value)
        }
    }

    /// Set a day to what the user says it was — the only way to bring one down.
    func correct(day: Date, to value: Int64, session: VaultService, month: Date, goal: Int) async {
        do {
            try await counter.correct(day: day, to: value, session: session)
        } catch {
            stepError = describe(error)
        }
        await loadSteps(session: session, month: month, goal: goal)
    }

    /// Read the file and show what it holds. Writes nothing.
    func previewImport(url: URL, session: VaultService) async {
        // Picked URLs from .fileImporter are security-scoped.
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let data = (try? Data(contentsOf: url)) ?? Data()
        importPreview = await WatchImporter.preview(data, session: session)
    }

    func confirmImport(session: VaultService, pedometer: Bool, month: Date, goal: Int) async {
        guard let preview = importPreview else { return }
        importPreview = nil
        let types = await SportStore.activities(session)
        importResult = await WatchImporter.importWorkouts(preview.importable, activities: types, session: session)
        await load(session: session, pedometer: pedometer, month: month, goal: goal)
    }
}

/// « Sport » — the dashboard, the step calendar, three doors, then the
/// sessions, newest first.
///
/// The doors are rows that say what they open: « Importer » alone gave no clue
/// that it wanted a watch's .gpx/.tcx export, and the pedometer is a setting
/// one flips once, so it lives with the activity types rather than on the
/// front page. The reminders get a door of their own, the way a treatment's
/// reminders are reached from its own screen.
struct SportView: View {
    @EnvironmentObject private var app: AppState
    @EnvironmentObject private var router: Router
    @EnvironmentObject private var sportPrefs: SportPrefs
    @EnvironmentObject private var labReminders: LabReminderStore
    @Environment(\.palette) private var palette
    @StateObject private var vm = SportViewModel()

    @State private var month = Date()
    @State private var pickedDay: PickedDay?
    @State private var showImporter = false
    @State private var depth: Int?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Metrics.blockGap) {
                dashboardCard

                // The step calendar only appears once the pedometer is on: an
                // empty month grid would be a screenful of nothing explaining
                // itself.
                if sportPrefs.pedometer {
                    stepCalendar
                }

                doorRow(
                    systemImage: "bell.badge",
                    title: "Rappels de séance",
                    subtitle: reminderSubtitle
                ) { router.push(.sportReminders) }
                doorRow(
                    systemImage: "square.and.arrow.down",
                    title: "Importer depuis ta montre",
                    subtitle: "Un fichier .fit, .gpx ou .tcx exporté par l'app de ta montre. Rien n'est envoyé nulle part."
                ) { showImporter = true }
                doorRow(
                    systemImage: "slider.horizontal.3",
                    title: "Types d'activité et podomètre",
                    subtitle: "Tes types de séance, le compteur de pas et l'objectif quotidien."
                ) { router.push(.sportActivities) }

                SectionTitleView("SÉANCES")
                    .padding(.top, Spacing.xs)

                if vm.sessions.isEmpty && !vm.loading {
                    EmptyStateView(
                        "Aucune séance pour l'instant. La première ?",
                        systemImage: "figure.run",
                        actionLabel: "Noter une séance"
                    ) { router.push(.sportSession(id: nil)) }
                } else {
                    ForEach(vm.sessions, id: \.id) { session in
                        Button { router.push(.sportSession(id: session.id)) } label: {
                            SessionRow(
                                session: session,
                                activity: session.activityId.flatMap { vm.activities[$0] })
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            .padding(.horizontal, Metrics.screenMargin)
            .padding(.bottom, Metrics.blockGap)
        }
        .background(palette.surface.ignoresSafeArea())
        .navigationTitle("Sport")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button { router.push(.sportSession(id: nil)) } label: {
                    Label("Noter une séance", systemImage: "plus")
                }
            }
        }
        // Any file type: exporters label .gpx and .tcx as XML, octet-stream or
        // their own type depending on where the file came from, and a filter
        // that misses one greys out the very file the user is trying to pick.
        .fileImporter(isPresented: $showImporter, allowedContentTypes: [.item]) { result in
            guard case .success(let url) = result, let session = app.session else { return }
            Task { await vm.previewImport(url: url, session: session) }
        }
        .sheet(item: $vm.importPreview) { preview in
            ImportPreviewSheet(
                preview: preview,
                onConfirm: {
                    guard let session = app.session else { return }
                    Task {
                        await vm.confirmImport(
                            session: session, pedometer: sportPrefs.pedometer, month: month,
                            goal: sportPrefs.dailyGoal)
                    }
                },
                onCancel: { vm.importPreview = nil })
        }
        .alert(
            "Import terminé",
            isPresented: Binding(get: { vm.importResult != nil }, set: { if !$0 { vm.importResult = nil } })
        ) {
            Button("OK") { vm.importResult = nil }
        } message: {
            let n = vm.importResult ?? 0
            Text(n == 1 ? "1 séance importée." : "\(n) séances importées.")
        }
        .sheet(item: $pickedDay) { picked in
            StepDaySheet(
                day: picked.date,
                steps: vm.stepsShown(on: picked.date),
                onCorrect: { value in
                    pickedDay = nil
                    guard let session = app.session else { return }
                    Task {
                        await vm.correct(
                            day: picked.date, to: value, session: session, month: month,
                            goal: sportPrefs.dailyGoal)
                    }
                },
                onDone: { pickedDay = nil })
        }
        .alert(
            "Correction impossible",
            isPresented: Binding(get: { vm.stepError != nil }, set: { if !$0 { vm.stepError = nil } })
        ) {
            Button("OK") { vm.stepError = nil }
        } message: {
            Text(vm.stepError ?? "")
        }
        .task { reload() }
        .onAppear {
            if depth == nil { depth = router.path.count }
            vm.startLiveSteps(enabled: sportPrefs.pedometer)
        }
        // Covers the editor pushed on top as well as leaving the module: the
        // sensor only runs while this screen is the one being looked at.
        .onDisappear { vm.stopLiveSteps() }
        .onChange(of: router.path.count) { _, count in
            // `.task` does not re-fire when the editor pushed on top pops, so
            // the stack depth says when to read again.
            if let depth, count == depth { reload() }
        }
        .onChange(of: month) { _, newMonth in
            guard let session = app.session, sportPrefs.pedometer else { return }
            Task { await vm.loadSteps(session: session, month: newMonth, goal: sportPrefs.dailyGoal) }
        }
    }

    private func reload() {
        guard let session = app.session else { return }
        Task {
            await vm.load(
                session: session, pedometer: sportPrefs.pedometer, month: month, goal: sportPrefs.dailyGoal)
        }
    }

    private var reminderSubtitle: String {
        let count = labReminders.items.filter { $0.kind == LabReminderKind.sport }.count
        switch count {
        case 0:  return "Aucun rappel programmé"
        case 1:  return "1 rappel programmé"
        default: return "\(count) rappels programmés"
        }
    }

    // MARK: - Dashboard

    /// Three numbers, not a chart. A chart of four sessions is a chart of
    /// noise, and what someone actually wants on opening the module is whether
    /// they have moved this week and whether the run is still alive.
    private var dashboardCard: some View {
        EggCard(variant: .primary) {
            HStack(alignment: .top) {
                stat("\(vm.dashboard.weekSessions)", label: "cette semaine")
                Spacer(minLength: 0)
                stat(SportFormat.duration(seconds: vm.dashboard.weekMinutes * 60), label: "de sport")
                Spacer(minLength: 0)
                stat("\(vm.dashboard.currentStreak)", label: "jours d'affilée")
            }
            if sportPrefs.pedometer {
                Text("\(SportFormat.integer(Int(vm.todayStepsShown))) pas aujourd'hui sur \(SportFormat.integer(sportPrefs.dailyGoal))")
                    .font(EggFont.bodyS)
                    .foregroundStyle(palette.onPrimaryContainer.opacity(0.8))
            }
            if vm.dashboard.longestStreak > vm.dashboard.currentStreak {
                Text("Meilleure série : \(vm.dashboard.longestStreak) jours")
                    .font(EggFont.bodyS)
                    .foregroundStyle(palette.onPrimaryContainer.opacity(0.8))
            }
        }
    }

    private func stat(_ value: String, label: String) -> some View {
        VStack(spacing: 2) {
            Text(value)
                .font(.system(size: 24, weight: .bold, design: .rounded))
                .foregroundStyle(palette.onPrimaryContainer)
            Text(label)
                .font(EggFont.micro)
                .foregroundStyle(palette.onPrimaryContainer.opacity(0.8))
        }
    }

    // MARK: - Steps

    private var stepCalendar: some View {
        MonthGrid(month: $month) { day in
            StepDayCell(day: day, steps: vm.stepsShown(on: day), goal: sportPrefs.dailyGoal) {
                pickedDay = PickedDay(date: day)
            }
        } footer: {
            stepSummary
                .padding(.top, 10)
        }
    }

    /// Under the calendar: the month in a few numbers, how it compares with
    /// the previous one, the goal streak and the year so far. Words rather
    /// than a chart: the calendar above already is the picture.
    private var stepSummary: some View {
        let s = vm.stepSummary
        let m = s.month
        return VStack(alignment: .leading, spacing: 2) {
            if m.daysRecorded == 0 {
                Text("Aucun pas compté ce mois-ci.")
            } else {
                Text("\(SportFormat.integer(Int(m.total))) pas ce mois-ci · \(SportFormat.integer(Int(m.average))) pas par jour")
                Text("Objectif atteint \(m.daysAtGoal) jour\(m.daysAtGoal > 1 ? "s" : "") · meilleur jour : \(SportFormat.integer(Int(m.best))) pas")
                if let change = s.changeVsPrevious {
                    Text("Moyenne : \(change > 0 ? "+" : "")\(change) % par rapport au mois précédent")
                }
            }
            Text("\(s.goalStreak) jour\(s.goalStreak > 1 ? "s" : "") d'affilée à l'objectif"
                 + (s.bestGoalStreak > s.goalStreak ? " · record : \(s.bestGoalStreak)" : ""))
            if s.yearTotal > 0 {
                Text("Cette année : \(SportFormat.integer(Int(s.yearTotal))) pas")
            }
        }
        .font(EggFont.bodyS)
        .foregroundStyle(palette.onSurfaceVariant)
    }

    // MARK: - Doors

    /// A door: what it opens, one line on what is behind it, a chevron. The
    /// same shape the treatment screen uses for its reminders.
    private func doorRow(
        systemImage: String, title: String, subtitle: String, action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            EggCard(variant: .low, paddingH: 18, paddingV: Spacing.l, spacing: 0) {
                HStack(spacing: Metrics.blockGap) {
                    Image(systemName: systemImage)
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(palette.onPrimaryContainer)
                        .frame(width: 40, height: 40)
                        .background(palette.primaryContainer, in: RoundedRectangle(cornerRadius: 12))
                    VStack(alignment: .leading, spacing: 2) {
                        Text(title)
                            .font(EggFont.titleS)
                            .foregroundStyle(palette.onSurface)
                        Text(subtitle)
                            .font(EggFont.bodyS)
                            .foregroundStyle(palette.onSurfaceVariant)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    Image(systemName: "chevron.right")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(palette.onSurfaceVariant)
                }
            }
        }
        .buttonStyle(.plain)
    }
}

/// One day of steps: a disc that fills as the count nears the goal, solid once
/// it is reached, with the day number over it. Tapping says the exact count.
private struct StepDayCell: View {
    @Environment(\.palette) private var palette

    let day: Date
    let steps: Int64?
    let goal: Int
    let onTap: () -> Void

    private var cal: Calendar { Calendar.current }

    var body: some View {
        let count = steps ?? 0
        let fraction = goal > 0 ? min(1, Double(count) / Double(goal)) : 0
        let reached = count >= Int64(goal) && goal > 0
        let isToday = cal.isDateInToday(day)

        Button(action: onTap) {
            ZStack {
                if count > 0 {
                    Circle()
                        .fill(palette.primaryContainer.opacity(reached ? 1 : 0.25 + 0.6 * fraction))
                        .frame(width: MonthGridMetrics.disc, height: MonthGridMetrics.disc)
                }
                if reached {
                    Circle()
                        .strokeBorder(palette.primary, lineWidth: 1.5)
                        .frame(width: MonthGridMetrics.disc, height: MonthGridMetrics.disc)
                } else if isToday {
                    Circle()
                        .strokeBorder(palette.primary, lineWidth: 1)
                        .frame(width: MonthGridMetrics.disc, height: MonthGridMetrics.disc)
                }
                Text("\(cal.component(.day, from: day))")
                    .font(.system(size: 13, weight: count > 0 ? .semibold : .regular))
                    .foregroundStyle(count > 0 ? palette.onPrimaryContainer : palette.onSurface)
            }
            .frame(height: MonthGridMetrics.cellHeight)
            .frame(maxWidth: .infinity)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(
            "\(cal.component(.day, from: day)), \(count > 0 ? "\(count) pas" : "aucun pas")\(reached ? ", objectif atteint" : "")")
    }
}

/// What a tapped calendar day says. Read-only: correcting a day is a separate
/// deliberate act, and a stray tap must not be able to rewrite one.
/// One day of the step calendar: what was counted, and a way to correct it.
/// Correcting is the only way to bring a day down — the sensor can only add —
/// and it sits here because this is where a wrong number is noticed. It takes
/// a deliberate « Corriger », then a number, then « Enregistrer »: a stray tap
/// cannot rewrite a day.
private struct StepDaySheet: View {
    @Environment(\.palette) private var palette
    let day: Date
    let steps: Int64?
    let onCorrect: (Int64) -> Void
    let onDone: () -> Void

    @State private var editing = false
    @State private var typed = ""

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.m) {
            Text(dayLabel)
                .font(EggFont.titleL)
                .foregroundStyle(palette.onSurface)
            if editing {
                TextField("Nombre de pas ce jour-là", text: $typed)
                    .keyboardType(.numberPad)
                    .font(.eggBody)
                    .padding(.horizontal, Spacing.m)
                    .frame(minHeight: Metrics.touchTarget)
                    .background(
                        palette.surfaceContainerHigh,
                        in: RoundedRectangle(cornerRadius: Radius.field, style: .continuous))
                    .onChange(of: typed) { _, new in
                        let digits = String(new.filter(\.isASCII).filter(\.isNumber).prefix(6))
                        if digits != new { typed = digits }
                    }
                HStack {
                    Button("Annuler") { editing = false }
                        .foregroundStyle(palette.onSurfaceVariant)
                    Spacer()
                    Button("Enregistrer") {
                        if let value = Int64(typed) { onCorrect(value) }
                    }
                    .disabled(Int64(typed) == nil)
                    .foregroundStyle(palette.primary)
                }
                .font(EggFont.label)
                .buttonStyle(.plain)
            } else {
                Text((steps ?? 0) == 0
                     ? "Aucun pas enregistré ce jour-là."
                     : "\(SportFormat.integer(Int(steps ?? 0))) pas")
                    .font(.eggBody)
                    .foregroundStyle(palette.onSurfaceVariant)
                Button("Corriger") {
                    typed = (steps ?? 0) > 0 ? String(steps ?? 0) : ""
                    editing = true
                }
                .font(EggFont.label)
                .foregroundStyle(palette.primary)
                .buttonStyle(.plain)
            }
            Spacer(minLength: 0)
        }
        .padding(Metrics.cardPadding)
        .frame(maxWidth: .infinity, alignment: .leading)
        .presentationDetents([.height(editing ? 260 : 200)])
        .presentationDragIndicator(.visible)
    }

    private var dayLabel: String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "fr_FR")
        f.dateStyle = .full
        return f.string(from: day).capitalized
    }
}

private struct SessionRow: View {
    @Environment(\.palette) private var palette
    let session: SportSession
    let activity: SportActivity?

    var body: some View {
        EggCard(variant: .low, paddingH: 18, paddingV: 14, spacing: 0) {
            HStack(alignment: .top, spacing: Spacing.s) {
                VStack(alignment: .leading, spacing: 2) {
                    // A session whose type was deleted keeps everything else,
                    // and is labelled honestly rather than hidden.
                    Text(activity?.name ?? "Sans type")
                        .font(EggFont.titleS)
                        .foregroundStyle(palette.onSurface)
                    Text(SportFormat.when(session.startedMs))
                        .font(EggFont.bodyS)
                        .foregroundStyle(palette.onSurfaceVariant)
                    if let note = session.freeText, !note.isEmpty {
                        Text(note)
                            .font(EggFont.bodyS)
                            .foregroundStyle(palette.onSurfaceVariant)
                            .lineLimit(2)
                            .padding(.top, 2)
                    }
                }
                Spacer(minLength: Spacing.s)
                VStack(alignment: .trailing, spacing: 2) {
                    Text(SportFormat.duration(seconds: session.durationS))
                        .font(EggFont.titleS)
                        .foregroundStyle(palette.onSurface)
                    // Only when there is one. Most sessions have no distance,
                    // and a blank line under every strength set is noise.
                    if let metres = session.distanceM {
                        Text(SportFormat.distance(metres: metres))
                            .font(EggFont.bodyS)
                            .foregroundStyle(palette.onSurfaceVariant)
                    }
                    if let hr = session.avgHr {
                        Text("\(hr) bpm moy.")
                            .font(EggFont.bodyS)
                            .foregroundStyle(palette.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/// What an import would write, before it writes it.
private struct ImportPreviewSheet: View {
    @Environment(\.palette) private var palette
    let preview: WatchImporter.Preview
    let onConfirm: () -> Void
    let onCancel: () -> Void

    private static let previewRows = 4

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: Spacing.m) {
                if preview.workouts.isEmpty {
                    Text("Aucune séance trouvée dans ce fichier. Exporte un .fit, un .gpx ou un .tcx depuis l'app de ta montre.")
                } else if preview.importable.isEmpty {
                    Text("Ces séances sont déjà enregistrées.")
                } else {
                    let n = preview.importable.count
                    Text(n == 1 ? "1 séance à importer :" : "\(n) séances à importer :")
                    ForEach(preview.importable.prefix(Self.previewRows)) { w in
                        Text(SportFormat.when(w.startedMs) + " · " + SportFormat.duration(seconds: w.durationS))
                            .font(EggFont.bodyS)
                            .foregroundStyle(palette.onSurfaceVariant)
                    }
                    if !preview.duplicates.isEmpty {
                        let d = preview.duplicates.count
                        Text(d == 1 ? "1 séance déjà enregistrée, ignorée." : "\(d) séances déjà enregistrées, ignorées.")
                            .font(EggFont.bodyS)
                            .foregroundStyle(palette.onSurfaceVariant)
                    }
                }
                Spacer(minLength: 0)
            }
            .font(.eggBody)
            .foregroundStyle(palette.onSurface)
            .padding(Metrics.cardPadding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .navigationTitle("Importer depuis ta montre")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(preview.importable.isEmpty ? "OK" : "Annuler", action: onCancel)
                }
                if !preview.importable.isEmpty {
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Importer", action: onConfirm)
                    }
                }
            }
        }
        .presentationDetents([.medium])
        .presentationDragIndicator(.visible)
    }
}

/// A tapped calendar day, wrapped because `.sheet(item:)` wants Identifiable
/// and a retroactive conformance on Date would be a second one the day some
/// other file adds its own.
private struct PickedDay: Identifiable {
    let date: Date
    var id: TimeInterval { date.timeIntervalSince1970 }
}
