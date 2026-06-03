import SwiftUI
import TransitionCore

// PUSHED stub screen — OCR import is not implemented yet. Offers a fallback to
// manual hormone entry via the Router.

struct ImportLabResultView: View {
    @EnvironmentObject private var router: Router
    @Environment(\.palette) private var palette

    var body: some View {
        ScrollView {
            VStack(spacing: Spacing.l) {
                EmptyStateCard(
                    text: "La reconnaissance de texte (OCR) arrive bientôt.",
                    systemImage: "doc.text.viewfinder")

                Button("Saisie manuelle") {
                    router.push(.addHormone)
                }
                .glassProminentButton()
                .tint(palette.primary)
            }
            .padding(Spacing.l)
        }
        .navigationTitle("Importer")
    }
}
