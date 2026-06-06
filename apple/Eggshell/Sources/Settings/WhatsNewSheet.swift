import SwiftUI

// ===========================================================================
// Feuille « Quoi de neuf » — presented via .sheet from SettingsHubView (and
// auto-presented on first launch of a new version). Shows the catalog releases
// newest-first with a "Compris" button to dismiss.
// ===========================================================================

struct WhatsNewSheet: View {
    @Environment(\.palette) private var palette
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: Spacing.xl) {
                    ForEach(WhatsNewCatalog.releases) { release in
                        releaseSection(release)
                    }
                }
                .padding(Spacing.l)
            }
            .background(palette.surface.ignoresSafeArea())
            .navigationTitle("Quoi de neuf")
            .safeAreaInset(edge: .bottom) {
                Button {
                    dismiss()
                } label: {
                    Text("Compris")
                        .font(.eggCallout)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, Spacing.s)
                }
                .glassProminentButton()
                .padding(Spacing.l)
                .background(palette.surface.opacity(0.95))
            }
        }
    }

    private func releaseSection(_ release: WhatsNewCatalog.Release) -> some View {
        VStack(alignment: .leading, spacing: Spacing.m) {
            Text(release.title)
                .font(.eggTitle)
                .foregroundStyle(palette.onSurface)

            SectionCard {
                ForEach(Array(release.highlights.enumerated()), id: \.offset) { index, highlight in
                    if index > 0 {
                        Divider().overlay(palette.outlineVariant)
                    }
                    HStack(alignment: .top, spacing: Spacing.m) {
                        Image(systemName: "sparkles")
                            .font(.eggCallout)
                            .foregroundStyle(palette.primary)
                            .frame(width: 24)
                        Text(highlight)
                            .font(.eggBody)
                            .foregroundStyle(palette.onSurface.opacity(0.85))
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .padding(.vertical, Spacing.xs)
                }
            }
        }
    }
}
