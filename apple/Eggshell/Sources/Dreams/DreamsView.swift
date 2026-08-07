import SwiftUI
import TransitionCore

@MainActor
final class DreamsViewModel: ObservableObject {

    /// A dream plus what the list needs without a second round-trip.
    struct Row: Identifiable {
        let dream: Dream
        let tags: [DreamTag]
        let audioCount: Int
        var id: Int64 { dream.id }
    }

    @Published var rows: [Row] = []
    /// Local midnight of every night that has a dream, for the calendar.
    @Published var nights: [Date: Row] = [:]
    @Published var tags: [DreamTag] = []
    /// Nil = every dream.
    @Published var filterTagId: Int64?
    @Published var loading = true

    func load(session: VaultService, store: DreamsStore) async {
        loading = true
        let all = await store.list(session: session, tagId: filterTagId)
        let allTags = await store.tags(session: session)
        // A filter whose tag has since been deleted would silently show an
        // empty journal; fall back to everything rather than to nothing.
        if let filter = filterTagId, !allTags.contains(where: { $0.id == filter }) {
            filterTagId = nil
            await load(session: session, store: store)
            return
        }

        var built: [Row] = []
        for d in all {
            built.append(
                Row(
                    dream: d,
                    tags: await store.tags(session: session, for: d.id),
                    audioCount: await store.audio(session: session, for: d.id).count))
        }
        let cal = Calendar.current
        rows = built
        tags = allTags
        nights = Dictionary(
            built.map { row in
                (cal.startOfDay(for: Date(timeIntervalSince1970: Double(row.dream.nightMs) / 1000)), row)
            },
            uniquingKeysWith: { first, _ in first })
        loading = false
    }
}

/// « Carnet de rêves ».
///
/// Opens on the calendar, like the mood journal: a dream journal is read for
/// its shape over weeks — which nights are blank, where a run of recall starts
/// — and a list shows that one row at a time. Tapping an empty night opens the
/// editor already set to it, which is the whole reason the grid earns its
/// space: you remember a dream two days late and file it against the right
/// night without a date picker.
struct DreamsView: View {
    @EnvironmentObject private var app: AppState
    @EnvironmentObject private var router: Router
    @EnvironmentObject private var dreamsStore: DreamsStore
    @Environment(\.palette) private var palette
    @StateObject private var vm = DreamsViewModel()

    @State private var month = Date()
    @State private var depth: Int?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Metrics.blockGap) {
                MonthGrid(month: $month) { day in
                    DreamDayCell(
                        day: day,
                        row: vm.nights[day],
                        onTap: {
                            if let existing = vm.nights[day] {
                                router.push(.dreamEditor(id: existing.dream.id, nightMs: nil))
                            } else {
                                router.push(
                                    .dreamEditor(
                                        id: nil,
                                        nightMs: DreamsStore.nightOf(date: day)))
                            }
                        })
                }

                // The tag row is the point of the screen: a dream journal is
                // kept to notice what repeats, and this is where repetition
                // becomes visible.
                if !vm.tags.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 7) {
                            tagChip(label: "Tous", selected: vm.filterTagId == nil) {
                                vm.filterTagId = nil
                                reload()
                            }
                            ForEach(vm.tags, id: \.id) { tag in
                                tagChip(
                                    // The count is what makes the row readable:
                                    // it says which themes actually recur.
                                    label: "\(tag.label) · \(tag.dreamCount)",
                                    selected: vm.filterTagId == tag.id
                                ) {
                                    vm.filterTagId = vm.filterTagId == tag.id ? nil : tag.id
                                    reload()
                                }
                            }
                        }
                    }
                }

                if vm.rows.isEmpty && !vm.loading {
                    EmptyStateView(
                        vm.filterTagId == nil
                            ? "Aucun rêve noté. Le souvenir s’efface en quelques minutes — le plus tôt est le mieux."
                            : "Aucun rêve avec ce tag.",
                        systemImage: "moon.zzz",
                        actionLabel: "Noter un rêve"
                    ) {
                        router.push(.dreamEditor(id: nil, nightMs: nil))
                    }
                } else {
                    ForEach(vm.rows) { row in
                        Button {
                            router.push(.dreamEditor(id: row.dream.id, nightMs: nil))
                        } label: {
                            DreamCard(row: row)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            .padding(.horizontal, Metrics.screenMargin)
            .padding(.bottom, Metrics.blockGap)
        }
        .background(palette.surface.ignoresSafeArea())
        .navigationTitle("Carnet de rêves")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    router.push(.dreamEditor(id: nil, nightMs: nil))
                } label: {
                    Label("Noter un rêve", systemImage: "plus")
                }
            }
        }
        .task { reload() }
        .onAppear { if depth == nil { depth = router.path.count } }
        .onChange(of: router.path.count) { _, count in
            // `.task` does not re-fire when the editor pushed on top pops, so
            // the stack depth says when to read again.
            if let depth, count == depth { reload() }
        }
    }

    private func tagChip(label: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .font(EggFont.label)
                .foregroundStyle(selected ? palette.onSecondaryContainer : palette.onSurfaceVariant)
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(
                    selected ? palette.secondaryContainer : palette.surfaceContainerHighest,
                    in: Capsule())
        }
        .buttonStyle(.plain)
    }

    private func reload() {
        guard let session = app.session else { return }
        Task { await vm.load(session: session, store: dreamsStore) }
    }
}

