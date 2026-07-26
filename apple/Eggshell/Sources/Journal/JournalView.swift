import SwiftUI
import TransitionCore

// Ressenti (§6.7) — one pushed screen for everything you feel: the journal and
// its month calendar, the Règles log, and the Corrélations overlay, behind a
// single segmented selector.
//
// The refonte reorganises, it removes nothing (D5): « Ton résumé » and
// « Corrélations » used to be destinations you had to know about; they are a
// line and a segment here, and keep their own routes for anything that links
// straight to them.

@MainActor
final class JournalViewModel: ObservableObject {
    @Published var loading = true
    @Published var entries: [JournalEntry] = []
    @Published var error: String?

    /// Calendar overlays: bleeding days (continuous band) + medication days
    /// (per-med coloured dots), so dose↔mood correlations show at a glance.
    struct Overlays {
        var bleedingDays: Set<Date> = []          // startOfDay keys
        var medsByDay: [Date: [Int64]] = [:]      // startOfDay → distinct med ids
        var medColors: [Int64: Int64] = [:]       // med id → ARGB
        var medNames: [Int64: String] = [:]
    }
    @Published var overlays = Overlays()

    /// The four bars drawn beside a history entry: the first four enabled
    /// indicators in catalogue order, each in its own accent (D4).
    @Published var barDefs: [MetricDefinition] = []
    /// entry id → metric id → value, for bar axes that are **not** backed by a
    /// journal column. The five built-ins are all columns, so a default
    /// catalogue costs zero extra queries; only a user who promoted one of
    /// their own indicators into the first four pays for the lookup.
    @Published var barValues: [Int64: [Int64: UInt32]] = [:]

    /// How many entries deep the custom-axis lookup goes. One query per entry
    /// is an actor hop each; the bars are a glance, not a document, and the
    /// history below that mark still renders with its column-backed axes.
    private let customAxisDepth = 60

    func load(_ session: VaultService) async {
        loading = true
        error = nil
        do {
            let loaded = try await session.listJournalEntries(limit: 200)
                .sorted { $0.atMs > $1.atMs }
            entries = loaded

            let defs = try await session.listMetricDefinitions(domain: "journal")
                .filter { $0.enabled && !$0.archived }
                .sorted { $0.sortOrder < $1.sortOrder }
            barDefs = Array(defs.prefix(4))

            if barDefs.contains(where: { $0.columnName == nil }) {
                var fetched: [Int64: [Int64: UInt32]] = [:]
                for entry in loaded.prefix(customAxisDepth) {
                    let stored = (try? await session.listMetricValues(
                        entryDomain: "journal", entryId: entry.id)) ?? []
                    fetched[entry.id] = Dictionary(
                        uniqueKeysWithValues: stored.map { ($0.metricId, $0.value) })
                }
                barValues = fetched
            } else {
                barValues = [:]
            }
        } catch {
            self.error = describe(error)
        }
        loading = false
    }

    /// (Re)load the overlay data for the month the calendar is showing.
    /// Monotonic ticket so a slow older month's result can't overwrite the
    /// overlays of the month currently on screen (fast prev/next paging).
    private var overlaysTicket = 0

