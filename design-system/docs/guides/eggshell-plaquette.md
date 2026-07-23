# Eggshell — dossier pour plaquette commerciale

Tout ce qu'il faut pour construire une plaquette, une landing page ou un
one-pager. Les chiffres et formulations viennent du dépôt (README, code,
captures) — rien n'est inventé. Les composants de ce design system suffisent à
maquetter la plaquette : `EggshellLogo`, `PhoneFrame`, `Card`, `ListRow`,
`SectionTitle`, `Button`.

## 1. En une phrase

**Eggshell — le suivi de transition qui ne quitte jamais ton téléphone.**

Variantes selon la longueur disponible :

- Court : *Suivi de transition. Chiffré. Hors ligne.*
- Moyen : *Hormones, ressenti, photos, analyses — tout au même endroit, et
  nulle part ailleurs.*
- Long : *Une app native pour suivre sa transition : traitements, journal,
  courbes hormonales, photos d'évolution. Base chiffrée, aucun compte, aucun
  serveur, et un mode leurre pour les contextes hostiles.*

## 2. Positionnement

Eggshell est **conçue pour et avec des personnes trans**. Ce n'est pas une app
santé généraliste avec un module « genre » : chaque écran part des besoins
réels d'un parcours de transition.

Le différenciateur n'est pas la fonctionnalité, c'est le **modèle de menace**.
Les apps de santé classiques supposent un utilisateur en sécurité. Eggshell
suppose l'inverse : téléphone qu'on peut te demander de déverrouiller, entourage
qui fouille, contexte légal instable. D'où le chiffrement local, l'absence totale
de réseau, et le mode leurre.

**Trois piliers** à reprendre tels quels dans la plaquette :

1. **Tout reste sur ton téléphone** — pas de compte, pas de serveur, pas
   d'analytics.
2. **Chiffré, et vérifiable** — SQLCipher + Argon2id, code source ouvert.
3. **Pensé pour les contextes hostiles** — mode leurre, alias d'icône, masquage.

## 3. Public

- **Cœur de cible** : personnes trans sous traitement hormonal (THS), qui
  veulent suivre doses, ressenti et évolution sans confier ces données à un
  tiers.
- **Secondaire** : personnes en questionnement ou en début de parcours, qui
  veulent un journal privé avant d'en parler à qui que ce soit.
- **Prescripteurs** : endocrinologues et médecins généralistes — l'export PDF
  est fait pour la consultation.
- **Communauté** : associations et groupes d'entraide, qui recommandent des
  outils sûrs.

## 4. Fonctionnalités (avec le bénéfice, pas juste la feature)

| Fonctionnalité | À dire dans la plaquette |
|---|---|
| **Médicaments** | Gel, patch, comprimé, injection — avec dosage, voie d'administration et rotation des sites d'injection. *Tu sais toujours où en était la dernière piqûre.* |
| **Rappels** | Notifications via `AlarmManager`, réarmées après redémarrage. *Un rappel qui survit à un reboot.* |
| **Journal** | Humeur, dysphorie/euphorie, libido, effets physiques et secondaires. Quatre curseurs, dix secondes. *Assez rapide pour être fait tous les jours.* |
| **Taux hormonaux** | Saisie des analyses (OCR optionnel), courbes, unités configurables. *Tes résultats deviennent une tendance, pas une pile de PDF.* |
| **Photos d'évolution** | Photos datées, comparaison avant/après, stockées dans le coffre chiffré. *Les photos les plus intimes sont les mieux protégées.* |
| **Voix** | Clips courts, détection de hauteur, vue chronologique. *Entendre le changement, pas seulement le mesurer.* |
| **Export PDF** | Rapport clair (traitements, courbes, journal) pour l'endocrinologue. *Arriver en consultation avec des données, pas des souvenirs.* |
| **Widget** | Prochaine prise visible depuis l'écran d'accueil. |
| **Thèmes** | 14 palettes, dont Catppuccin, Gruvbox, Tokyo Night, Dracula, Nord, Rosé Pine, Solarized, One Dark. *Une app qui te ressemble.* |

## 5. Sécurité — les faits exacts

À citer précisément ; ce sont les arguments les plus forts et les plus
vérifiables.

- **Stockage** : SQLite chiffré via **SQLCipher**. La clé de base est dérivée
  par **Argon2id** depuis le secret de déverrouillage.
