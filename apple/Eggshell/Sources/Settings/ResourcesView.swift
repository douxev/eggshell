import SwiftUI
import TransitionCore

// ===========================================================================
// PUSHED screen — static list of external resources. No vault, no ViewModel.
// ===========================================================================

struct ResourcesView: View {
    @Environment(\.palette) private var palette

    private struct Resource: Identifiable {
        let id = UUID()
        let name: String
        let url: URL
    }

    private let resources: [Resource] = [
        Resource(name: "OUTrans", url: URL(string: "https://outrans.org")!),
        Resource(name: "Wiki Trans", url: URL(string: "https://wikitrans.co")!),
        Resource(name: "Fédération Trans & Intersexes", url: URL(string: "https://www.federation-trans-et-intersexes.org")!),
        Resource(name: "ANCIC (santé)", url: URL(string: "https://www.ancic.asso.fr")!),
    ]

    var body: some View {
        ScrollView {
            VStack(spacing: Spacing.m) {
                SectionCard {
                    Text("Associations & ressources").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
                    ForEach(resources) { resource in
                        Link(destination: resource.url) {
                            HStack(spacing: Spacing.m) {
                                Image(systemName: "globe").foregroundStyle(palette.primary)
                                Text(resource.name).font(.eggCallout).foregroundStyle(palette.onSurface)
                                Spacer()
                                Image(systemName: "arrow.up.right").font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.5))
                            }
                            .padding(.vertical, Spacing.xs)
                        }
                    }
                }
            }
            .padding(Spacing.m)
        }
        .navigationTitle("Ressources")
    }
}
