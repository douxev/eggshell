import SwiftUI

// ===========================================================================
// Catalogue des nouveautés. Mirrors android WhatsNewCatalog: a single LATEST
// release bundle, presented by SettingsHubView when the WhatsNewStore reports
// the user hasn't seen `latestVersion` yet. To ship a new release: bump
// `latestVersion` and prepend a Release entry.
// ===========================================================================

enum WhatsNewCatalog {
    /// Highest version code. Compared against WhatsNewStore.lastSeen.
    static let latestVersion: Int = 13

    struct Release: Identifiable {
        var id: Int { version }
        let version: Int
        let title: String
        let highlights: [String]
    }

    /// Releases, newest first.
    static let releases: [Release] = [
        Release(
            version: 14,
            title: "Quoi de neuf",
            highlights: [
                "Un seul accueil — plus de barre d'onglets : tes huit modules tiennent sur l'accueil, et le retour y ramène toujours.",
                "Cocher une dose sans naviguer — la prochaine prise et ton humeur du jour se notent depuis l'accueil, en un geste.",
                "Régularité lisible — un graphique montre l'écart de chaque prise à l'heure prévue, sans jamais inventer de chiffre.",
                "Réglages en trois portes — Modules, Sécurité, Apparence & langue. Les rappels remontent d'un cran.",
                "Le rapport médecin part avec toi — il se prépare depuis Rendez-vous, avec les périodes et les sections que tu choisis.",
                "Analyses importées plus finement — la lecture d'un PDF retient aussi le laboratoire, pour que ton médecin sache d'où vient la valeur.",
            ]
        ),
        Release(
            version: 13,
            title: "Quoi de neuf",
            highlights: [
                "Règles — note un jour passé, ou « cette semaine = règles » en une seule action.",
                "Prises par période — déclare une plage de prises (ex. gel quotidien sur des mois) et modifie une prise déjà notée, voie comprise.",
                "Rappels sur mesure — modifie tes rappels, donne-leur ton propre texte, ajoute un rappel journal ; tout est regroupé au même endroit.",
                "Calendrier plus parlant — règles en ligne continue et points de traitement sur le calendrier du journal, avec légende.",
                "Courbes datées — les courbes d'hormones affichent les dates et un rond à chaque jour de prise.",
                "Bilan sanguin enrichi — l'import PDF reconnaît la tension artérielle, l'hémoglobine et l'hématocrite.",
            ]
        ),
        Release(
            version: 10,
            title: "Quoi de neuf",
            highlights: [
                "Rendez-vous — nouvel onglet pour noter tes RDV, les professionnel·les et ce qu'il y a à faire.",
                "Résumé — compare ta semaine ou ton mois au précédent : humeur, prises notées vs prévues, symptômes.",
                "Historique des prises — supprime une prise, et note l'heure exacte d'une prise oubliée (antidatage).",
                "Supprimer un traitement — archive-le, ou supprime-le définitivement avec tout son historique.",
                "Bilans labo protégés — importe tes résultats même quand le PDF du labo est verrouillé par un mot de passe.",
                "Export PDF médecin — le récapitulatif pour ton médecin ne plante plus et s'ouvre dans le partage.",
                "Humeur confirmée — un petit mot confirme l'enregistrement de ton ressenti, avec un accès au journal.",
            ]
        ),
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
