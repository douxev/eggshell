import SwiftUI
import TransitionCore

// Réglages (§6.15) — seven screens folded into three doors.
//
// The doors are Modules, Sécurité, Apparence & langue. Their subtitles are live
// summaries, not copy: « 6 activés sur 8 » has to be true, or the door lies about
// what is behind it.
//
// Two things moved and are visible here: **Rappels came up a level** — the
// notification-content card is a section of this screen, with one row down to the
// full CRUD hub — and **the PDF export left**, for Rendez-vous. Nothing was
// dropped on the way (D5).

struct SettingsHubView: View {
    @EnvironmentObject private var features: FeaturesStore
    @EnvironmentObject private var themeStore: ThemeStore
    @EnvironmentObject private var securityFlags: SecurityFlags
    @EnvironmentObject private var hormoneUnits: HormoneUnitStore
    @EnvironmentObject private var app: AppState
    @EnvironmentObject private var whatsNew: WhatsNewStore
    @EnvironmentObject private var router: Router
    @Environment(\.palette) private var palette
    @Environment(\.openURL) private var openURL

    @State private var showWhatsNew = false
    @State private var contentMode: NotifContentMode = NotifPrefs.contentMode

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Metrics.blockGap) {
                doors
                remindersSection
                supportCard
                whatsNewRow
                footer
            }
            .padding(.horizontal, Metrics.screenMargin)
            .padding(.top, Spacing.s)
            .padding(.bottom, Spacing.xl)
        }
        .background(palette.surface.ignoresSafeArea())
        .navigationTitle("Réglages")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $showWhatsNew) { WhatsNewSheet() }
        .onAppear {
            if whatsNew.shouldShow(latestVersion: WhatsNewCatalog.latestVersion) {
                showWhatsNew = true
                whatsNew.markSeen(WhatsNewCatalog.latestVersion)
            }
        }
    }

    // MARK: - The three doors

    private var doors: some View {
        ListGroup {
            ListRowView(
                title: "Modules",
                subtitle: "\(features.enabledCount) activés sur 8 · ce que l'app suit pour toi",
                systemImage: "square.grid.2x2",
                iconContainer: palette.primaryContainer,
                iconTint: palette.onPrimaryContainer,
                showsChevron: true,
                showsSeparator: true,
                action: { router.push(.settingsModules) })
            ListRowView(
                title: "Sécurité",
                subtitle: securitySummary,
                systemImage: "lock.shield",
                iconContainer: palette.primaryContainer,
                iconTint: palette.onPrimaryContainer,
                showsChevron: true,
                showsSeparator: true,
                action: { router.push(.settingsSecurity) })
            ListRowView(
                title: "Apparence & langue",
                subtitle: appearanceSummary,
                systemImage: "paintpalette",
                iconContainer: palette.primaryContainer,
                iconTint: palette.onPrimaryContainer,
                showsChevron: true,
                action: { router.push(.settingsAppearance) })
        }
    }

    /// Verrouillage · leurre · sauvegarde. Read from the vault's own prefs, not
    /// from a cached string: a door that misreports its lock mode is worse than
    /// no subtitle at all.
    private var securitySummary: String {
        let prefs = VaultPrefs()
        let mode = prefs.modeRaw.flatMap(SecurityMode.init(rawValue:))
        return [
            mode?.title.lowercased() ?? "verrouillage",
            prefs.hasDecoyPin ? "leurre actif" : "pas de leurre",
            securityFlags.blockScreenshots ? "captures bloquées" : "sauvegarde",
        ]
        .joined(separator: " · ")
    }

    private var appearanceSummary: String {
        let theme = Themes.find(themeStore.themeId).label
        let unit = hormoneUnits.effectiveUnit(for: "estradiol") ?? "unité d'origine"
        return "\(theme) · français · \(unit)"
    }

    // MARK: - Rappels — a section of this screen, not a door (§2.4)

    private var remindersSection: some View {
        VStack(alignment: .leading, spacing: Spacing.s) {
            SectionTitleView("Rappels", prominent: true)
            EggCard(variant: .low, paddingH: Spacing.l, paddingV: 18, spacing: 0) {
                Text("Contenu des notifications")
                    .font(EggFont.titleS)
                    .foregroundStyle(palette.onSurface)
                Text("Ce qui s'affiche sur l'écran verrouillé")
                    .font(EggFont.bodyS)
                    .foregroundStyle(palette.onSurfaceVariant)
                    .padding(.top, 2)

                ChipFlowLayout(spacing: 7, lineSpacing: 7) {
                    ForEach(NotifContentMode.allCases) { mode in
                        PillView(Self.chipLabel(mode), selected: contentMode == mode) {
                            contentMode = mode
                            NotifPrefs.contentMode = mode
                            Task { await app.refreshNotifications() }
                        }
                    }
                }
                .padding(.top, 14)

                // The preview is the point of the card: a mode name means nothing,
                // the sentence someone standing next to you would read means
                // everything.
                HStack(alignment: .center, spacing: 9) {
                    Image(systemName: "bell")
                        .font(.system(size: 17))
                        .foregroundStyle(palette.onSurfaceVariant)
                    Text(Self.preview(contentMode))
                        .font(EggFont.bodyS)
                        .foregroundStyle(palette.onSurfaceVariant)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .padding(.horizontal, Spacing.m)
                .padding(.vertical, 10)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(
                    palette.surfaceContainer,
                    in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                .padding(.top, 14)
                .accessibilityLabel("Aperçu de l'écran verrouillé. \(Self.preview(contentMode))")
            }

            ListGroup {
                ListRowView(
                    title: "Configurer les rappels",
                    subtitle: "Ajoute, modifie ou supprime tes rappels",
                    systemImage: "bell.badge",
                    showsChevron: true,
                    action: { router.push(.reminders) })
            }
        }
    }

    private static func chipLabel(_ mode: NotifContentMode) -> String {
        switch mode {
        case .generic: return "Générique"
        case .name:    return "Nom"
        case .alias:   return "Alias"
        }
    }

    /// Written for each mode. The name and the alias previews did not exist in the
    /// handoff and are written here to the same rule the vault follows: the real
    /// name only ever reaches a lock screen because someone asked for it.
    private static func preview(_ mode: NotifContentMode) -> String {
        switch mode {
        case .generic: return "« C'est l'heure » — aucun nom affiché."
        case .name:    return "« Estradiol — c'est l'heure » — le vrai nom s'affiche."
        case .alias:   return "« Vitamine D — c'est l'heure » — ton alias, jamais le vrai nom."
        }
    }

    // MARK: - Soutien, nouveautés, pied de page

    private var supportCard: some View {
        EggCard(
            variant: .tertiary,
            action: { openURL(URL(string: "https://paypal.me/metraf")!) }
        ) {
            HStack(alignment: .center, spacing: 14) {
                Image(systemName: "heart.fill")
                    .font(.system(size: 26))
                VStack(alignment: .leading, spacing: 2) {
                    Text("Soutenir eggshell")
                        .font(EggFont.titleS)
                    Text("Gratuit, sans pub, sans compte.")
                        .font(EggFont.bodyS)
                        .opacity(0.8)
                }
                Spacer(minLength: Spacing.s)
                Image(systemName: "chevron.right")
                    .font(.system(size: 17, weight: .semibold))
                    .opacity(0.7)
            }
            .frame(minHeight: Metrics.touchTarget)
        }
    }

    private var whatsNewRow: some View {
        ListGroup {
            ListRowView(
                title: "Quoi de neuf",
                subtitle: "Les nouveautés de cette version",
                systemImage: "sparkles",
                showsChevron: true,
                action: { showWhatsNew = true })
        }
    }

    private var footer: some View {
        HStack(spacing: Metrics.blockGap) {
            Button { router.push(.resources) } label: {
                Text("Ressources")
                    .font(EggFont.micro)
                    .tracking(0.5)
                    .foregroundStyle(palette.primary)
                    .frame(minHeight: Metrics.touchTarget)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            Text("·")
                .font(EggFont.micro)
                .foregroundStyle(palette.outline)
            Text("Version \(AppVersion.name)")
                .font(EggFont.micro)
                .tracking(0.5)
                .foregroundStyle(palette.onSurfaceVariant)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 14)
        .padding(.bottom, 4)
    }
}

extension FeaturesStore {
    /// « N activés sur 8 » — the door's subtitle has to be counted, not typed.
    /// Lives here rather than in `Core/Stores.swift` so the store stays a store.
    var enabledCount: Int {
        [medications, journal, hormones, weight, photos, voice, bleeding, appointments]
            .filter { $0 }
            .count
    }
}
