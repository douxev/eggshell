import SwiftUI

// ===========================================================================
// Catalogue des nouveautés. Mirrors android WhatsNewCatalog: a single LATEST
// release bundle, presented by SettingsHubView when the WhatsNewStore reports
// the user hasn't seen `latestVersion` yet. To ship a new release: bump
// `latestVersion` and prepend a Release entry.
// ===========================================================================

enum WhatsNewCatalog {
    /// Highest version code. Compared against WhatsNewStore.lastSeen.
    static let latestVersion: Int = 1

    struct Release: Identifiable {
        var id: Int { version }
        let version: Int
        let title: String
        let highlights: [String]
    }

    /// Releases, newest first.
    static let releases: [Release] = [
        Release(
            version: 1,
            title: "Quoi de neuf",
            highlights: [
                "13 thèmes — Lavande, Catppuccin, Gruvbox, Dracula, Nord, Tokyo Night… dans Plus → Thème.",
                "Hauteur de voix — F0 calculé localement pour chaque enregistrement, courbe d'évolution dans le suivi voix.",
                "Rappels labo, photo & voix — catégories séparées avec notification prioritaire optionnelle.",
                "Lightbox + zoom — pince pour zoomer, partage et sauvegarde dans la galerie depuis la timeline photo.",
                "Blocage des captures d'écran — bascule dans Avancé pour cacher l'app et bloquer les captures.",
                "Menstruations — suivi optionnel des règles et du spotting.",
                "Ressources — sites et associations utiles dans Plus → Ressources.",
            ]
        ),
    ]
}
