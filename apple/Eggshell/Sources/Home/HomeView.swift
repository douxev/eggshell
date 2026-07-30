import SwiftUI
import TransitionCore

// Accueil — the only root screen of the refonte (§6.1).
//
// Three ideas govern it: the daily gesture (tick a dose, tap a face) happens
// here without navigating; the map of the app is visible above the fold as an
// eight-module launcher; and enabling a module never adds a destination, it
// only adds a tile.
//
// iOS variant of §6.1: a 22 pt bold title on one line instead of a header
// component, inset grouped cards of radius 20, and an anchored bottom bar
// instead of a FAB — the bar reserves its 84 pt band, it never floats over the
// last launcher row.

struct HomeView: View {
    @EnvironmentObject private var app: AppState
    @EnvironmentObject private var features: FeaturesStore
    @EnvironmentObject private var labReminders: LabReminderStore
    @EnvironmentObject private var router: Router
    @Environment(\.palette) private var palette
    @StateObject private var vm = HomeViewModel()

    /// The confirmation pill, with the one optional action §8 names.
    private struct Toast: Equatable {
        let message: String
        var actionLabel: String?
    }

    @State private var showQuickLog = false
    @State private var toast: Toast?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Metrics.blockGap) {
                header
                if let message = vm.error { ErrorCardView(message) }
                if vm.loading {
                    SkeletonBlock(height: 168, cornerRadius: Radius.card)
                    SkeletonBlock(height: 150, cornerRadius: Radius.card)
                } else {
                    if features.medications {
                        DoseCard(
                            vm: vm,
                            onMarkTaken: markNextTaken,
                            onSnooze: { if let next = vm.nextDose { vm.snooze(next) } },
                            onAddMedication: { router.push(.addMedication) })
                    }
                    if features.journal {
                        MoodCard(
                            selectedFace: vm.moodFace,
                            onPick: pickMood,
                            onOpenDetail: { router.push(.addJournal(id: nil)) })
                    }
                }
                trackingSection
                LauncherGrid(features: features, badges: vm.badges, onOpen: open)
                Color.clear.frame(height: Spacing.m)
            }
            .padding(.horizontal, Metrics.screenMargin)
        }
        .background(palette.surface.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .eggActionBar {
            ActionBarButton("Noter", systemImage: "plus") { showQuickLog = true }
                .accessibilityLabel("Noter rapidement")
        }
        .overlay(alignment: .bottom) {
            if let toast {
                // The action is always supplied; `SnackbarView` only draws the
                // button when a label goes with it, so the pill stays plain for
                // every confirmation but the mood one.
                SnackbarView(
                    message: toast.message,
                    actionLabel: toast.actionLabel,
                    action: {
                        self.toast = nil
                        router.push(.journal)
                    })
                    // The pill clears the anchored bar instead of covering
                    // « Noter » for the whole time it is shown, like the three
                    // other screens that pair a snackbar with an action bar.
                    .padding(.bottom, Metrics.actionBarHeight + Spacing.m)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .sheet(isPresented: $showQuickLog) {
            QuickLogSheet(hasInjectable: vm.hasInjectable)
                .environmentObject(router)
                .environmentObject(features)
                .presentationDetents([.medium])
                .presentationDragIndicator(.visible)
        }
        .sensoryFeedback(.success, trigger: vm.savedTick)
        .task { await reload() }
        // A root's `.task` does not re-fire when a pushed screen pops, so the
        // home refreshes itself every time the stack comes back to it.
        .onChange(of: router.path.count) { _, depth in
            if depth == 0 { Task { await reload() } }
        }
    }

    // MARK: - Header (§4: large title on ONE line + a round 34 pt button)

    private var header: some View {
        HStack(alignment: .center, spacing: Spacing.s) {
            Text(Self.dateTitle())
                .font(EggFont.screenTitle)
                .foregroundStyle(palette.onSurface)
                .lineLimit(1)
                .minimumScaleFactor(0.72)
            Spacer(minLength: 0)
            Button { router.push(.settingsHub) } label: {
                Image(systemName: "gearshape")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(palette.onSurfaceVariant)
                    .frame(width: 34, height: 34)
                    .background(palette.surfaceContainerHigh, in: Circle())
                    .frame(width: Metrics.touchTarget, height: Metrics.touchTarget)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Réglages")
        }
        .padding(.top, Spacing.s)
    }

    // MARK: - « TON SUIVI » + the family legend, always above the grid

    private var trackingSection: some View {
        VStack(alignment: .leading, spacing: 6) {
            SectionTitleView("TON SUIVI", action: "Modules") {
                router.push(.settingsModules)
            }
            FamilyLegend()
        }
    }

    // MARK: - Actions

    private func reload() async {
        guard let session = app.session else { return }
        await vm.load(session: session, features: features, labReminders: labReminders.items)
    }

    private func markNextTaken() {
        guard let session = app.session, let next = vm.nextDose else { return }
        Task {
            if await vm.markTakenNow(next, session: session) {
                await app.refreshNotifications()
                await reload()
                flash("Enregistré ✓")
            }
        }
    }

    private func pickMood(_ face: Int) {
        guard let session = app.session else { return }
        Task {
            if await vm.setMoodFace(face, session: session) {
                // §8: the mood tap is the one confirmation that offers a way
                // out — the face was recorded blind, so the user needs to be
                // able to see what it wrote.
                flash("Enregistré ✓", actionLabel: "Voir le journal")
            }
        }
    }

    private func open(_ module: LauncherModule) {
        vm.markModuleOpened(module)
        router.push(Self.route(for: module))
    }

    private func flash(_ message: String, actionLabel: String? = nil) {
        let pill = Toast(message: message, actionLabel: actionLabel)
        withAnimation(.easeOut(duration: 0.2)) { toast = pill }
        Task {
            try? await Task.sleep(nanoseconds: 2_500_000_000)
            withAnimation(.easeIn(duration: 0.2)) { if toast == pill { toast = nil } }
        }
    }

    // MARK: - Static helpers

    static func route(for module: LauncherModule) -> Route {
        switch module {
        case .meds:         return .medicationList
        case .appointments: return .appointments
        case .journal:      return .journal
        case .bleeding:     return .bleeding
        case .labs:         return .labs
        case .weight:       return .weight
        case .photos:       return .photos
        case .voice:        return .voice
        case .notes:        return .notes
        }
    }

    /// « Dimanche 26 juillet » — the date in full, never a greeting: the salute
    /// used to take the place of a useful block (§6.1.1).
    static func dateTitle(_ date: Date = Date()) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "fr_FR")
        f.dateFormat = "EEEE d MMMM"
        let text = f.string(from: date)
        return text.prefix(1).uppercased() + text.dropFirst()
    }

    static func timeLabel(_ ms: Int64) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "fr_FR")
        f.dateFormat = "HH:mm"
        return f.string(from: Date(timeIntervalSince1970: Double(ms) / 1000))
    }

    /// « à 20:00 » / « demain » / « dans 3 j » — the delay is the actionable
    /// half of the reminder line: the label alone cannot tell tomorrow from six
    /// weeks away (§6.1.2).
    ///
    /// The gap is counted in **calendar days**, never in elapsed milliseconds:
    /// at 23:00, a reminder set for 09:00 tomorrow is ten hours away, so the
    /// division said « à 09:00 » — which reads as "any minute now" — while
    /// something two calendar days out came back as « demain ». What the reader
    /// needs is which day it lands on.
    static func relativeDelay(
        _ atMs: Int64,
        nowMs: Int64 = Time.nowMs(),
        calendar: Calendar = .current
    ) -> String {
        let today = calendar.startOfDay(for: Date(timeIntervalSince1970: Double(nowMs) / 1000))
        let dueDay = calendar.startOfDay(for: Date(timeIntervalSince1970: Double(atMs) / 1000))
        let days = calendar.dateComponents([.day], from: today, to: dueDay).day ?? 0
        if days <= 0 { return "à \(timeLabel(atMs))" }
        if days == 1 { return "demain" }
        return "dans \(days) j"
    }
}

