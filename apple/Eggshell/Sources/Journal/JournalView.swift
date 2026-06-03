import SwiftUI
import TransitionCore

@MainActor
final class JournalViewModel: ObservableObject {
    @Published var loading = true
    @Published var entries: [JournalEntry] = []
    @Published var error: String?

    func load(_ session: VaultService) async {
        loading = true
        do {
            entries = try await session.listJournalEntries(limit: 200)
        } catch {
            self.error = describe(error)
        }
        loading = false
    }
}

struct JournalView: View {
    @EnvironmentObject private var app: AppState
    @EnvironmentObject private var router: Router
    @Environment(\.palette) private var palette
    @StateObject private var vm = JournalViewModel()

    var body: some View {
        TabScaffold(title: "Journal") {
            if vm.loading {
                ProgressView().tint(palette.primary).frame(maxWidth: .infinity).padding()
            } else if vm.entries.isEmpty {
                EmptyStateCard(text: "Aucune entrée", systemImage: "book")
            } else {
                ForEach(vm.entries, id: \.id) { entry in
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

    private func entryCard(_ entry: JournalEntry) -> some View {
        SectionCard {
            Text(dateLabel(entry.atMs)).font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))

            HStack(spacing: Spacing.m) {
                metricBar("Humeur", entry.mood, palette.primary)
                metricBar("Euphorie", entry.euphoria, palette.tertiary)
                metricBar("Libido", entry.libido, palette.secondary)
                metricBar("Énergie", entry.energy, palette.success)
            }

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
    }

    private func metricBar(_ label: String, _ value: UInt32?, _ color: Color) -> some View {
        let fraction = min(1.0, max(0.0, Double(value ?? 0) / 10.0))
        return VStack(spacing: Spacing.xs) {
            ZStack(alignment: .bottom) {
                Capsule()
                    .fill(palette.surfaceContainerHigh)
                    .frame(width: 8, height: 36)
                Capsule()
                    .fill(color)
                    .frame(width: 8, height: max(2, 36 * fraction))
            }
            Text(label).font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.5))
        }
        .frame(maxWidth: .infinity)
    }

    private func dateLabel(_ ms: Int64) -> String {
        let date = Date(timeIntervalSince1970: Double(ms) / 1000.0)
        let f = DateFormatter()
        f.locale = Locale(identifier: "fr")
        f.dateStyle = .medium
        f.timeStyle = .short
        return f.string(from: date)
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
        var rows: [CGFloat] = [0]
        var rowWidth: CGFloat = 0
        var totalHeight: CGFloat = 0
        var rowHeight: CGFloat = 0
        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if rowWidth + size.width > maxWidth && rowWidth > 0 {
                totalHeight += rowHeight + spacing
                rows.append(0)
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
