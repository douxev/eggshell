import SwiftUI

/// The month scaffold both journals share: the title row with its two chevrons,
/// the weekday header, and six week rows that call `cell` for each real day.
///
/// Extracted rather than copied because the fiddly parts are the ones a copy
/// gets subtly wrong and nobody notices for months: `firstWeekday` comes from
/// the locale (Monday in France, Sunday in the US), the leading-blank count
/// derives from it, and the grid is always six rows so the card does not change
/// height between a 28-day February and a 31-day month starting on a Sunday.
/// Two implementations of that would eventually disagree, and the one that
/// disagreed would be the one nobody was looking at.
///
/// What a day *looks* like is deliberately not here — a mood disc, a bleeding
/// run and a dream marker have nothing in common — so `cell` draws it and only
/// has to fill `cellHeight`.
struct MonthGrid<Cell: View, Footer: View>: View {
    @Environment(\.palette) private var palette

    @Binding var month: Date
    var cellHeight: CGFloat = MonthGridMetrics.cellHeight
    var gap: CGFloat = MonthGridMetrics.gap
    @ViewBuilder var cell: (Date) -> Cell
    @ViewBuilder var footer: () -> Footer

    private var cal: Calendar { Calendar.current }

    var body: some View {
        EggCard(variant: .low, paddingH: 16, paddingV: 16, spacing: 0) {
            header
            weekdayHeader
            grid
            footer()
        }
    }

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
            Text(MonthGridMetrics.monthLabel(monthStart))
                .font(EggFont.titleS)
                .foregroundStyle(palette.onSurface)
            Spacer(minLength: 0)
            chevron("chevron.right", label: "Mois suivant") { shift(1) }
        }
        .padding(.bottom, 10)
    }

    private func chevron(
        _ systemImage: String, label: String, action: @escaping () -> Void
    ) -> some View {
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
            ForEach(Array(MonthGridMetrics.weekdaySymbols(cal).enumerated()), id: \.offset) { pair in
                Text(pair.element)
                    .font(EggFont.micro)
                    .foregroundStyle(palette.onSurfaceVariant.opacity(0.7))
                    .frame(maxWidth: .infinity)
            }
        }
        .padding(.bottom, 4)
        .accessibilityHidden(true)
    }

    private var grid: some View {
        VStack(spacing: gap) {
            ForEach(0..<6, id: \.self) { row in
                HStack(spacing: gap) {
                    ForEach(0..<7, id: \.self) { column in
                        let number = row * 7 + column - leadingBlanks + 1
                        if number >= 1, number <= daysInMonth,
                           let date = cal.date(byAdding: .day, value: number - 1, to: monthStart) {
                            cell(cal.startOfDay(for: date))
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
}

extension MonthGrid where Footer == EmptyView {
    init(
        month: Binding<Date>,
        cellHeight: CGFloat = MonthGridMetrics.cellHeight,
        gap: CGFloat = MonthGridMetrics.gap,
        @ViewBuilder cell: @escaping (Date) -> Cell
    ) {
        self.init(
            month: month, cellHeight: cellHeight, gap: gap,
            cell: cell, footer: { EmptyView() })
    }
}

enum MonthGridMetrics {
    static let cellHeight: CGFloat = 34
    static let gap: CGFloat = 2
    /// Ø of the day disc, and therefore the radius a run's end caps clamp to.
    static let disc: CGFloat = 28

    static func monthLabel(_ date: Date) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "fr")
        f.dateFormat = "LLLL yyyy"
        return f.string(from: date).capitalized
    }

    /// Weekday initials, rotated to start on the locale's first day.
    static func weekdaySymbols(_ cal: Calendar) -> [String] {
        var f = DateFormatter()
        f.locale = Locale(identifier: "fr")
        let symbols = f.veryShortStandaloneWeekdaySymbols ?? ["D", "L", "M", "M", "J", "V", "S"]
        let shift = cal.firstWeekday - 1
        return (0..<7).map { symbols[($0 + shift) % 7].uppercased() }
    }
}