    /// `bleedingEnabled` is not a display switch: with the module off the cycle
    /// history is never even read, so nothing about someone's bleeding can
    /// surface on a calendar they did not opt into.
    func loadOverlays(
        _ session: VaultService,
        month: Date,
        cal: Calendar,
        bleedingEnabled: Bool
    ) async {
        overlaysTicket += 1
        let ticket = overlaysTicket
        guard let interval = cal.dateInterval(of: .month, for: month) else { return }
        // A band that starts in the previous month or ends in the next one must
        // keep its square corner at the boundary, so the run detection needs a
        // day on either side of the grid.
        let fromMs = Int64(interval.start.timeIntervalSince1970 * 1000) - 86_400_000
        let toMs = Int64(interval.end.timeIntervalSince1970 * 1000) + 86_400_000
        do {
            let doses = try await session.listDoseEventsBetween(fromMs: fromMs, toMs: toMs)
                .filter { $0.status == "taken" }
            let meds = try await session.listMedications(includeArchived: true)
            var bleedingDays: Set<Date> = []
            if bleedingEnabled {
                // Bleeding entries are newest-first; one page comfortably covers
                // years of cycle history.
                let bleeding = try await session.listBleedingEntries(limit: 1000)
                bleedingDays = Set(bleeding.map {
                    cal.startOfDay(for: Date(timeIntervalSince1970: Double($0.atMs) / 1000))
                })
            }
            var byDay: [Date: [Int64]] = [:]
            for dose in doses {
                let day = cal.startOfDay(
                    for: Date(timeIntervalSince1970: Double(dose.takenAtMs) / 1000))
                var ids = byDay[day] ?? []
                if !ids.contains(dose.medicationId) { ids.append(dose.medicationId) }
                byDay[day] = ids
            }
            var fresh = Overlays()
            fresh.bleedingDays = bleedingDays
            fresh.medsByDay = byDay
            fresh.medColors = Dictionary(uniqueKeysWithValues: meds.compactMap { med in
                med.color.map { (med.id, $0) }
            })
            fresh.medNames = Dictionary(uniqueKeysWithValues: meds.map { ($0.id, $0.name) })
            guard ticket == overlaysTicket else { return } // superseded by a newer month
            overlays = fresh
        } catch {
            // Overlays are decorative — but stale ones from another month are
            // worse than none, so reset instead of keeping the old grid.
            if ticket == overlaysTicket { overlays = Overlays() }
        }
    }

    /// Average mood per calendar day, on the 0…10 scale, for the disc tint.
    func moodByDay(_ cal: Calendar) -> [Date: Double] {
        var sums: [Date: (Double, Int)] = [:]
        for entry in entries {
            guard let mood = entry.mood else { continue }
            let day = cal.startOfDay(for: Date(timeIntervalSince1970: Double(entry.atMs) / 1000))
            let running = sums[day] ?? (0, 0)
            sums[day] = (running.0 + Double(mood), running.1 + 1)
        }
        return sums.mapValues { $0.0 / Double($0.1) }
    }

    /// One bar axis of an entry: a built-in reads its column, anything else
    /// reads the metric-values table.
    func barValue(_ entry: JournalEntry, _ def: MetricDefinition) -> UInt32? {
        if let column = def.columnName {
            switch column {
            case "mood":      return entry.mood
            case "dysphoria": return entry.dysphoria
            case "euphoria":  return entry.euphoria
            case "libido":    return entry.libido
            case "energy":    return entry.energy
            default:          return nil
            }
        }
        return barValues[entry.id]?[def.id]
    }
}

// MARK: - Screen

struct JournalView: View {
    @EnvironmentObject private var app: AppState
    @EnvironmentObject private var router: Router
    @EnvironmentObject private var features: FeaturesStore
    @Environment(\.palette) private var palette
    @StateObject private var vm = JournalViewModel()

    /// Segment identities, stable whatever the selector shows. « Règles »
    /// disappears entirely when the module is off, so a position in the control
    /// is not an identity: keeping the two apart is what lets the flag flip
    /// without silently moving the user to another segment.
    private static let segmentJournal = 0
    private static let segmentBleeding = 1
    private static let segmentCorrelations = 2
    private static let segmentTitles = ["Journal", "Règles", "Corrélations"]

    /// Which segment the selector offers. Bleeding is opt-in — it says
    /// something strong about a body — so the module being off removes the
    /// segment, not just its content.
    private var availableSegments: [Int] {
        features.bleeding
            ? [Self.segmentJournal, Self.segmentBleeding, Self.segmentCorrelations]
            : [Self.segmentJournal, Self.segmentCorrelations]
    }

    /// The segment actually shown. Should the module be switched off from
    /// elsewhere while this screen sits on the stack, the selection falls back
    /// to the journal on the very next render instead of pointing past the end
    /// of the control.
    private var effectiveSegment: Int {
        availableSegments.contains(segment) ? segment : Self.segmentJournal
    }

