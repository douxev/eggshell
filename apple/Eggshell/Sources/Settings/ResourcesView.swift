import SwiftUI
import TransitionCore

// Reached from the footer of Réglages (§6.15). Sites, helplines and associations,
// split into « Général » and « Associations ».
//
// No webview, no analytics, no link rewriting: a tap hands the URL to the system
// browser and eggshell never touches any of these hosts itself.

struct ResourcesView: View {
    @Environment(\.palette) private var palette
    @Environment(\.openURL) private var openURL

    private enum ResourceCategory: CaseIterable {
        case general
        case association

        var label: String {
            switch self {
            case .general: return "Général"
            case .association: return "Associations"
            }
        }
    }

    private struct Resource: Identifiable {
        let id = UUID()
        let title: String
        let description: String
        let urlString: String
        let category: ResourceCategory

        var url: URL? { URL(string: urlString) }
        var isDiscord: Bool {
            let lower = urlString.lowercased()
            return lower.contains("discord") || lower.contains("cutt.ly/discord")
        }
    }

    @State private var selected: ResourceCategory = .general

    private var categoryIndex: Int {
        selected == .general ? 0 : 1
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Metrics.blockGap) {
                SegmentedSelector(
                    options: ResourceCategory.allCases.map(\.label),
                    selection: Binding(
                        get: { categoryIndex },
                        set: { selected = $0 == 0 ? .general : .association }),
                    accessibilityLabel: "Catégorie")

                Text("Touche une ligne pour ouvrir le lien dans ton navigateur. eggshell ne fait aucune requête vers ces sites.")
                    .font(EggFont.bodyS)
                    .foregroundStyle(palette.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)

                let shown = resources.filter { $0.category == selected }
                ListGroup {
                    ForEach(Array(shown.enumerated()), id: \.element.id) { index, resource in
                        ListRowView(
                            title: resource.title,
                            subtitle: resource.description,
                            systemImage: resource.isDiscord
                                ? "bubble.left.and.bubble.right"
                                : "globe",
                            showsChevron: true,
                            showsSeparator: index != shown.count - 1,
                            action: { if let url = resource.url { openURL(url) } })
                    }
                }
                Color.clear.frame(height: Spacing.s)
            }
            .padding(.horizontal, Metrics.screenMargin)
            .padding(.top, Spacing.s)
        }
        .background(palette.surface.ignoresSafeArea())
        .navigationTitle("Ressources")
        .navigationBarTitleDisplayMode(.inline)
    }

    // ── Données ─────────────────────────────────────────────────────────────
    private let resources: [Resource] = [
        // Général
        Resource(
            title: "Fransgenre",
            description: "Plateforme communautaire francophone : témoignages, base de connaissances, listes de soignant·es trans-friendly, forum d'entraide.",
            urlString: "https://fransgenre.fr",
            category: .general),
        Resource(
            title: "AdminisTrans",
            description: "Guide collaboratif des démarches administratives trans en France : changement de prénom, de mention de sexe, papiers, mutuelle.",
            urlString: "https://administrans.fr/",
            category: .general),
        Resource(
            title: "Wikitrans",
            description: "Encyclopédie collaborative francophone sur les questions trans : protocoles, démarches administratives, ressources locales.",
            urlString: "https://wikitrans.co",
            category: .general),
        Resource(
            title: "Transat (annuaire)",
            description: "Annuaire de médecins, endocrinologues et soignant·es trans-friendly partout en France.",
            urlString: "https://transat-asso.fr/",
            category: .general),
        Resource(
            title: "SOS homophobie · ligne d'écoute",
            description: "Soutien anonyme et gratuit en cas d'agression, discrimination ou détresse. 01 48 06 42 41.",
            urlString: "https://www.sos-homophobie.org",
            category: .general),
        Resource(
            title: "S* Écoute",
            description: "Ligne d'écoute 24/7 si tu traverses une période très dure : 01 45 39 40 00.",
            urlString: "https://www.suicide-ecoute.fr",
            category: .general),

        // Associations (sites)
        Resource(
            title: "OUTrans",
            description: "Association d'auto-support trans à Paris. Permanences, groupes de parole.",
            urlString: "https://outrans.org",
            category: .association),
        Resource(
            title: "Chrysalide Lyon",
            description: "Auto-support pour les personnes trans, intersexes, en questionnement et leurs proches.",
            urlString: "https://chrysalide-asso.fr/",
            category: .association),
        Resource(
            title: "Acceptess-T",
            description: "Association communautaire dédiée à la défense des droits des personnes trans, en particulier migrantes et travailleuses du sexe.",
            urlString: "https://www.acceptess-t.com",
            category: .association),

        // Associations (Discord)
        Resource(
            title: "Divergenre",
            description: "Discord — Asso Amiens / Somme.",
            urlString: "https://discord.com/invite/3Jf5CqbN38",
            category: .association),
        Resource(
            title: "Transat (Marseille)",
            description: "Discord — Asso Marseille / Bouches-du-Rhône.",
            urlString: "https://cutt.ly/discord-transat",
            category: .association),
        Resource(
            title: "Trans Comté",
            description: "Discord — Collectif Franche-Comté.",
            urlString: "https://cutt.ly/discord-transcomte",
            category: .association),
        Resource(
            title: "Association Trans Toulousaine et Occitane",
            description: "Discord — Asso Toulouse.",
            urlString: "https://discord.gg/CU2tv7meqY",
            category: .association),
        Resource(
            title: "Collectif Intersexe et Activiste",
            description: "Discord — Asso intersexe.",
            urlString: "https://discord.com/invite/h2zGVmUM3D",
            category: .association),
        Resource(
            title: "Trans-mission Var",
            description: "Discord — Association Toulon / Var.",
            urlString: "https://discord.gg/hrTZZDv8QG",
            category: .association),
        Resource(
            title: "ISKIS",
            description: "Discord — Asso LGBTI+ Rennes / Ille-et-Vilaine.",
            urlString: "https://discord.gg/gAaCYc2kw8",
            category: .association),
        Resource(
            title: "MAG jeunes LGBT+",
            description: "Discord — Asso jeunes LGBTI+ Paris.",
            urlString: "https://discord.com/invite/GF85Q9v3Yt",
            category: .association),
        Resource(
            title: "Trans inter nb Rouen",
            description: "Discord — Serveur Rouen / Normandie.",
            urlString: "https://discord.gg/DJTyTF4yEA",
            category: .association),
        Resource(
            title: "Meuf Trans Toulouse",
            description: "Discord — Serveur transfem Toulouse.",
            urlString: "https://discord.gg/mk9X3UcKWv",
            category: .association),
        Resource(
            title: "Révolte TransDraconique",
            description: "Discord — Serveur Nancy / Metz / Grand Est.",
            urlString: "https://discord.gg/Ggh2cVcjtn",
            category: .association),
        Resource(
            title: "ASTR / Le Châlet Transfem",
            description: "Discord — Serveur transfem Suisse.",
            urlString: "https://discord.gg/HUXnBuKCrq",
            category: .association),
    ]
}
