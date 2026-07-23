import SwiftUI
import TransitionCore

// ===========================================================================
// TAB ROOT — Journal. Shows a monthly calendar (6×7 grid with month
// navigation + day selection, days tinted by their average mood) above the
// list of entries. Selecting a day filters the list to that day. A link
// opens the correlation view; the FAB creates a new entry. Mirrors android
// JournalListScreen.
// ===========================================================================

@MainActor
final class JournalViewModel: ObservableObject {
    @Published var loading = true
    @Published var entries: [JournalEntry] = []
    @Published var error: String?

    /// Calendar overlays: bleeding days (continuous band) + medication days
    /// (per-med colored dots), so dose↔mood correlations show at a glance.
    /// Mirrors android JournalListViewModel.Overlays.
    struct Overlays {
        var bleedingDays: Set<Date> = []          // startOfDay keys
        var medsByDay: [Date: [Int64]] = [:]      // startOfDay → distinct med ids
        var medColors: [Int64: Int64] = [:]       // med id → ARGB
        var medNames: [Int64: String] = [:]
    }
    @Published var overlays = Overlays()

    func load(_ session: VaultService) async {
        loading = true
        do {
            entries = try await session.listJournalEntries(limit: 200)
                .sorted { $0.atMs > $1.atMs }
        } catch {
            self.error = describe(error)
        }
        loading = false
    }

    /// (Re)load the overlay data for the month the calendar is showing.
    /// Monotonic ticket so a slow older month's result can't overwrite the
    /// overlays of the month currently on screen (fast prev/next paging).
    private var overlaysTicket = 0

    func loadOverlays(_ session: VaultService, month: Date, cal: Calendar) async {
        overlaysTicket += 1
        let ticket = overlaysTicket
        guard let interval = cal.dateInterval(of: .month, for: month) else { return }
        let fromMs = Int64(interval.start.timeIntervalSince1970 * 1000)
        let toMs = Int64(interval.end.timeIntervalSince1970 * 1000) - 1
        do {
            let doses = try await session.listDoseEventsBetween(fromMs: fromMs, toMs: toMs)
                .filter { $0.status == "taken" }
            let meds = try await session.listMedications(includeArchived: true)
            // Bleeding entries are newest-first; one page comfortably covers
            // years of cycle history.
            let bleeding = try await session.listBleedingEntries(limit: 1000)
            var byDay: [Date: [Int64]] = [:]
            for d in doses {
                let day = cal.startOfDay(for: Date(timeIntervalSince1970: Double(d.takenAtMs) / 1000))
                var ids = byDay[day] ?? []
                if !ids.contains(d.medicationId) { ids.append(d.medicationId) }
                byDay[day] = ids
            }
            var o = Overlays()
            o.bleedingDays = Set(bleeding.map {
                cal.startOfDay(for: Date(timeIntervalSince1970: Double($0.atMs) / 1000))
            })
            o.medsByDay = byDay
            o.medColors = Dictionary(uniqueKeysWithValues: meds.compactMap { m in
                m.color.map { (m.id, $0) }
            })
            o.medNames = Dictionary(uniqueKeysWithValues: meds.map { ($0.id, $0.name) })
            guard ticket == overlaysTicket else { return } // superseded by a newer month
            overlays = o
        } catch {
            // Overlays are decorative — but stale ones from another month are
            // worse than none, so reset instead of keeping the old grid.
            if ticket == overlaysTicket { overlays = Overlays() }
        }
    }

    /// Entries grouped by their calendar day (local time zone).
    func entriesByDay(_ cal: Calendar) -> [Date: [JournalEntry]] {
        Dictionary(grouping: entries) { entry in
            cal.startOfDay(for: Date(timeIntervalSince1970: Double(entry.atMs) / 1000))
        }
    }
}

struct JournalView: View {
    @EnvironmentObject private var app: AppState
    @EnvironmentObject private var router: Router
    @Environment(\.palette) private var palette
    @StateObject private var vm = JournalViewModel()