    /// Bridges the control's *position* to the segment *identity*, so that
    /// hiding « Règles » shifts nothing under the user.
    private var segmentSelection: Binding<Int> {
        Binding(
            get: { self.availableSegments.firstIndex(of: self.effectiveSegment) ?? 0 },
            set: { index in
                guard self.availableSegments.indices.contains(index) else { return }
                self.segment = self.availableSegments[index]
            })
    }

    /// One of the segment identities above — never a position in the control.
    @State private var segment = 0
    @State private var visibleMonth = Date()
    @State private var selectedDay: Date?
    @State private var search = ""
    /// Where this screen sits in the stack. A pushed screen's `.task` does not
    /// re-fire when something on top of it pops, so it watches the depth to
    /// know it is showing again.
    @State private var depth: Int?
    @State private var reloadTick = 0

    private var cal: Calendar { Calendar.current }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Metrics.blockGap) {
                SegmentedSelector(
                    options: availableSegments.map { Self.segmentTitles[$0] },
                    selection: segmentSelection,
                    accessibilityLabel: "Ce que tu regardes")

                switch effectiveSegment {
                case Self.segmentBleeding:
                    BleedingSection(search: search, reloadTick: reloadTick)
                case Self.segmentCorrelations:
                    CorrelationSection(reloadTick: reloadTick)
                default:
                    journalSegment
                }
            }
            .padding(.horizontal, Metrics.screenMargin)
            .padding(.top, Spacing.s)
            .padding(.bottom, Spacing.m)
        }
        .background(palette.surface.ignoresSafeArea())
        .navigationTitle("Ressenti")
        .navigationBarTitleDisplayMode(.inline)
        .searchable(text: $search, prompt: "Rechercher dans tes notes")
        .eggActionBar {
            if effectiveSegment == Self.segmentBleeding {
                ActionBarButton("Noter mes règles", systemImage: "plus") {
                    router.push(.addBleeding(id: nil))
                }
            } else {
                ActionBarButton("Noter mon ressenti", systemImage: "plus") {
                    router.push(.addJournal(id: nil))
                }
            }
        }
        .task(id: reloadTick) {
            guard let session = app.session else { return }
            await vm.load(session)
            await vm.loadOverlays(
                session, month: visibleMonth, cal: cal, bleedingEnabled: features.bleeding)
        }
        .onAppear { if depth == nil { depth = router.path.count } }
        .onChange(of: router.path.count) { _, current in
            if current == depth { reloadTick += 1 }
        }
        .onChange(of: visibleMonth) { _, month in
            guard let session = app.session else { return }
            Task {
                await vm.loadOverlays(
                    session, month: month, cal: cal, bleedingEnabled: features.bleeding)
            }
        }
        .onChange(of: features.bleeding) { _, enabled in
            // The flag can flip while this screen is on the stack. Bring the
            // selection back onto an existing segment, and drop the band the
            // calendar is still drawing.
            segment = effectiveSegment
            guard let session = app.session else { return }
            Task {
                await vm.loadOverlays(
                    session, month: visibleMonth, cal: cal, bleedingEnabled: enabled)
            }
        }
    }

    // MARK: - Segment « Journal »

    @ViewBuilder
    private var journalSegment: some View {
        // « Ton résumé » lost its own tile in the refonte; it lands here, in
        // front of the history it summarises (D5).
        ListGroup {
            ListRowView(
                title: "Ton résumé",
                subtitle: "Ce que la période raconte, face à la précédente",
                systemImage: "chart.bar.doc.horizontal",
                iconContainer: palette.primaryContainer,
                iconTint: palette.onPrimaryContainer,
                showsChevron: true,
                action: { router.push(.summary) })
        }

        CalendarCard(
            month: $visibleMonth,
            selectedDay: $selectedDay,
            moodByDay: vm.moodByDay(cal),
            overlays: vm.overlays)

        SectionTitleView(
            selectedDay == nil ? "Historique" : Self.dayHeader(selectedDay!),
            action: selectedDay == nil ? "Corrélations" : "Tout voir",
            onAction: {
                if selectedDay == nil {
                    segment = Self.segmentCorrelations
                } else {
                    selectedDay = nil
                }
            },
            prominent: true)

        if vm.loading {
            SkeletonBlock(height: 96, cornerRadius: Radius.card)
            SkeletonBlock(height: 96, cornerRadius: Radius.card)
        } else if let message = vm.error {
            ErrorCardView(message, retryLabel: "Réessayer") { reloadTick += 1 }
        } else if visibleEntries.isEmpty {
            if search.isEmpty {
                EmptyStateView(
                    emptyMessage,
                    systemImage: "square.and.pencil",
                    actionLabel: "Noter mon ressenti",
                    action: { router.push(.addJournal(id: nil)) })
            } else {
                EmptyStateView(emptyMessage, systemImage: "magnifyingglass")
            }
        } else {
            ForEach(visibleEntries, id: \.id) { entry in
                entryCard(entry)
            }
        }
    }

    private var emptyMessage: String {
        if !search.isEmpty { return "Rien ne correspond à « \(search) »." }
        if selectedDay != nil { return "Tu n'as rien noté ce jour-là. Ce n'est pas grave." }
        return "Ton journal est encore vide. Note une première fois comment tu te sens — "
            + "même trois mots, ça compte."
    }

    private var visibleEntries: [JournalEntry] {
        var list = vm.entries
        if let day = selectedDay {
            list = list.filter {
                cal.isDate(Date(timeIntervalSince1970: Double($0.atMs) / 1000), inSameDayAs: day)
            }
        }
        let query = search.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return list }
        let needle = Self.fold(query)
        return list.filter { entry in
            Self.fold("\(entry.freeText ?? "") \(entry.sideEffects ?? "")").contains(needle)
        }
    }

    // MARK: - Entry card (§6.7 §1.4)

    private func entryCard(_ entry: JournalEntry) -> some View {
        EggCard(variant: .low, action: { router.push(.addJournal(id: entry.id)) }) {
            HStack(alignment: .top, spacing: 14) {
                MoodBars(axes: axes(for: entry))
                VStack(alignment: .leading, spacing: 3) {
                    MicroLabel(Self.entryDateLabel(entry.atMs))
                    if let text = entry.freeText, !text.isEmpty {
                        Text(text)
                            .font(.eggBody)
                            .foregroundStyle(palette.onSurface)
                            .lineLimit(3)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    let effects = Self.effects(entry.sideEffects)
                    if !effects.isEmpty {
                        ChipFlowLayout(spacing: 6, lineSpacing: 6) {
                            ForEach(effects, id: \.self) { effect in
                                EffectTag(effect)
                            }
                        }
                        .padding(.top, 6)
                    }
                }
                Spacer(minLength: 0)
            }
        }
        .accessibilityElement(children: .combine)
    }

    private func axes(for entry: JournalEntry) -> [(Double, Color)] {
        vm.barDefs.compactMap { def -> (Double, Color)? in
            guard let raw = vm.barValue(entry, def) else { return nil }
            let span = max(1.0, Double(def.maxValue) - Double(def.minValue))
            let fraction = (Double(raw) - Double(def.minValue)) / span
            return (min(1, max(0, fraction)), MetricAccents.color(def, palette))
        }
    }

    // MARK: - Formatting

    private static func fold(_ text: String) -> String {
        text.folding(
            options: [.diacriticInsensitive, .caseInsensitive],
            locale: Locale(identifier: "fr_FR"))
    }

    /// The comma-separated side-effect field, as the chips the user picked.
    static func effects(_ raw: String?) -> [String] {
        (raw ?? "")
            .split(separator: ",")
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
    }

    /// « HIER · 21:30 », « AUJOURD'HUI · 08:10 », « 24 JUILLET · 08:10 ».
    static func entryDateLabel(_ ms: Int64, now: Date = Date()) -> String {
        let date = Date(timeIntervalSince1970: Double(ms) / 1000)
        let cal = Calendar.current
        let time = DateFormatter()
        time.locale = Locale(identifier: "fr_FR")
        time.dateFormat = "HH:mm"

        let day: String
        if cal.isDate(date, inSameDayAs: now) {
            day = "AUJOURD'HUI"
        } else if let yesterday = cal.date(byAdding: .day, value: -1, to: now),
                  cal.isDate(date, inSameDayAs: yesterday) {
            day = "HIER"
        } else {
            let formatter = DateFormatter()
            formatter.locale = Locale(identifier: "fr_FR")
            formatter.dateFormat = cal.isDate(date, equalTo: now, toGranularity: .year)
                ? "d MMMM" : "d MMMM yyyy"
            day = formatter.string(from: date).uppercased()
        }
        return "\(day) · \(time.string(from: date))"
    }

    static func dayHeader(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "fr_FR")
        formatter.dateFormat = "EEEE d MMMM"
        let text = formatter.string(from: date)
        return text.prefix(1).uppercased() + text.dropFirst()
    }
}

