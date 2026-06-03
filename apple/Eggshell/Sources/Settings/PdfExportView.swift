import SwiftUI
import TransitionCore

// ===========================================================================
// PUSHED STUB — "Export PDF". No data loading; placeholder until the PDF
// export feature ships. Pushed via Route.pdfExport, so no TabScaffold:
// a plain ScrollView/VStack with .navigationTitle.
// ===========================================================================

struct PdfExportView: View {
    @Environment(\.palette) private var palette

    var body: some View {
        ScrollView {
            VStack(spacing: Spacing.l) {
                EmptyStateCard(
                    text: "L'export PDF de ton suivi arrive bientôt.",
                    systemImage: "doc.richtext")

                Text("Tu pourras générer un récapitulatif partageable de tes prises, mesures et journal.")
                    .font(.eggCaption)
                    .foregroundStyle(palette.onSurface.opacity(0.6))
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: .infinity)
                    .padding(.horizontal, Spacing.l)
            }
            .padding(Spacing.l)
        }
        .navigationTitle("Export PDF")
    }
}