// MARK: - Carte de dose (§6.1.2)

private struct DoseCard: View {
    @Environment(\.palette) private var palette
    @ObservedObject var vm: HomeViewModel
    let onMarkTaken: () -> Void
    let onSnooze: () -> Void
    let onAddMedication: () -> Void

    var body: some View {
        // No treatment at all is not a "0/0" state — it dead-ends the user. It
        // gets the unified empty card with a priming button instead (§5.3).
        if !vm.hasMedications {
            EmptyStateView(
                "Ajoute un traitement pour commencer à suivre tes prises.",
                systemImage: "pills",
                actionLabel: "Ajouter un traitement",
                action: onAddMedication)
        } else {
            EggCard(variant: .primary, spacing: 0) {
                headline
                actions
                reminderLine
            }
        }
    }

    private var headline: some View {
        HStack(alignment: .center, spacing: Spacing.l) {
            ProgressRingView(
                progress: vm.plannedCount == 0
                    ? 0 : Double(vm.takenCount) / Double(vm.plannedCount),
                diameter: 64,
                lineWidth: 6,
                tint: palette.primary,
                track: palette.surfaceContainerHighest
            ) {
                Text("\(vm.takenCount)/\(vm.plannedCount)")
                    .font(.system(size: 17, weight: .bold))
                    .foregroundStyle(palette.onPrimaryContainer)
            }
            .accessibilityLabel("\(vm.takenCount) prises sur \(vm.plannedCount)")

            VStack(alignment: .leading, spacing: 3) {
                if vm.plannedCount == 0 {
                    Text("Aucun planning").font(EggFont.titleL)
                } else if let next = vm.nextDose {
                    MicroLabel(
                        "PROCHAINE PRISE · \(HomeView.timeLabel(next.scheduledAtMs))",
                        color: palette.onPrimaryContainer.opacity(0.72))
                    Text(Self.doseTitle(next.medication))
                        .font(EggFont.titleL)
                        .lineLimit(2)
                } else {
                    Text("Tout est pris ✓").font(EggFont.titleL)
                }
            }
            Spacer(minLength: 0)
        }
    }