// MARK: - Carte calendrier (§6.7 §1.2/§1.3)

/// Six rows of seven, always — a month never reflows the page under the
/// history by gaining or losing a line (D4).
private struct CalendarCard: View {
    @Environment(\.palette) private var palette

    @Binding var month: Date
    @Binding var selectedDay: Date?
    let moodByDay: [Date: Double]
    let overlays: JournalViewModel.Overlays

    /// Ø of the day disc, and therefore the radius the band's end caps clamp to.
    private let disc: CGFloat = 28
    private let cellHeight: CGFloat = 34
    private let gap: CGFloat = 2

    private var cal: Calendar { Calendar.current }

    var body: some View {
        EggCard(variant: .low, paddingH: 16, paddingV: 16, spacing: 0) {
            header
            weekdayHeader
            grid
            legend
        }
    }

    // MARK: Month header

    private var monthStart: Date {
        cal.dateInterval(of: .month, for: month)?.start ?? cal.startOfDay(for: month)
    }

    private var daysInMonth: Int {
        cal.range(of: .day, in: .month, for: monthStart)?.count ?? 30
    }

    /// Empty slots before the 1st, so it lands under its weekday column. The
    /// first day of the week comes from the locale, never from a constant.
    private var leadingBlanks: Int {
        let weekdayOfFirst = cal.component(.weekday, from: monthStart)
        return ((weekdayOfFirst - cal.firstWeekday) + 7) % 7
    }

