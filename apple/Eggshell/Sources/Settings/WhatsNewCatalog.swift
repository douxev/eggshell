import SwiftUI

// ===========================================================================
// Catalogue des nouveautés. Mirrors android WhatsNewCatalog: a single LATEST
// release bundle, presented by SettingsHubView when the WhatsNewStore reports
// the user hasn't seen `latestVersion` yet. To ship a new release: bump
// `latestVersion` and prepend a Release entry.
// ===========================================================================

enum WhatsNewCatalog {
    /// Highest version code. Compared against WhatsNewStore.lastSeen.
    static let latestVersion: Int = 9

    struct Release: Identifiable {
        var id: Int { version }
        let version: Int
        let title: String
        let highlights: [String]
    }

    /// Releases, newest first.
    static let releases: [Release] = [
        Release(
            version: 9,
            title: "Quoi de neuf",
            highlights: [
                "Menstruations — nouvel onglet pour noter tes règles, le spotting et tes symptômes.",
                "Jauges personnalisables — renomme, réordonne et ajoute tes propres curseurs dans le journal et les menstruations.",
                "Corrélations — ton humeur en regard de tes prises, changements de traitement et jours de règles.",
                "Édition de traitement — modifie un traitement ; les changements de dose ou de voie sont gardés pour les corrélations.",
                "Couleur des traitements — choisis une couleur pour repérer chaque traitement d'un coup d'œil.",
                "Leurre persistant — l'appli de notes leurre garde tes notes entre les sessions, isolée de ton vrai coffre.",
                "Rappels sur mesure — mode d'affichage (générique, nom ou alias) et priorité, rappel par rappel.",
            ]
        ),
    ]
}
