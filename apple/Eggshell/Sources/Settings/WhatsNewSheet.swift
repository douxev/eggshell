import SwiftUI

// « Quoi de neuf » — a sheet, presented from Réglages and once automatically on
// the first launch of a new version.
//
// It is a sheet and not a screen on purpose: reading it is never a step you have
// to complete, and « Compris » closes it for good.

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
                    Color.clear.frame(height: Spacing.s)
                }
                .padding(.horizontal, Metrics.screenMargin)
                .padding(.top, Spacing.s)
            }
            .background(palette.surface.ignoresSafeArea())
            .navigationTitle("Quoi de neuf")
            .navigationBarTitleDisplayMode(.inline)
            .eggActionBar {
                ActionBarButton("Compris") { dismiss() }
            }
        }
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
    }

    private func releaseSection(_ release: WhatsNewCatalog.Release) -> some View {
        VStack(alignment: .leading, spacing: Spacing.s) {
            SectionTitleView(release.title, prominent: true)
            ListGroup {
                ForEach(Array(release.highlights.enumerated()), id: \.offset) { index, highlight in
                    HStack(alignment: .top, spacing: Spacing.m) {
                        Image(systemName: "sparkles")
                            .font(.system(size: 17))
                            .foregroundStyle(palette.primary)
                            .frame(width: 24)
                        Text(highlight)
                            .font(.eggBody)
                            .foregroundStyle(palette.onSurface)
                            .fixedSize(horizontal: false, vertical: true)
                        Spacer(minLength: 0)
                    }
                    .padding(.horizontal, Metrics.screenMargin)
                    .padding(.vertical, Spacing.m)

                    if index != release.highlights.count - 1 {
                        Rectangle()
                            .fill(palette.outlineVariant)
                            .frame(height: 1)
                            .padding(.leading, Metrics.screenMargin + 24 + Spacing.m)
                    }
                }
            }
        }
    }
}
