import SwiftUI
import TransitionCore

// ===========================================================================
// PUSHED screen — theme picker. A grid over Themes.all; each tile renders a
// mini preview from the theme's own swatch palette (primary / secondary /
// tertiary dots over a surface card) plus the label. Tapping sets
// themeStore.themeId; the active theme shows a checkmark. Mirrors android
// ThemePickerScreen.
// ===========================================================================

struct ThemePickerView: View {
    @EnvironmentObject private var themeStore: ThemeStore
    @Environment(\.palette) private var palette
    @Environment(\.colorScheme) private var colorScheme

    private let columns = [
        GridItem(.flexible(), spacing: Spacing.m),
        GridItem(.flexible(), spacing: Spacing.m),
    ]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Spacing.l) {
                Text("Choisis l'apparence de l'application. Les thèmes sombres s'appliquent quel que soit le réglage système.")
                    .font(.eggCaption)
                    .foregroundStyle(palette.onSurface.opacity(0.6))

                LazyVGrid(columns: columns, spacing: Spacing.m) {
                    ForEach(Themes.all) { theme in
                        tile(theme)
                    }
                }
            }
            .padding(Spacing.l)
        }
        .navigationTitle("Thème")
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
                        .font(.eggLabel)
                        .foregroundStyle(selected ? palette.primary : palette.onSurface)
                    if selected {
                        Image(systemName: "checkmark.circle.fill")
                            .font(.eggCaption)
                            .foregroundStyle(palette.primary)
                    }
                    Spacer()
                }
            }
        }
        .buttonStyle(.plain)
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
                    .fill(swatch.onSurface.opacity(0.6))
                    .frame(height: 4)
                RoundedRectangle(cornerRadius: 2, style: .continuous)
                    .fill(swatch.onSurface.opacity(0.35))
                    .frame(width: 60, height: 4)
            }
            .padding(Spacing.m)
            .frame(maxWidth: .infinity, alignment: .leading)
            .frame(height: 110)
            .background(
                swatch.surface,
                in: RoundedRectangle(cornerRadius: Corner.large, style: .continuous)
            )
            .overlay(
                RoundedRectangle(cornerRadius: Corner.large, style: .continuous)
                    .strokeBorder(selected ? swatch.primary : palette.outlineVariant,
                                  lineWidth: selected ? 2 : 1)
            )

            if selected {
                Image(systemName: "checkmark.circle.fill")
                    .font(.title3)
                    .foregroundStyle(swatch.primary)
                    .padding(Spacing.s)
            }
        }
    }

    private func dot(_ color: Color, size: CGFloat) -> some View {
        Circle().fill(color).frame(width: size, height: size)
    }
}