/// One night.
///
/// A night either has a dream or it does not — there is no continuous value to
/// shade the way mood shades a journal cell — so presence is a filled disc,
/// lucidity a ring around it, and a voice note a dot. Three states the eye
/// separates at a glance, none of them told by colour alone.
private struct DreamDayCell: View {
    @Environment(\.palette) private var palette

    let day: Date
    let row: DreamsViewModel.Row?
    let onTap: () -> Void

    private var cal: Calendar { Calendar.current }

    var body: some View {
        let isToday = cal.isDateInToday(day)
        let lucid = row?.dream.lucid == true

        Button(action: onTap) {
            ZStack {
                if row != nil {
                    Circle()
                        .fill(lucid ? palette.tertiaryContainer : palette.secondaryContainer)
                        .frame(width: MonthGridMetrics.disc, height: MonthGridMetrics.disc)
                    if lucid {
                        Circle()
                            .strokeBorder(palette.tertiary, lineWidth: 1.5)
                            .frame(width: MonthGridMetrics.disc, height: MonthGridMetrics.disc)
                    }
                } else if isToday {
                    Circle()
                        .strokeBorder(palette.primary, lineWidth: 1)
                        .frame(width: MonthGridMetrics.disc, height: MonthGridMetrics.disc)
                }
                Text("\(cal.component(.day, from: day))")
                    .font(.system(size: 13, weight: row != nil ? .semibold : .regular))
                    .foregroundStyle(
                        lucid
                            ? palette.onTertiaryContainer
                            : (row != nil ? palette.onSecondaryContainer : palette.onSurface))
                if (row?.audioCount ?? 0) > 0 {
                    VStack {
                        Spacer()
                        Circle().fill(palette.tertiary).frame(width: 4, height: 4)
                    }
                    .frame(height: MonthGridMetrics.cellHeight)
                }
            }
            .frame(height: MonthGridMetrics.cellHeight)
            .frame(maxWidth: .infinity)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(accessibilityLabel(isToday: isToday, lucid: lucid))
    }

    private func accessibilityLabel(isToday: Bool, lucid: Bool) -> String {
        var parts = ["\(cal.component(.day, from: day))"]
        if isToday { parts.append("aujourd’hui") }
        if row != nil { parts.append("rêve noté") }
        if lucid { parts.append("lucide") }
        if (row?.audioCount ?? 0) > 0 { parts.append("note vocale") }
        return parts.joined(separator: ", ")
    }
}

private struct DreamCard: View {
    @Environment(\.palette) private var palette
    let row: DreamsViewModel.Row

    var body: some View {
        EggCard(variant: .low, paddingH: 18, paddingV: 14, spacing: 0) {
            HStack(alignment: .top, spacing: Spacing.s) {
                VStack(alignment: .leading, spacing: 2) {
                    // The night, not the writing date — stated first because it
                    // is what the entry is about.
                    MicroLabel("NUIT DU " + MeasureFormat.upper(nightLabel))
                    Text(row.dream.title.isEmpty ? "Sans titre" : row.dream.title)
                        .font(EggFont.titleS)
                        .foregroundStyle(palette.onSurface)
                        .multilineTextAlignment(.leading)
                }
                Spacer(minLength: Spacing.s)
                if row.dream.lucid {
                    StatusPillView(
                        "Lucide",
                        container: palette.tertiaryContainer,
                        content: palette.onTertiaryContainer)
                }
            }

            if !row.dream.body.isEmpty {
                Text(row.dream.body)
                    .font(EggFont.bodyS)
                    .foregroundStyle(palette.onSurfaceVariant)
                    .lineLimit(2)
                    .multilineTextAlignment(.leading)
                    .padding(.top, 6)
            }

            if !row.tags.isEmpty || row.audioCount > 0 {
                HStack(spacing: 6) {
                    if row.audioCount > 0 {
                        Image(systemName: "waveform")
                            .font(.system(size: 12))
                            .foregroundStyle(palette.onSurfaceVariant)
                    }
                    ForEach(row.tags.prefix(3), id: \.id) { tag in
                        StatusPillView(
                            tag.label,
                            container: palette.surfaceContainerHighest,
                            content: palette.onSurfaceVariant)
                    }
                    if row.tags.count > 3 {
                        MicroLabel("+\(row.tags.count - 3)")
                    }
                    Spacer(minLength: 0)
                }
                .padding(.top, 10)
            }
        }
    }

    private var nightLabel: String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "fr")
        f.dateFormat = "EEEE d MMMM"
        return f.string(from: Date(timeIntervalSince1970: Double(row.dream.nightMs) / 1000))
    }
}
