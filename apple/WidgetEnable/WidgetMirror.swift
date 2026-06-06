import Foundation

// =============================================================================
// WidgetMirror — app-side writer for the "prochaine dose" home-screen widget.
// =============================================================================
//
// PARITÉ ANDROID. Sur Android, le widget (EggshellWidgetProvider) lit un miroir
// EN CLAIR, NON CHIFFRÉ, hors du coffre (ReminderPrefs + LabReminderPrefs) afin
// de pouvoir s'afficher SANS que le coffre soit déverrouillé. Côté iOS, une
// extension WidgetKit s'exécute dans un PROCESSUS SÉPARÉ qui n'a jamais la clé
// du coffre : elle ne peut donc PAS lire `vault.db`. La seule façon de partager
// des données app → widget est le conteneur d'un App Group commun
// (`group.com.douxev.eggshell`).
//
// Ce miroir contient donc, par conception, des métadonnées EN CLAIR. Règles de
// confidentialité (identiques à Android) :
//   1. JAMAIS de nom de médicament en clair, SAUF si l'utilisateur a
//      explicitement choisi le mode `.name` (opt-in, comme le mode de contenu
//      des notifications). Le défaut est `.generic` → titre neutre.
//   2. MODE LEURRE (decoy) : on EFFACE tout le miroir via `clear()`. Le widget
//      affiche alors son état vide ("Aucun rappel"), exactement comme Android
//      qui désactive le receiver du widget (WidgetVisibility) quand un PIN
//      leurre est configuré. Aucune donnée réelle ne doit fuiter vers l'écran
//      d'accueil sous le PIN leurre.
//   3. On n'écrit QUE l'heure d'échéance + un libellé générique (ou opt-in). On
//      n'écrit jamais la dose, la voie, le site d'injection, ni les notes.
//
// Pur Foundation. Aucune dépendance au cœur Rust ni à TransitionCore : on passe
// uniquement des valeurs déjà extraites par l'appelant (qui, lui, a la session
// déverrouillée). Tout échoue GRACIEUSEMENT (no-op) si le conteneur App Group
// n'est pas provisionné (entitlement absent → `available == false`).
//
// → Copier ce fichier dans la CIBLE APP (apple/Eggshell/Sources/Platform/) une
//   fois l'App Group ajouté aux entitlements. Tant que le dossier WidgetEnable/
//   n'est pas dans project.yml, ce fichier n'est PAS compilé et ne peut pas
//   casser le build.

// MARK: - Modèle partagé app ↔ widget

/// Mode d'affichage du libellé, calqué sur `NotificationContentMode`.
public enum WidgetMirrorMode: String, Codable {
    case generic   // libellé neutre, aucune fuite (DÉFAUT)
    case name      // nom du médicament en clair (opt-in explicite)
}

/// Une ligne "prochaine dose" sérialisée dans le conteneur App Group.
/// Volontairement minimal : un titre déjà résolu + l'heure d'échéance (ms epoch).
public struct WidgetMirrorEntry: Codable, Hashable {
    /// Titre affiché. En mode `.generic` c'est un libellé neutre ("Prochaine
    /// prise") ; en mode `.name` c'est le nom du médicament fourni par l'appelant.
    public var title: String
    /// Échéance en millisecondes depuis l'epoch (UTC). Le widget calcule un
    /// libellé relatif ("dans 2 h", "demain", ...) à partir de cette valeur.
    public var dueAtMs: Int64
    /// Nom de symbole SF (sf symbol) optionnel pour l'icône de ligne.
    public var systemImage: String?

    public init(title: String, dueAtMs: Int64, systemImage: String? = nil) {
        self.title = title
        self.dueAtMs = dueAtMs
        self.systemImage = systemImage
    }
}

/// Charge utile complète écrite dans le conteneur. Versionnée pour pouvoir
/// faire évoluer le format sans planter un widget plus ancien.
public struct WidgetMirrorPayload: Codable {
    public var schema: Int
    public var mode: WidgetMirrorMode
    public var updatedAtMs: Int64
    public var entries: [WidgetMirrorEntry]

    public init(mode: WidgetMirrorMode, entries: [WidgetMirrorEntry], updatedAtMs: Int64) {
        self.schema = WidgetMirror.schemaVersion
        self.mode = mode
        self.updatedAtMs = updatedAtMs
        self.entries = entries
    }
}

// MARK: - Helper d'écriture côté app

public enum WidgetMirror {
    /// IDENTIFIANT D'APP GROUP — DOIT correspondre à l'entitlement de l'app ET
    /// de l'extension widget (cf. WidgetEnable/README.md).
    public static let appGroupId = "group.com.douxev.eggshell"

