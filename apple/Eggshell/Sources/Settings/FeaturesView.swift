import SwiftUI
import TransitionCore

struct FeaturesView: View {
    @EnvironmentObject private var features: FeaturesStore
    @Environment(\.palette) private var palette

    var body: some View {
        ScrollView {
            VStack(spacing: Spacing.l) {
                Text("Active uniquement les fonctions dont tu as besoin. Les autres restent masquées dans l'app.")
                    .font(.eggCaption)
                    .foregroundStyle(palette.onSurface.opacity(0.6))
                    .frame(maxWidth: .infinity, alignment: .leading)

                SectionCard {
                    featureToggle(
                        title: "Médicaments",
                        subtitle: "Suivi des prises et des plannings",
                        isOn: $features.medications)
                    Divider().overlay(palette.outlineVariant)
                    featureToggle(
                        title: "Journal",
                        subtitle: "Humeur, ressenti et effets",
                        isOn: $features.journal)
                    Divider().overlay(palette.outlineVariant)
                    featureToggle(
                        title: "Hormones",
                        subtitle: "Résultats de laboratoire",
                        isOn: $features.hormones)
                    Divider().overlay(palette.outlineVariant)
                    featureToggle(
                        title: "Poids",
                        subtitle: "Suivi de ton poids",
                        isOn: $features.weight)
                    Divider().overlay(palette.outlineVariant)
                    featureToggle(
                        title: "Photos",
                        subtitle: "Suivi visuel de ta transition",
                        isOn: $features.photos)
                    Divider().overlay(palette.outlineVariant)
                    featureToggle(
                        title: "Voix",
                        subtitle: "Enregistrements et tonalité",
                        isOn: $features.voice)
                }
            }
            .padding(Spacing.l)
        }
        .navigationTitle("Fonctions")
    }

    private func featureToggle(title: String, subtitle: String, isOn: Binding<Bool>) -> some View {
        Toggle(isOn: isOn) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.eggCallout).foregroundStyle(palette.onSurface)
                Text(subtitle).font(.eggCaption).foregroundStyle(palette.onSurface.opacity(0.6))
            }
        }
        .tint(palette.primary)
    }
}
