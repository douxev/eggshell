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
        .task { if let s = app.session { await vm.load(s) } }
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
        }
    }

    private func dayCell(_ date: Date, today: Date, byDay: [Date: [JournalEntry]]) -> some View {
        let day = cal.startOfDay(for: date)
        let dayEntries = byDay[day] ?? []
        let isToday = cal.isDate(day, inSameDayAs: today)
        let isSelected = selectedDay.map { cal.isDate($0, inSameDayAs: day) } ?? false

        let moods = dayEntries.compactMap { $0.mood }.map { Double($0) }
        let avgMood: Double? = moods.isEmpty ? nil : moods.reduce(0, +) / Double(moods.count)

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
            VStack(spacing: 2) {
                Text("\(cal.component(.day, from: day))")
                    .font(.eggCallout.weight(isToday || isSelected ? .bold : .regular))
                    .foregroundStyle(onContainer)
                Circle().fill(dotColor).frame(width: 5, height: 5)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, Spacing.xs)
            .background(Circle().fill(container).padding(2))
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