    // Calendar paging + selection state.
    @State private var visibleMonth: Date = Calendar.current.startOfDay(for: Date())
    @State private var selectedDay: Date?

    private var cal: Calendar { Calendar.current }

    var body: some View {
        TabScaffold(title: "Journal") {
            NavigationLink(value: Route.correlation) {
                HStack(spacing: Spacing.s) {
                    Image(systemName: "chart.xyaxis.line")
                    Text("Corrélations").font(.eggCallout)
                    Spacer()
                    Image(systemName: "chevron.right").font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.4))
                }
                .padding(.vertical, Spacing.xs)
                .foregroundStyle(palette.primary)
            }
            .buttonStyle(.plain)

            calendarCard

            let byDay = vm.entriesByDay(cal)
            let visible = visibleEntries(byDay)

            Text(selectedDay == nil ? "Historique" : dayHeader(selectedDay!))
                .font(.eggLabel)
                .foregroundStyle(palette.onSurface.opacity(0.6))
                .frame(maxWidth: .infinity, alignment: .leading)

            if vm.loading {
                ProgressView().tint(palette.primary).frame(maxWidth: .infinity).padding()
            } else if visible.isEmpty {
                EmptyStateCard(
                    text: selectedDay == nil ? "Aucune entrée" : "Aucune entrée ce jour-là",
                    systemImage: "book")
            } else {
                ForEach(visible, id: \.id) { entry in
                    Button {
                        router.push(.addJournal(id: entry.id))
                    } label: {
                        entryCard(entry)
                    }
                    .buttonStyle(.plain)
                }
            }
            if let e = vm.error { ErrorBanner(message: e) }
        }
        .overlay(alignment: .bottomTrailing) {
            Button { router.push(.addJournal(id: nil)) } label: {
                Image(systemName: "plus").font(.title2.weight(.semibold)).frame(width: 60, height: 60)
            }
            .glassProminentButton().tint(palette.primary)
            .clipShape(Circle())
            .padding(Spacing.xl)
        }
        .task {
            if let s = app.session {
                await vm.load(s)
                await vm.loadOverlays(s, month: visibleMonth, cal: cal)
            }
        }
        .onChange(of: visibleMonth) { _, newMonth in
            if let s = app.session {
                Task { await vm.loadOverlays(s, month: newMonth, cal: cal) }
            }
        }
    }

    // MARK: - List filtering

    private func visibleEntries(_ byDay: [Date: [JournalEntry]]) -> [JournalEntry] {
        guard let day = selectedDay else { return vm.entries }
        return (byDay[cal.startOfDay(for: day)] ?? []).sorted { $0.atMs > $1.atMs }
    }

    // MARK: - Calendar

    private var calendarCard: some View {
        let byDay = vm.entriesByDay(cal)
        let today = cal.startOfDay(for: Date())
        let monthStart = cal.dateInterval(of: .month, for: visibleMonth)?.start ?? visibleMonth
        let daysInMonth = cal.range(of: .day, in: .month, for: monthStart)?.count ?? 30
        // Leading blanks so the 1st lands under its weekday column.
        let firstWeekday = cal.firstWeekday // 1 = Sunday by default
        let weekdayOfFirst = cal.component(.weekday, from: monthStart) // 1...7
        let leadingBlanks = ((weekdayOfFirst - firstWeekday) + 7) % 7

        return SectionCard {
            HStack {
                Button { shiftMonth(-1) } label: {
                    Image(systemName: "chevron.left").font(.eggHeadline)
                }
                .buttonStyle(.plain).foregroundStyle(palette.onSurface)
                Spacer()
                Text(monthLabel(monthStart)).font(.eggHeadline).foregroundStyle(palette.onSurface)
                Spacer()
                Button { shiftMonth(1) } label: {
                    Image(systemName: "chevron.right").font(.eggHeadline)
                }
                .buttonStyle(.plain).foregroundStyle(palette.onSurface)
            }
            .padding(.bottom, Spacing.xs)

            // Weekday header row.
            HStack(spacing: 0) {
                ForEach(weekdaySymbols(), id: \.self) { sym in
                    Text(sym)
                        .font(.eggCaption)
                        .foregroundStyle(palette.onSurface.opacity(0.5))
                        .frame(maxWidth: .infinity)
                }
            }

            // 6×7 grid.
            let totalCells = leadingBlanks + daysInMonth
            let rows = (totalCells + 6) / 7
            VStack(spacing: Spacing.xs) {
                ForEach(0..<rows, id: \.self) { r in
                    HStack(spacing: 0) {
                        ForEach(0..<7, id: \.self) { c in
                            let dayNum = r * 7 + c - leadingBlanks + 1
                            if dayNum >= 1 && dayNum <= daysInMonth,
                               let date = cal.date(byAdding: .day, value: dayNum - 1, to: monthStart) {
                                dayCell(date, today: today, byDay: byDay)
                                    .frame(maxWidth: .infinity)
                            } else {
                                Color.clear.frame(maxWidth: .infinity).aspectRatio(1, contentMode: .fit)
                            }
                        }
                    }
                }
            }

            // Legend — only for what the visible month actually shows.
            let monthDays: [Date] = (0..<daysInMonth).compactMap {
                cal.date(byAdding: .day, value: $0, to: monthStart)
            }.map { cal.startOfDay(for: $0) }
            let monthHasBleeding = monthDays.contains { vm.overlays.bleedingDays.contains($0) }
            let monthMedIds = distinctMedIds(monthDays)
            if monthHasBleeding || !monthMedIds.isEmpty {
                FlowLayout(spacing: Spacing.m) {
                    if monthHasBleeding {
                        legendItem(color: palette.error.opacity(0.35), label: "Règles", band: true)
                    }
                    ForEach(monthMedIds, id: \.self) { id in
                        legendItem(
                            color: vm.overlays.medColors[id].map { MedColor.color(fromArgb: $0) } ?? palette.tertiary,
                            label: vm.overlays.medNames[id] ?? "",
                            band: false)
                    }
                }
                .padding(.top, Spacing.xs)
            }
        }
    }

    /// Distinct med ids seen across the given days, in first-seen order.
    private func distinctMedIds(_ days: [Date]) -> [Int64] {
        var ids: [Int64] = []
        for day in days {
            for id in vm.overlays.medsByDay[day] ?? [] where !ids.contains(id) {
                ids.append(id)
            }
        }
        return ids
    }

    private func legendItem(color: Color, label: String, band: Bool) -> some View {
        HStack(spacing: 4) {
            Capsule().fill(color).frame(width: band ? 14 : 6, height: 6)
            Text(label).font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.6))
        }
    }

    private func dayCell(_ date: Date, today: Date, byDay: [Date: [JournalEntry]]) -> some View {
        let day = cal.startOfDay(for: date)
        let dayEntries = byDay[day] ?? []
        let isToday = cal.isDate(day, inSameDayAs: today)
        let isSelected = selectedDay.map { cal.isDate($0, inSameDayAs: day) } ?? false

        let moods = dayEntries.compactMap { $0.mood }.map { Double($0) }
        let avgMood: Double? = moods.isEmpty ? nil : moods.reduce(0, +) / Double(moods.count)

        // Bleeding band: continuous across adjacent bleeding days, so the caps
        // are only rounded where the span actually starts/ends.
        let bleeding = vm.overlays.bleedingDays.contains(day)
        let bleedsLeft = bleeding && (cal.date(byAdding: .day, value: -1, to: day)
            .map { vm.overlays.bleedingDays.contains(cal.startOfDay(for: $0)) } ?? false)
        let bleedsRight = bleeding && (cal.date(byAdding: .day, value: 1, to: day)
            .map { vm.overlays.bleedingDays.contains(cal.startOfDay(for: $0)) } ?? false)

        // One dot per medication taken that day; nil = med without a color.
        let medDots: [Color?] = (vm.overlays.medsByDay[day] ?? []).map { id in
            vm.overlays.medColors[id].map { MedColor.color(fromArgb: $0) }
        }

        let container: Color = isSelected ? palette.primary
            : (isToday ? palette.primaryContainer : Color.clear)
        let onContainer: Color = isSelected ? palette.onPrimary
            : (isToday ? palette.onPrimaryContainer : palette.onSurface)

        let dotColor: Color = {
            guard !dayEntries.isEmpty else { return .clear }
            let base = isSelected ? palette.onPrimary : palette.primary
            guard let avg = avgMood else { return base }
            let intensity = min(1.0, max(0.3, avg / 10.0))
            return base.opacity(intensity)
        }()

        return Button {
            withAnimation(.easeInOut(duration: 0.15)) {
                if isSelected { selectedDay = nil } else { selectedDay = day }
            }
        } label: {
            ZStack {
                if bleeding {
                    UnevenRoundedRectangle(
                        topLeadingRadius: bleedsLeft ? 0 : 13,
                        bottomLeadingRadius: bleedsLeft ? 0 : 13,
                        bottomTrailingRadius: bleedsRight ? 0 : 13,
                        topTrailingRadius: bleedsRight ? 0 : 13)
                        .fill(palette.error.opacity(0.18))
                        .frame(height: 26)
                        .padding(.leading, bleedsLeft ? 0 : 3)
                        .padding(.trailing, bleedsRight ? 0 : 3)
                }
                VStack(spacing: 2) {
                    Text("\(cal.component(.day, from: day))")
                        .font(.eggCallout.weight(isToday || isSelected ? .bold : .regular))
                        .foregroundStyle(onContainer)
                    HStack(spacing: 2) {
                        Circle().fill(dotColor).frame(width: 5, height: 5)
                        // Up to three treatment dots keep the cell readable; a
                        // fourth med collapses into the legend below the grid.
                        ForEach(Array(medDots.prefix(3).enumerated()), id: \.offset) { _, medColor in
                            Circle()
                                .fill(medColor ?? (isSelected ? palette.onPrimary : palette.tertiary))
                                .frame(width: 4, height: 4)
                        }
                    }
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, Spacing.xs)
                .background(Circle().fill(container).padding(2))
            }
        }
        .buttonStyle(.plain)
    }

    private func shiftMonth(_ delta: Int) {
        if let next = cal.date(byAdding: .month, value: delta, to: visibleMonth) {
            withAnimation(.easeInOut(duration: 0.15)) { visibleMonth = next }
        }
    }

    private func weekdaySymbols() -> [String] {
        let f = DateFormatter()
        f.locale = Locale(identifier: "fr_FR")
        let short = f.veryShortStandaloneWeekdaySymbols ?? ["D", "L", "M", "M", "J", "V", "S"]
        // Rotate so the array starts at calendar.firstWeekday (1-based).
        let start = cal.firstWeekday - 1
        return (0..<7).map { short[(start + $0) % 7] }
    }

    private func monthLabel(_ date: Date) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "fr_FR")
        f.dateFormat = "LLLL yyyy"
        return f.string(from: date).capitalizedFirst
    }

    private func dayHeader(_ date: Date) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "fr_FR")
        f.dateFormat = "EEEE d MMMM"
        return f.string(from: date).capitalizedFirst
    }

    // MARK: - Entry card

    private func entryCard(_ entry: JournalEntry) -> some View {
        SectionCard {
            HStack(alignment: .top, spacing: Spacing.m) {
                miniBars(entry)
                VStack(alignment: .leading, spacing: Spacing.xs) {
                    Text(dateLabel(entry.atMs))
                        .font(.eggLabel)
                        .foregroundStyle(palette.onSurface.opacity(0.6))

                    if let sideEffects = entry.sideEffects, !sideEffects.isEmpty {
                        let pills = sideEffects
                            .split(separator: ",")
                            .map { $0.trimmingCharacters(in: .whitespaces) }
                            .filter { !$0.isEmpty }
                        if !pills.isEmpty {
                            WrapHStack(pills) { Pill(text: $0) }
                        }
                    }

                    if let freeText = entry.freeText, !freeText.isEmpty {
                        Text(freeText)
                            .font(.eggCallout)
                            .foregroundStyle(palette.onSurface.opacity(0.8))
                            .lineLimit(2)
                    }
                }
                Spacer(minLength: 0)
            }
        }
    }

    private func miniBars(_ entry: JournalEntry) -> some View {
        let bars: [(UInt32, Color)] = [
            entry.mood.map { ($0, palette.primary) },
            entry.euphoria.map { ($0, palette.tertiary) },
            entry.libido.map { ($0, palette.secondary) },
            entry.energy.map { ($0, palette.success) },
        ].compactMap { $0 }

        return HStack(alignment: .bottom, spacing: Spacing.xs) {
            ForEach(Array(bars.enumerated()), id: \.offset) { _, bar in
                let fraction = min(1.0, max(0.05, Double(bar.0) / 10.0))
                ZStack(alignment: .bottom) {
                    Capsule().fill(palette.surfaceContainerHigh).frame(width: 7, height: 40)
                    Capsule().fill(bar.1).frame(width: 7, height: max(3, 40 * fraction))
                }
            }
        }
        .frame(height: 40)
    }

    private func dateLabel(_ ms: Int64) -> String {
        let date = Date(timeIntervalSince1970: Double(ms) / 1000.0)
        let f = DateFormatter()
        f.locale = Locale(identifier: "fr_FR")
        f.dateStyle = .medium
        f.timeStyle = .short
        return f.string(from: date)
    }
}

