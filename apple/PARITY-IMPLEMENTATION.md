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

## Lot 2026-07-23 — backlog P0–P2 + courbes datées (schéma 13 → 14)

Parité refaite dans la foulée du lot Android (branche `feat/android-bugfixes-batch`).
Contrat de bindings : `TransitionCoreInfo.bindingsContract` 0.0.7 → **0.0.8**
(`dose_schedules.label`, `logDoses`, `updateDose`, `getDose`, `updateSchedule`,
`addBleedingEntries` — voir CORE_API.md). Côté Swift :

- **Règles** : date rétroactive éditable (création + édition) et mode
  « Plusieurs jours » (une entrée par jour à 12 h locale, batch
  `addBleedingEntries`, sliders appliqués à chaque entrée) — `AddBleedingEntryView`.
- **Prises** : mode « Période » (une prise par jour à l'heure choisie, batch
  `logDoses`) et mode édition (`LogDoseView(medId:editDoseId:)`, seed via
  `getDose`, sauvegarde via `updateDose` en conservant status/scheduledAtMs/
  scheduleId). Accès édition depuis l'historique (`MedicationDetailView`,
  `Route.editDose`).
- **Rappels** : champ « Texte du rappel » (60 car.) + mode édition
  (`AddScheduleView(medId:/medicationId:editScheduleId:)`, `Route.editSchedule`,
  recalcul du nextDue comme à la création) ; le label remplace nom/alias dans la
  notification quand le mode ≠ générique (`NotificationManager.makeMedContent`),
  jamais montré en générique — même résolution qu'Android.
- **Hub rappels** : catégorie « Journal d'humeur » (LabReminderStore kind
  `journal`, titre notif « Moment journal », titres par catégorie pour
  photo/voix/labo), lignes med cliquables → édition en sheet, section
  « Rendez-vous » en lecture seule (RemindersView).
- **Calendrier Journal** : bande continue « Règles » (caps arrondis aux
  extrémités de plage), points colorés par traitement (≤ 3) + légende du mois
  (JournalView, réutilise `MedColor.color(fromArgb:)`).
- **Courbes hormones** : interpolation `.linear`, axe X daté (première /
  médiane / dernière mesure, « d MMM »), marqueurs de prises interpolés sur la
  courbe, couleur du traitement (HormonesView).
- **Parseur labo** : `bp_systolic`/`bp_diastolic` (paire, fenêtres de
  plausibilité, habitude cmHg ×10), `hemoglobin` (gardes HbA1c/« Hémoglobine
  A1c »), `hematocrit` ; unités `g/dL` (+ pli « g/100 mL »), `%`, `mmHg` ;
  catalogue `Catalog.swift` aligné byte-for-byte sur Android.

Toujours valable : validation finale = build CI macOS (aucun toolchain local).