    @ViewBuilder
    private var actions: some View {
        if vm.nextDose != nil || vm.plannedCount == 0 {
            HStack(spacing: Spacing.s) {
                Button(action: vm.nextDose != nil ? onMarkTaken : onAddMedication) {
                    HStack(spacing: Spacing.s) {
                        Image(systemName: vm.nextDose != nil ? "checkmark" : "calendar.badge.plus")
                            .font(.system(size: 15, weight: .semibold))
                        Text(vm.nextDose != nil ? "Marquer comme pris" : "Programmer une prise")
                            .font(EggFont.label)
                            .lineLimit(1)
                    }
                    .foregroundStyle(palette.onPrimary)
                    .frame(maxWidth: .infinity)
                    .frame(height: Metrics.touchTarget)
                    .background(palette.primary, in: Capsule())
                    .contentShape(Capsule())
                }
                .buttonStyle(.plain)

                if vm.nextDose != nil {
                    // The system tonal pair (on-surface-variant on
                    // surface-container-highest) is a foreign pair inside a
                    // primary-container card, so the button derives its own
                    // from the card and the contrast rule holds in all 14
                    // palettes.
                    Button(action: onSnooze) {
                        Image(systemName: "clock")
                            .font(.system(size: 17, weight: .semibold))
                            .foregroundStyle(palette.onPrimaryContainer)
                            .frame(width: Metrics.touchTarget, height: Metrics.touchTarget)
                            .background(palette.onPrimaryContainer.opacity(0.12), in: Circle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Décaler")
                }
            }
            .padding(.top, 14)
        }
    }

    @ViewBuilder
    private var reminderLine: some View {
        if let reminder = vm.reminder {
            VStack(spacing: 0) {
                CardRule()
                    .padding(.top, 12)
                HStack(spacing: 9) {
                    Image(systemName: "bell")
                        .font(.system(size: 14))
                        .opacity(0.7)
                    // « Puis bilan hormonal, dans 3 j » (§6.1.2).
                    Text("Puis \(reminder.text), \(HomeView.relativeDelay(reminder.dueAtMs))")
                        .font(EggFont.bodyS)
                        .opacity(0.85)
                        .lineLimit(1)
                    Spacer(minLength: Spacing.s)
                    if reminder.othersCount > 0 {
                        MicroLabel(
                            reminder.othersCount == 1
                                ? "+1 rappel" : "+\(reminder.othersCount) rappels",
                            color: palette.onPrimaryContainer.opacity(0.7))
                    }
                }
                .padding(.top, 10)
            }
        }
    }

    private static func doseTitle(_ med: Medication) -> String {
        guard let dose = med.defaultDose else { return med.name }
        let amount = dose.rounded() == dose ? String(Int(dose)) : String(dose)
        if let unit = med.defaultDoseUnit, !unit.isEmpty {
            return "\(med.name) · \(amount) \(unit)"
        }
        return "\(med.name) · \(amount)"
    }
}

// MARK: - Carte de ressenti (§6.1.3)

private struct MoodCard: View {
    @Environment(\.palette) private var palette
    let selectedFace: Int?
    let onPick: (Int) -> Void
    let onOpenDetail: () -> Void

    private static let emoji = ["😞", "🙁", "😐", "🙂", "😄"]
    private static let labels = ["Difficile", "Moyen", "Ça va", "Bien", "Très bien"]

    var body: some View {
        // The card sits one tier above the page so the button inside it can
        // still step above the card.
        EggCard(
            variant: .low,
            paddingH: 14,
            paddingV: 12,
            spacing: 0,
            container: palette.surfaceContainerHigh
        ) {
            HStack {
                MicroLabel("COMMENT TU TE SENS ?")
                Spacer(minLength: Spacing.s)
                // Empty until something is recorded today — that *is* the
                // empty state.
                Text(selectedFace.map { Self.labels[$0 - 1] } ?? "")
                    .font(EggFont.micro)
                    .tracking(0.5)
                    .foregroundStyle(palette.primary)
                    .lineLimit(1)
            }
            faces
            detailButton
        }
    }

    private var faces: some View {
        HStack(spacing: 5) {
            ForEach(0..<5, id: \.self) { index in
                let face = index + 1
                let selected = selectedFace == face
                Button { onPick(face) } label: {
                    Text(Self.emoji[index])
                        .font(.system(size: 25))
                        .opacity(selected ? 1 : 0.75)
                        .frame(maxWidth: .infinity)
                        .frame(height: Metrics.touchTarget)
                        .background(
                            RoundedRectangle(cornerRadius: 14, style: .continuous)
                                .fill(selected
                                      ? palette.primaryContainer
                                      : palette.surfaceContainerHighest))
                        .overlay {
                            if selected {
                                RoundedRectangle(cornerRadius: 14, style: .continuous)
                                    .strokeBorder(palette.primary, lineWidth: 2)
                            }
                        }
                        .scaleEffect(selected ? 1.04 : 1)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Self.labels[index])
                .accessibilityAddTraits(selected ? [.isSelected] : [])
            }
        }
        .animation(.easeOut(duration: 0.15), value: selectedFace)
        .padding(.top, 10)
    }

    private var detailButton: some View {
        Button(action: onOpenDetail) {
            HStack(spacing: 7) {
                Image(systemName: "square.and.pencil").font(.system(size: 14, weight: .semibold))
                Text("Noter en détail — curseurs, note, effets")
                    .font(.system(size: 12.5, weight: .semibold))
                    .lineLimit(1)
                    .minimumScaleFactor(0.85)
            }
            .foregroundStyle(palette.primary)
            .frame(maxWidth: .infinity)
            .frame(height: 40)
            .background(palette.surfaceContainerHighest, in: Capsule())
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .padding(.top, 9)
    }
}

// MARK: - Légende des familles (§6.1.5) — always above the grid

private struct FamilyLegend: View {
    @Environment(\.palette) private var palette

    var body: some View {
        // Wraps rather than clipping: four labels no longer fit one line at a
        // large Dynamic Type size, and the last one would vanish silently.
        ChipFlowLayout(spacing: 14, lineSpacing: 6) {
            dot(palette.primaryContainer, "Traitement")
            dot(palette.tertiaryContainer, "Ressenti")
            dot(palette.evolutionContainer, "Évolution")
            dot(palette.otherContainer, "Autres")
        }
        .accessibilityElement(children: .combine)
    }

    private func dot(_ color: Color, _ label: String) -> some View {
        HStack(spacing: 5) {
            Circle().fill(color).frame(width: 8, height: 8)
            Text(label)
                .font(EggFont.micro)
                .tracking(0.5)
                .foregroundStyle(palette.onSurfaceVariant.opacity(0.85))
        }
    }
}

// MARK: - Lanceur (§6.1.6, §7)

/// A tile's family, which is also its colour pair.
private enum LauncherFamily {
    case treatment, feeling, evolution, other
}

private struct LauncherSpec: Identifiable {
    /// `nil` for a tile that announces a module rather than opening one.
    let module: LauncherModule?
    let id: String
    let label: String
    let systemImage: String
    let family: LauncherFamily
    /// Announced but not shipped: drawn greyed, and takes no input at all.
    var comingSoon: Bool { module == nil }

    init(module: LauncherModule, label: String, systemImage: String, family: LauncherFamily) {
        self.module = module
        self.id = module.rawValue
        self.label = label
        self.systemImage = systemImage
        self.family = family
    }

    init(teaser id: String, label: String, systemImage: String, family: LauncherFamily) {
        self.module = nil
        self.id = id
        self.label = label
        self.systemImage = systemImage
        self.family = family
    }
}

private struct LauncherGrid: View {
    @ObservedObject var features: FeaturesStore
    let badges: [LauncherModule: HomeViewModel.Badge]
    let onOpen: (LauncherModule) -> Void

    private let columns = Array(
        repeating: GridItem(.flexible(), spacing: 8, alignment: .top), count: 4)

    var body: some View {
        LazyVGrid(columns: columns, alignment: .leading, spacing: 14) {
            ForEach(tiles) { tile in
                LauncherCell(spec: tile, badge: tile.module.flatMap { badges[$0] }) {
                    if let module = tile.module { onOpen(module) }
                }
            }
        }
    }

    /// A tile whose module is off is simply not drawn; the grid reflows. The
    /// labels are one line each — that is why the second tile says « RDV ».
    private var tiles: [LauncherSpec] {
        var all: [LauncherSpec] = []
        if features.medications {
            all.append(LauncherSpec(module: .meds, label: "Médics",
                                    systemImage: "pills.fill", family: .treatment))
        }
        if features.appointments {
            all.append(LauncherSpec(module: .appointments, label: "RDV",
                                    systemImage: "calendar", family: .treatment))
        }
        if features.journal {
            all.append(LauncherSpec(module: .journal, label: "Journal",
                                    systemImage: "face.smiling", family: .feeling))
        }
        if features.bleeding {
            all.append(LauncherSpec(module: .bleeding, label: "Menstruations",
                                    systemImage: "drop.fill", family: .feeling))
        }
        if features.hormones {
            all.append(LauncherSpec(module: .labs, label: "Analyses",
                                    systemImage: "cross.vial.fill", family: .evolution))
        }
        if features.weight {
            all.append(LauncherSpec(module: .weight, label: "Poids",
                                    systemImage: "scalemass.fill", family: .evolution))
        }
        if features.photos {
            all.append(LauncherSpec(module: .photos, label: "Photos",
                                    systemImage: "camera.fill", family: .evolution))
        }
        if features.voice {
            all.append(LauncherSpec(module: .voice, label: "Voix",
                                    systemImage: "waveform", family: .evolution))
        }
        if features.notes {
            all.append(LauncherSpec(module: .notes, label: "Notes",
                                    systemImage: "doc.text", family: .other))
        }
        // Always last, and never gated on a flag: it has nothing to toggle yet.
        // Delete this line the moment the module itself lands.
        all.append(LauncherSpec(teaser: "dreams", label: "Rêves",
                                systemImage: "moon.stars.fill", family: .other))
        return all
    }
}

private struct LauncherCell: View {
    @Environment(\.palette) private var palette
    let spec: LauncherSpec
    let badge: HomeViewModel.Badge?
    let onTap: () -> Void

    var body: some View {
        if spec.comingSoon {
            // Dimmed *and* inert — no Button at all. Greying a tile while
            // leaving it tappable promises a screen that does not exist, and a
            // highlight that leads nowhere reads as a bug, not an announcement.
            content
                .opacity(0.45)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(accessibleName)
        } else {
            Button(action: onTap) { content }
                .buttonStyle(.plain)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(accessibleName)
                .accessibilityAddTraits(.isButton)
        }
    }

    private var content: some View {
        VStack(spacing: 7) {
            tile
            Text(spec.label)
                .font(EggFont.micro)
                .tracking(0.5)
                .foregroundStyle(palette.onSurfaceVariant)
                .lineLimit(1)
        }
        .frame(maxWidth: .infinity)
        .contentShape(Rectangle())
    }

    /// iOS drops the knock-out ring native badges never use: the counter stays
    /// 19 pt, the news dot is 12 pt.
    private var tile: some View {
        ZStack(alignment: .topTrailing) {
            RoundedRectangle(cornerRadius: Radius.launcherTile, style: .continuous)
                .fill(container)
                .frame(width: 58, height: 58)
                .overlay {
                    Image(systemName: spec.systemImage)
                        .font(.system(size: 24, weight: .semibold))
                        .foregroundStyle(iconColor)
                }
            switch badge {
            case .counter(let count):
                Text(count > 9 ? "9+" : "\(count)")
                    .font(.system(size: 11, weight: .bold))
                    .foregroundStyle(palette.onError)
                    .frame(minWidth: 19)
                    .frame(height: 19)
                    .padding(.horizontal, 3)
                    .background(palette.error, in: Capsule())
                    .offset(x: 5, y: -5)
            case .news:
                Circle()
                    .fill(palette.error)
                    .frame(width: 12, height: 12)
                    .offset(x: 3, y: -3)
            case nil:
                EmptyView()
            }
        }
        .frame(width: 58, height: 58)
    }

    private var accessibleName: String {
        if spec.comingSoon { return "\(spec.label), bientôt disponible" }
        switch badge {
        case .counter(let count):
            return "\(spec.label), \(count) en attente"
        case .news:
            return "\(spec.label), nouveauté"
        case nil:
            return spec.label
        }
    }

    private var container: Color {
        switch spec.family {
        case .treatment: return palette.primaryContainer
        case .feeling:   return palette.tertiaryContainer
        case .evolution: return palette.evolutionContainer
        case .other:     return palette.otherContainer
        }
    }

    private var iconColor: Color {
        switch spec.family {
        case .treatment: return palette.onPrimaryContainer
        case .feeling:   return palette.onTertiaryContainer
        case .evolution: return palette.onEvolutionContainer
        case .other:     return palette.onOtherContainer
        }
    }
}
