import SwiftUI
import TransitionCore

// ===========================================================================
// PUSHED screen — the "Plus" hub. Shortcut rows to the dedicated settings
// screens (Fonctionnalités, Thème, Rappels, Ressources, Avancé), plus PDF
// export and hormone units. A "Quoi de neuf" row opens WhatsNewSheet; the
// sheet also auto-presents on first launch of a new version (whatsNew gate).
// Mirrors android SettingsHubScreen.
// ===========================================================================

struct SettingsHubView: View {
    @EnvironmentObject private var whatsNew: WhatsNewStore
    @Environment(\.palette) private var palette

    @State private var showWhatsNew = false

    private struct HubRow: Identifiable {
        let id = UUID()
        let label: String
        let subtitle: String
        let icon: String
        let route: Route
    }

    private let rows: [HubRow] = [
        HubRow(label: "Fonctionnalités", subtitle: "Choisis les sections à afficher",
               icon: "slider.horizontal.3", route: .features),
        HubRow(label: "Thème", subtitle: "Couleurs de l'application",
               icon: "paintpalette", route: .themePicker),
        HubRow(label: "Export PDF", subtitle: "Rapport à partager avec un·e soignant·e",
               icon: "doc.richtext", route: .pdfExport),
        HubRow(label: "Unités hormonales", subtitle: "Unité d'affichage par hormone",
               icon: "ruler", route: .hormoneUnits),
        HubRow(label: "Rappels", subtitle: "Notifications de prises, labo, photo, voix",
               icon: "bell", route: .reminders),
        HubRow(label: "Ressources", subtitle: "Sites et associations utiles",
               icon: "globe", route: .resources),
        HubRow(label: "Avancé", subtitle: "Sécurité, sauvegarde, masquage",
               icon: "shield", route: .advancedSettings),
    ]

    var body: some View {
        ScrollView {
            VStack(spacing: Spacing.l) {
                SectionCard {
                    ForEach(Array(rows.enumerated()), id: \.element.id) { index, row in
                        if index > 0 {
                            Divider().overlay(palette.outlineVariant)
                        }
                        NavigationLink(value: row.route) {
                            hubRowLabel(icon: row.icon, label: row.label, subtitle: row.subtitle)
                        }
                        .buttonStyle(.plain)
                    }
                }

                whatsNewCard
                donationCard
            }
            .padding(Spacing.l)
        }
        .navigationTitle("Plus")
        .sheet(isPresented: $showWhatsNew) {
            WhatsNewSheet()
        }
        .onAppear {
            if whatsNew.shouldShow(latestVersion: WhatsNewCatalog.latestVersion) {
                showWhatsNew = true
                whatsNew.markSeen(WhatsNewCatalog.latestVersion)
            }
        }
    }

    private func hubRowLabel(icon: String, label: String, subtitle: String) -> some View {
        HStack(spacing: Spacing.m) {
            Image(systemName: icon)
                .font(.title3)
                .foregroundStyle(palette.primary)
                .frame(width: 32, height: 32)
            VStack(alignment: .leading, spacing: 2) {
                Text(label)
                    .font(.eggCallout)
                    .foregroundStyle(palette.onSurface)
                Text(subtitle)
                    .font(.eggCaption)
                    .foregroundStyle(palette.onSurface.opacity(0.6))
            }
            Spacer()
            Image(systemName: "chevron.right")
                .font(.eggCaption)
                .foregroundStyle(palette.onSurface.opacity(0.4))
        }
        .padding(.vertical, Spacing.s)
    }

    private var whatsNewCard: some View {
        Button {
            showWhatsNew = true
        } label: {
            SectionCard {
                HStack(spacing: Spacing.m) {
                    Image(systemName: "sparkles")
                        .font(.title3)
                        .foregroundStyle(palette.primary)
                        .frame(width: 32, height: 32)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Quoi de neuf")
                            .font(.eggCallout)
                            .foregroundStyle(palette.onSurface)
                        Text("Les nouveautés de cette version")
                            .font(.eggCaption)
                            .foregroundStyle(palette.onSurface.opacity(0.6))
                    }
                    Spacer()
                    Image(systemName: "chevron.right")
                        .font(.eggCaption)
                        .foregroundStyle(palette.onSurface.opacity(0.4))
                }
                .padding(.vertical, Spacing.s)
            }
        }
        .buttonStyle(.plain)
    }

    private var donationCard: some View {
        Link(destination: URL(string: "https://paypal.me/metraf")!) {
            SectionCard {
                HStack(spacing: Spacing.m) {
                    Image(systemName: "heart")
                        .font(.title2)
                        .foregroundStyle(palette.error)
                        .frame(width: 32, height: 32)
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