    private var header: some View {
        HStack(spacing: 0) {
            chevron("chevron.left", label: "Mois précédent") { shift(-1) }
            Spacer(minLength: 0)
            Text(Self.monthLabel(monthStart))
                .font(EggFont.titleS)
                .foregroundStyle(palette.onSurface)
            Spacer(minLength: 0)
            chevron("chevron.right", label: "Mois suivant") { shift(1) }
        }
        .padding(.bottom, 10)
    }

    private func chevron(_ systemImage: String, label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(palette.onSurfaceVariant)
                .frame(width: Metrics.touchTarget, height: 28)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }

    private func shift(_ delta: Int) {
        guard let next = cal.date(byAdding: .month, value: delta, to: monthStart) else { return }
        withAnimation(.easeOut(duration: 0.15)) { month = next }
    }

    private var weekdayHeader: some View {
        HStack(spacing: gap) {
            ForEach(Array(Self.weekdaySymbols(cal).enumerated()), id: \.offset) { pair in
                Text(pair.element)
                    .font(EggFont.micro)
                    .foregroundStyle(palette.onSurfaceVariant.opacity(0.7))
                    .frame(maxWidth: .infinity)
            }
        }
        .padding(.bottom, 4)
        .accessibilityHidden(true)
    }

    // MARK: Grid

    private var grid: some View {
        VStack(spacing: gap) {
            ForEach(0..<6, id: \.self) { row in
                HStack(spacing: gap) {
                    ForEach(0..<7, id: \.self) { column in
                        let number = row * 7 + column - leadingBlanks + 1
                        if number >= 1, number <= daysInMonth,
                           let date = cal.date(byAdding: .day, value: number - 1, to: monthStart) {
                            dayCell(cal.startOfDay(for: date))
                        } else {
                            // A slot outside the month still holds its column,
                            // so the weekdays stay aligned all the way down.
                            Color.clear
                                .frame(height: cellHeight)
                                .frame(maxWidth: .infinity)
                        }
                    }
                }
            }
        }
    }