- **Déverrouillage** : code PIN ou biométrie. Le secret biométrique est
  encapsulé dans le **Android Keystore** (clé non exportable, liée au TEE /
  StrongBox quand disponible).
- **Anti-force brute** : les tentatives de PIN sont freinées par backoff
  exponentiel.
- **Mode leurre** : un second secret ouvre un écran anodin (calculatrice /
  notes) qui ne révèle **pas même l'existence** des vraies données.
- **Alias d'icône** : l'app peut se présenter sous une icône de calculatrice ou
  de météo.
- **Zéro réseau** : aucune requête sortante, aucun analytics, aucun rapport de
  crash. Vérifiable : la permission `android.permission.INTERNET` **n'est pas
  demandée** dans `AndroidManifest.xml`.

> Argument massue pour la plaquette : *« Ne nous crois pas sur parole —
> l'app ne demande même pas la permission d'accéder à Internet. »*

## 6. Technique (pour un encart « sous le capot »)

- **Cœur métier en Rust**, partagé entre plateformes ; pont généré par
  **UniFFI**.
- **Android** : Kotlin + Jetpack Compose, Material 3.
- **iOS** : portage SwiftUI en cours, sur le même cœur Rust (~71 fichiers
  Swift à ce jour ; parité partielle — ne pas l'annoncer comme disponible).
- **Licence** : GPL-3.0-or-later, avec une permission additionnelle (section 7)
  autorisant la distribution via les stores. Le choix de la GPL est délibéré :
  il empêche un fork propriétaire d'affaiblir le coffre ou le mode leurre puis
  de le rediffuser sous le même nom.

## 7. Statut — à ne pas surpromettre

Version **0.1.0**, en développement actif. **Pas encore publiée sur les
stores** ; canaux prévus : Google Play, F-Droid, GitHub Releases (APK signé),
App Store après le portage iOS.

L'app **n'a pas encore reçu d'audit de sécurité tiers**. Le README le dit
explicitement — une plaquette honnête doit le refléter (« pré-version », « ne
confie pas encore des données que tu ne peux pas te permettre de perdre »).
C'est aussi un appel à contribution crédible.

## 8. Identité visuelle

- **Logo** : un œuf pêche sur fond crème dégradé. « Eggshell » = coquille : ce
  qui protège, et ce qu'on finit par briser. Ne pas recolorer l'œuf selon le
  thème — les jetons `--brand-egg` / `--brand-shell` sont fixes.
- **Couleurs** : lavande/violet par défaut (`--primary` `#6A4FA3` en clair,
  `#D4BBFF` en sombre), rose comme couleur d'accent secondaire.
- **Formes** : coins très arrondis (cartes 24 px, lignes de liste 28 px), FAB
  carré arrondi (18 px), boutons en pilule.
- **Typo** : Roboto Flex ; échelle Material 3 via `t-display-s`, `t-headline`,
  `t-title`, `t-body`, `t-label-s`.
- **Captures disponibles** dans `assets/` du dépôt : `home.png`, `meds.png`,
  `journal.png`, `timeline.png`, `themes.png`, `encryption.png`,
  `settings.png`, plus `feature-graphic-1024x500.png`.

## 9. Ton

Tutoiement, direct, sans jargon médical ni pathos. L'app dit « Bonjour »,
« Comment tu te sens ? », « Tout est pris ✓ ». Jamais de ton clinique, jamais de
ton militant : factuel et chaleureux. Éviter « patient », « traitement du
trouble », « condition ». Préférer « ton parcours », « tes doses », « ton
ressenti ».

## 10. Structure de plaquette suggérée

1. **Bandeau** — logo, titre, la phrase courte, un `PhoneFrame` montrant
   l'écran d'accueil.
2. **Trois piliers** (§2) en trois `Card`, une icône chacune : `encrypted`,
   `cloud_off`, `visibility_off`.
3. **Fonctionnalités** — 4 à 6 `ListRow` avec icônes (`medication`,
   `mood`, `show_chart`, `photo_camera`, `graphic_eq`, `picture_as_pdf`).
4. **Sécurité** — encart sombre, les faits du §5, avec la phrase « même pas la
   permission Internet ».
5. **Thèmes** — rangée de `ThemeSwatchCard`, preuve visuelle de la
   personnalisation.
6. **Statut et contribution** — version, pas encore en store, appel à
   contributions (sécurité, accessibilité, traductions, iOS).
7. **Pied de page** — licence GPL, lien du dépôt.