private extension String {
    /// Uppercases only the first character (for French month/day labels which
    /// the formatter returns lowercase).
    var capitalizedFirst: String {
        guard let first = first else { return self }
        return String(first).uppercased() + dropFirst()
    }
}

// Lightweight flow layout so side-effect Pills wrap onto multiple lines.
private struct WrapHStack<Data: RandomAccessCollection, Content: View>: View where Data.Element: Hashable {
    let data: Data
    let content: (Data.Element) -> Content

    init(_ data: Data, @ViewBuilder content: @escaping (Data.Element) -> Content) {
        self.data = data
        self.content = content
    }

    var body: some View {
        FlowLayout(spacing: Spacing.xs) {
            ForEach(Array(data), id: \.self) { item in
                content(item)
            }
        }
    }
}

private struct FlowLayout: Layout {
    var spacing: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout Void) -> CGSize {
        let maxWidth = proposal.width ?? .infinity
        var rowWidth: CGFloat = 0
        var totalHeight: CGFloat = 0
        var rowHeight: CGFloat = 0
        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if rowWidth + size.width > maxWidth && rowWidth > 0 {
                totalHeight += rowHeight + spacing
                rowWidth = 0
                rowHeight = 0
            }
            rowWidth += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
        totalHeight += rowHeight
        return CGSize(width: maxWidth == .infinity ? rowWidth : maxWidth, height: totalHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout Void) {
        let maxX = bounds.maxX
        var x = bounds.minX
        var y = bounds.minY
        var rowHeight: CGFloat = 0
        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if x + size.width > maxX && x > bounds.minX {
                x = bounds.minX
                y += rowHeight + spacing
                rowHeight = 0
            }
            subview.place(at: CGPoint(x: x, y: y), anchor: .topLeading, proposal: ProposedViewSize(size))
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
    }
}