    private func dayCell(_ day: Date) -> some View {
        let isToday = cal.isDateInToday(day)
        let isSelected = selectedDay.map { cal.isDate($0, inSameDayAs: day) } ?? false
        let mood = moodByDay[day]
        let bleeding = overlays.bleedingDays.contains(day)
        let previous = cal.date(byAdding: .day, value: -1, to: day).map { cal.startOfDay(for: $0) }
        let next = cal.date(byAdding: .day, value: 1, to: day).map { cal.startOfDay(for: $0) }
        let opensRun = bleeding && !(previous.map { overlays.bleedingDays.contains($0) } ?? false)
        let closesRun = bleeding && !(next.map { overlays.bleedingDays.contains($0) } ?? false)
        let dots: [Color] = (overlays.medsByDay[day] ?? []).prefix(3).map { id in
            overlays.medColors[id].map { MedColor.color(fromArgb: $0) } ?? palette.tertiary
        }

        return Button {
            withAnimation(.easeOut(duration: 0.15)) {
                selectedDay = isSelected ? nil : day
            }
        } label: {
            ZStack {
                if bleeding {
                    // The −2 inset swallows the grid gap so consecutive days
                    // weld into one bar; only the two ends of the run are
                    // rounded, week boundaries included (D4).
                    UnevenRoundedRectangle(
                        topLeadingRadius: opensRun ? disc / 2 : 0,
                        bottomLeadingRadius: opensRun ? disc / 2 : 0,
                        bottomTrailingRadius: closesRun ? disc / 2 : 0,
                        topTrailingRadius: closesRun ? disc / 2 : 0,
                        style: .continuous)
                        .fill(palette.errorContainer)
                        .frame(height: cellHeight - 6)
                        .padding(.leading, opensRun ? 3 : -gap)
                        .padding(.trailing, closesRun ? 3 : -gap)
                }
                if isToday {
                    Circle().fill(palette.primary).frame(width: disc, height: disc)
                } else if let mood {
                    // Deliberately faint: the tint is a texture over the month,
                    // not a value to read off a single cell.
                    Circle()
                        .fill(palette.primary.opacity(min(1, max(0, mood / 10)) * 0.42))
                        .frame(width: disc, height: disc)
                }
                if isSelected {
                    Circle()
                        .strokeBorder(palette.primary, lineWidth: 2)
                        .frame(width: disc, height: disc)
                }
                Text("\(cal.component(.day, from: day))")
                    .font(.system(size: 13, weight: isToday ? .bold : .regular))
                    .foregroundStyle(isToday ? palette.onPrimary : palette.onSurface)
                if !dots.isEmpty {
                    HStack(spacing: 3) {
                        ForEach(Array(dots.enumerated()), id: \.offset) { pair in
                            // On today's filled disc the dots switch ink rather
                            // than disappear: nothing is lost to the highlight.
                            Circle()
                                .fill(isToday ? palette.onPrimary : pair.element)
                                .frame(width: 4, height: 4)
                        }
                    }
                    .opacity(0.85)
                    .padding(.bottom, 2)
                    .frame(maxHeight: .infinity, alignment: .bottom)
                }
            }
            .frame(height: cellHeight)
            .frame(maxWidth: .infinity)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Self.dayDescription(
            day, mood: mood, doseCount: dots.count, bleeding: bleeding, isToday: isToday))
        .accessibilityAddTraits(isSelected ? [.isSelected] : [])
    }

    // MARK: Legend