    /// Nom du fichier JSON dans le conteneur partagé.
    public static let fileName = "next_dose_mirror.json"

    /// Version du schéma du payload (incrémenter en cas de changement cassant).
    public static let schemaVersion = 1

    /// Nombre maximal de lignes exposées (parité Android : 3).
    public static let maxEntries = 3

    /// URL du fichier miroir dans le conteneur App Group, ou `nil` si le
    /// conteneur n'est pas disponible (entitlement manquant / simulateur sans
    /// App Group provisionné). Tout le helper devient alors un no-op silencieux.
    public static var fileURL: URL? {
        guard let dir = FileManager.default
            .containerURL(forSecurityApplicationGroupIdentifier: appGroupId) else {
            return nil
        }
        return dir.appendingPathComponent(fileName, isDirectory: false)
    }

    /// `true` quand le conteneur App Group est joignable (entitlement présent).
    public static var available: Bool { fileURL != nil }

    // MARK: Écriture

    /// Écrit le miroir "prochaine dose".
    ///
    /// - Parameters:
    ///   - entries: lignes déjà résolues par l'appelant (qui a la session
    ///     déverrouillée). L'appelant DOIT n'inclure un nom en clair que si
    ///     `mode == .name` ; sinon fournir un titre générique. Tronqué à
    ///     `maxEntries`.
    ///   - mode: `.generic` (défaut, aucune fuite) ou `.name` (opt-in).
    ///   - now: horloge injectable (tests).
    ///
    /// No-op gracieux si le conteneur est indisponible. N'écrit JAMAIS de nom en
    /// clair lorsque `mode == .generic` : en garde-fou, on neutralise alors les
    /// titres au cas où l'appelant en aurait laissé passer un.
    @discardableResult
    public static func write(
        entries: [WidgetMirrorEntry],
        mode: WidgetMirrorMode = .generic,
        now: Date = Date()
    ) -> Bool {
        guard let url = fileURL else { return false }

        // Tri par échéance croissante, troncature, garde-fou anti-fuite.
        let safe = entries
            .sorted { $0.dueAtMs < $1.dueAtMs }
            .prefix(maxEntries)
            .map { entry -> WidgetMirrorEntry in
                if mode == .generic {
                    // Défense en profondeur : aucun nom en clair en mode générique.
                    return WidgetMirrorEntry(
                        title: genericTitle,
                        dueAtMs: entry.dueAtMs,
                        systemImage: entry.systemImage)
                }
                return entry
            }

        let payload = WidgetMirrorPayload(
            mode: mode,
            entries: Array(safe),
            updatedAtMs: Int64(now.timeIntervalSince1970 * 1000))

        do {
            let data = try JSONEncoder().encode(payload)
            // Écriture atomique ; protection de fichier "complete until first
            // unlock" pour que le contenu reste lisible par le widget après
            // déverrouillage de l'appareil sans rester en clair avant.
            try data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            return true
        } catch {
            return false
        }
    }

    /// Efface entièrement le miroir.
    ///
    /// À APPELER :
    ///   - en MODE LEURRE (decoy) — parité Android WidgetVisibility(false) ;
    ///   - au verrouillage / effacement complet du coffre si l'on ne veut plus
    ///     rien exposer ;
    ///   - quand la fonctionnalité de rappels est désactivée.
    ///
    /// Réécrit un payload vide (plutôt que de supprimer le fichier) pour que le
    /// widget bascule proprement sur son état "Aucun rappel" au prochain
    /// rafraîchissement, sans erreur de lecture. No-op gracieux si indisponible.
    @discardableResult
    public static func clear(now: Date = Date()) -> Bool {
        guard let url = fileURL else { return false }
        let empty = WidgetMirrorPayload(
            mode: .generic,
            entries: [],
            updatedAtMs: Int64(now.timeIntervalSince1970 * 1000))
        do {
            let data = try JSONEncoder().encode(empty)
            try data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            return true
        } catch {
            // Si l'écriture échoue, tenter une suppression dure du fichier.
            try? FileManager.default.removeItem(at: url)
            return false
        }
    }

    // MARK: Lecture (utilisée côté widget ; exposée pour debug app)

    /// Lit le miroir. Retourne un payload vide si rien n'est écrit ou si le
    /// conteneur est indisponible.
    public static func read() -> WidgetMirrorPayload {
        guard let url = fileURL,
              let data = try? Data(contentsOf: url),
              let payload = try? JSONDecoder().decode(WidgetMirrorPayload.self, from: data) else {
            return WidgetMirrorPayload(mode: .generic, entries: [], updatedAtMs: 0)
        }
        return payload
    }

    /// Libellé générique neutre par défaut (aucune fuite).
    public static let genericTitle = "Prochaine prise"
}
