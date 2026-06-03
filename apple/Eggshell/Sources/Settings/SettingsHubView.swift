import SwiftUI
import TransitionCore

struct SettingsHubView: View {
    @Environment(\.palette) private var palette

    private struct HubRow: Identifiable {
        let id = UUID()
        let label: String
        let icon: String
        let route: Route
    }

    private let rows: [HubRow] = [
        HubRow(label: "Fonctions", icon: "gearshape", route: .features),
        HubRow(label: "Thème", icon: "paintpalette", route: .themePicker),
        HubRow(label: "Export PDF", icon: "doc.richtext", route: .pdfExport),
        HubRow(label: "Unités hormonales", icon: "ruler", route: .hormoneUnits),
        HubRow(label: "Rappels", icon: "bell", route: .reminders),
        HubRow(label: "Ressources", icon: "globe", route: .resources),
        HubRow(label: "Avancé", icon: "shield", route: .advancedSettings)
    ]

    var body: some View {
        ScrollView {
            VStack(spacing: Spacing.l) {
                SectionCard {
                    ForEach(rows) { row in
                        NavigationLink(value: row.route) {
                            HStack(spacing: Spacing.m) {
                                Image(systemName: row.icon)
                                    .font(.title3)
                                    .foregroundStyle(palette.primary)
                                    .frame(width: 28)
                                Text(row.label)
                                    .font(.eggCallout)
                                    .foregroundStyle(palette.onSurface)
                                Spacer()
                                Image(systemName: "chevron.right")
                                    .font(.eggCaption)
                                    .foregroundStyle(palette.onSurface.opacity(0.4))
                            }
                            .padding(.vertical, Spacing.s)
                        }
                        .buttonStyle(.plain)
                    }
                }
                donationCard
            }
            .padding(Spacing.l)
        }
        .navigationTitle("Plus")
    }

    private var donationCard: some View {
        Link(destination: URL(string: "https://paypal.me/metraf")!) {
            SectionCard {
                HStack(spacing: Spacing.m) {
                    Image(systemName: "heart")
                        .font(.title2)
                        .foregroundStyle(palette.error)
                        .frame(width: 28)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Soutenir le développement")
                            .font(.eggHeadline)
                            .foregroundStyle(palette.onSurface)
                        Text("M'aider à construire des outils pour la santé trans")
                            .font(.eggCaption)
                            .foregroundStyle(palette.onSurface.opacity(0.6))
                    }
                    Spacer()
                }
            }
        }
        .buttonStyle(.plain)
    }
}