    /// Only what the displayed month actually contains (README §6.7).
    private var legend: some View {
        let days: [Date] = (0..<daysInMonth).compactMap {
            cal.date(byAdding: .day, value: $0, to: monthStart)
        }.map { cal.startOfDay(for: $0) }
        let hasMood = days.contains { moodByDay[$0] != nil }
        let hasBleeding = days.contains { overlays.bleedingDays.contains($0) }
        var medIds: [Int64] = []
        for day in days {
            for id in overlays.medsByDay[day] ?? [] where !medIds.contains(id) {
                medIds.append(id)
            }
        }

        return Group {
            if hasMood || hasBleeding || !medIds.isEmpty {
                VStack(spacing: 0) {
                    CardRule().padding(.top, 12)
                    ChipFlowLayout(spacing: 14, lineSpacing: 6) {
                        if hasMood {
                            legendItem(palette.primary, "Humeur", band: false)
                        }
                        ForEach(medIds, id: \.self) { id in
                            legendItem(
                                overlays.medColors[id].map { MedColor.color(fromArgb: $0) }
                                    ?? palette.tertiary,
                                overlays.medNames[id] ?? "Traitement",
                                band: false)
                        }
                        if hasBleeding {
                            legendItem(palette.errorContainer, "Règles", band: true)
                        }
                    }
                    .padding(.top, 12)
                }
            }
        }
    }

    private func legendItem(_ color: Color, _ label: String, band: Bool) -> some View {
        HStack(spacing: 6) {
            if band {
                Capsule().fill(color).frame(width: 16, height: 8)
            } else {
                Circle().fill(color).frame(width: 9, height: 9)
            }
            Text(label)
                .font(EggFont.micro)
                .foregroundStyle(palette.onSurfaceVariant)
        }
    }

    // MARK: Formatting

    private static func monthLabel(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "fr_FR")
        formatter.dateFormat = "LLLL yyyy"
        let text = formatter.string(from: date)
        return text.prefix(1).uppercased() + text.dropFirst()
    }

    /// Weekday initials rotated to the locale's first day of the week.
    private static func weekdaySymbols(_ cal: Calendar) -> [String] {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "fr_FR")
        let symbols = formatter.veryShortStandaloneWeekdaySymbols
            ?? ["D", "L", "M", "M", "J", "V", "S"]
        let start = cal.firstWeekday - 1
        return (0..<7).map { symbols[(start + $0) % symbols.count] }
    }

    /// A canvas cell has no readable content of its own, so the whole day is
    /// announced as one sentence (§10).
    private static func dayDescription(
        _ day: Date, mood: Double?, doseCount: Int, bleeding: Bool, isToday: Bool
    ) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "fr_FR")
        formatter.dateFormat = "d MMMM"
        var parts = [isToday ? "Aujourd'hui, \(formatter.string(from: day))"
                             : formatter.string(from: day)]
        if let mood {
            parts.append("humeur \(Int(mood.rounded())) sur 10")
        }
        if doseCount == 1 {
            parts.append("1 traitement pris")
        } else if doseCount > 1 {
            parts.append("\(doseCount) traitements pris")
        }
        if bleeding { parts.append("règles") }
        return parts.joined(separator: ", ")
    }
}

// MARK: - Petits éléments

/// The four axes of a history entry, each in its own accent (D4).
private struct MoodBars: View {
    @Environment(\.palette) private var palette
    let axes: [(Double, Color)]

    var body: some View {
        HStack(alignment: .bottom, spacing: 5) {
            ForEach(Array(axes.enumerated()), id: \.offset) { pair in
                ZStack(alignment: .bottom) {
                    Capsule()
                        .fill(palette.surfaceContainerHighest)
                        .frame(width: 8, height: 44)
                    Capsule()
                        .fill(pair.element.1)
                        .frame(width: 8, height: max(4, 44 * pair.element.0))
                }
            }
        }
        .frame(height: 44)
        .accessibilityHidden(true)
    }
}

/// A read-only effect chip under a history entry.
private struct EffectTag: View {
    @Environment(\.palette) private var palette
    let text: String

    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text)
            .font(EggFont.micro)
            .foregroundStyle(palette.onSurfaceVariant)
            .padding(.horizontal, 9)
            .padding(.vertical, 3)
            .background(palette.surfaceContainerHighest, in: Capsule())
    }
}
