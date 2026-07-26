import SwiftUI
import TransitionCore

// Porte 3 — **Apparence & langue** (§2.4). Themes, the display units of the lab
// results, and the language.
//
// Two things live here that used to be their own screens: the hormone display
// units, which are an appearance choice and not a medical one, and the language.
// Nothing was dropped on the way (D5).

struct ThemePickerView: View {
    @EnvironmentObject private var themeStore: ThemeStore
    @EnvironmentObject private var hormoneUnits: HormoneUnitStore
    @EnvironmentObject private var router: Router
    @Environment(\.palette) private var palette
    @Environment(\.colorScheme) private var colorScheme

    private let columns = [
        GridItem(.flexible(), spacing: Spacing.m),
        GridItem(.flexible(), spacing: Spacing.m),
    ]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Metrics.blockGap) {
                SectionTitleView("Thème", prominent: true)
                Text("Choisis l'apparence de l'application. Les thèmes sombres s'appliquent quel que soit le réglage du système.")
                    .font(EggFont.bodyS)
                    .foregroundStyle(palette.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)

                LazyVGrid(columns: columns, spacing: Spacing.m) {
                    ForEach(Themes.all) { theme in
                        tile(theme)
                    }
                }

                SectionTitleView("Analyses", prominent: true)
                    .padding(.top, Spacing.s)
                ListGroup {
                    ListRowView(
                        title: "Unités d'affichage des analyses",
                        subtitle: unitsSubtitle,
                        systemImage: "ruler",
                        showsChevron: true,
                        action: { router.push(.hormoneUnits) })
                }

                SectionTitleView("Langue", prominent: true)
                    .padding(.top, Spacing.s)
                ListGroup {
                    ListRowView(
                        title: "Langue de l'app",
                        subtitle: "L'app suit la langue du système. Seul le français est disponible pour l'instant.",
                        systemImage: "globe",
                        trailingText: "Français")
                }
                Color.clear.frame(height: Spacing.s)
            }
            .padding(.horizontal, Metrics.screenMargin)
            .padding(.top, Spacing.s)
        }
        .background(palette.surface.ignoresSafeArea())
        .navigationTitle("Apparence & langue")
        .navigationBarTitleDisplayMode(.inline)
    }

    private var unitsSubtitle: String {
        guard let unit = hormoneUnits.effectiveUnit(for: "estradiol") else {
            return "Telles que saisies"
        }
        return "Actuellement \(unit)"
    }

    private func tile(_ theme: Theme) -> some View {
        let selected = themeStore.themeId == theme.id
        let swatch = theme.swatch(dark: colorScheme == .dark)
        return Button {
            themeStore.themeId = theme.id
        } label: {
            VStack(spacing: Spacing.s) {
                preview(swatch, selected: selected)
                HStack(spacing: Spacing.xs) {
                    Text(theme.label)
                        .font(EggFont.label)
                        .foregroundStyle(selected ? palette.primary : palette.onSurface)
                    if selected {
                        Image(systemName: "checkmark.circle.fill")
                            .font(EggFont.micro)
                            .foregroundStyle(palette.primary)
                    }
                    Spacer()
                }
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel(theme.label)
        .accessibilityValue(selected ? "Thème actif" : "")
        .accessibilityAddTraits(selected ? [.isSelected] : [])
    }

    private func preview(_ swatch: Palette, selected: Bool) -> some View {
        ZStack(alignment: .topTrailing) {
            VStack(alignment: .leading, spacing: Spacing.s) {
                HStack(spacing: Spacing.s) {
                    dot(swatch.primary, size: 28)
                    dot(swatch.secondary, size: 22)
                    dot(swatch.tertiary, size: 16)
                }
                Spacer()
                RoundedRectangle(cornerRadius: 2, style: .continuous)
                    .fill(swatch.onSurfaceVariant)
                    .frame(height: 4)
                RoundedRectangle(cornerRadius: 2, style: .continuous)
                    .fill(swatch.outlineVariant)
                    .frame(width: 60, height: 4)
            }
            .padding(Spacing.m)
            .frame(maxWidth: .infinity, alignment: .leading)
            .frame(height: 110)
            .background(
                swatch.surface,
                in: RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: Radius.card, style: .continuous)
                    .strokeBorder(
                        selected ? swatch.primary : palette.outlineVariant,
                        lineWidth: selected ? 2 : 1))

            if selected {
                Image(systemName: "checkmark.circle.fill")
                    .font(.system(size: 20))
                    .foregroundStyle(swatch.primary)
                    .padding(Spacing.s)
            }
        }
    }

    private func dot(_ color: Color, size: CGFloat) -> some View {
        Circle().fill(color).frame(width: size, height: size)
    }
}
