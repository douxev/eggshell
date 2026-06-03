import SwiftUI
import TransitionCore

// PUSHED screen: no TabScaffold, no NavigationStack. Lets the user pick the app
// theme. For now only one theme ("Lavande") is available; the grid scaffolding is
// already in place so future themes drop in as extra swatches.

struct ThemePickerView: View {
    @EnvironmentObject private var themeStore: ThemeStore
    @Environment(\.palette) private var palette

    private struct ThemeOption: Identifiable {
        let id: String
        let label: String
    }

    private let themes: [ThemeOption] = [
        ThemeOption(id: "lavender", label: "Lavande")
    ]

    private let columns = [GridItem(.adaptive(minimum: 120), spacing: Spacing.m)]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Spacing.l) {
                LazyVGrid(columns: columns, spacing: Spacing.m) {
                    ForEach(themes) { theme in
                        swatch(theme)
                    }
                }
                Text("D'autres thèmes arriveront bientôt.")
                    .font(.eggCaption)
                    .foregroundStyle(palette.onSurface.opacity(0.6))
            }
            .padding(Spacing.l)
        }
        .navigationTitle("Thème")
    }

    private func swatch(_ theme: ThemeOption) -> some View {
        let selected = themeStore.themeId == theme.id
        return Button {
            themeStore.themeId = theme.id
        } label: {
            VStack(spacing: Spacing.s) {
                Circle()
                    .fill(palette.primary)
                    .frame(width: 32, height: 32)
                Text(theme.label)
                    .font(.eggLabel)
                    .foregroundStyle(palette.onSurface)
            }
            .frame(maxWidth: .infinity, minHeight: 96)
            .background(
                palette.surfaceContainer,
                in: RoundedRectangle(cornerRadius: Corner.large, style: .continuous)
            )
            .overlay(
                RoundedRectangle(cornerRadius: Corner.large, style: .continuous)
                    .strokeBorder(selected ? palette.primary : Color.clear, lineWidth: 2)
            )
        }
        .buttonStyle(.plain)
    }
}
