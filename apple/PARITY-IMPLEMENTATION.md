# Mise à parité iOS ↔ Android — implémentation

Travail réalisé sur la base de la cartographie [PARITY-ANDROID-IOS.md](PARITY-ANDROID-IOS.md).
App iOS passée de ~5 200 à ~11 800 lignes Swift (63 fichiers). Approche : fondation
écrite à la main (contrat de symboles) → fan-out d'agents par écran → passe de
vérification de cohérence. **Aucune compilation locale possible** (pas de toolchain
iOS) : la validation finale exige un build CI (GitHub Actions macOS). Aucune
modification du cœur Rust n'a été nécessaire (toute l'API uniffi existait déjà).

## Correctifs critiques
- **Corruption de données croisée corrigée** (`Core/Catalog.swift`) : les types de
  médicaments / voies / sites d'injection iOS utilisent désormais EXACTEMENT les
  mêmes identifiants que l'Android (estrogen/progesterone/…, suppository, etc.).
- **Cause racine levée** (`Core/VaultService.swift`) : enveloppe maintenant
  update_medication, treatment changes, list_dose_events_between, delete_schedule,
  update_journal_entry, metrics (definitions/values), bleeding (CRUD).
- **Fuites de sécurité branchées** : `Platform/PrivacyShield.swift` applique
  réellement le masquage écran (app-switcher + enregistrement) au toggle
  « bloquer les captures » ; strip EXIF à l'import photo (ré-encodage JPEG).

## Domaines implémentés (parité)
- Saignements/cycle (onglet + CRUD) · métriques personnalisables (éditeur + sliders
  dynamiques journal/saignements) · corrélation (graphe humeur/doses/changements/
  saignements, fenêtres 30/90/180 j).
- Today enrichi : anneau de progression, doses du jour cochables, sparkline humeur,
  rappels agrégés (médocs + labo/photo/voix), quick-log corrigé (Photo/Voix).
- Médicaments : édition + journal des changements de traitement, planning avec date
  de départ, suppression de planning, sites d'injection localisés.
- Hormones : conversion par défaut conventionnelle + « telle que saisie », édition
  de mesure, **import OCR de labo** (Vision + parser porté).
- Voix : **détection de pitch (YIN)** + tendance, partage, effacement sécurisé.
- Photos : galerie lazy, comparaison avant/après, strip EXIF, partage, pellicule.
- Rappels : notifications multi-occurrences + actions Pris/Passer (file de doses
  verrouillée drainée au déverrouillage) + modes de contenu + rappels labo/photo/voix.
- Réglages : 10 thèmes effectifs (themeId désormais relu), picker en grille,
  ressources à onglets, « Quoi de neuf », toggle Saignements.
- Sécurité : restauration de sauvegarde, changement de mode, vérification PIN
  constant-time, re-skin du leurre.
- Onboarding : scénarios en langage clair + assistant post-création.
- Export PDF : rapport A4 multi-sections via UIGraphicsPDFRenderer.

## Reste à faire (validation / dépendances externes)
1. **Build CI** : valider la compilation sur Xcode (toolchain iOS) — des erreurs de
   type subtiles peuvent subsister (closures Sendable, inférence), non détectables
   sans le SDK.
2. **Widget WidgetKit** (`apple/WidgetEnable/`, NON compilé) : nécessite de créer un
   App Group dans le portail Apple Developer + régénérer les profils `match` + ajouter
   la cible widget dans `project.yml`. Code prêt-à-activer + instructions dans
   `apple/WidgetEnable/README.md`. Non intégré pour ne pas casser la signature.
3. **Déguisement d'icône** : code prêt (`Platform/AppIconManager.swift`) mais inactif
   tant que les PNG d'icônes alternatives + l'entrée `CFBundleAlternateIcons` ne sont
   pas ajoutés au bundle (voir README WidgetEnable). L'UI le gère gracieusement.
4. **Paranoïaque + restauration/changement de mode** : volontairement refusé
   (`VaultError.paranoidRequiresRekey`) car cela demanderait un re-chiffrement complet
   de la base.
