# WidgetEnable — activer le widget « prochaine dose » et le déguisement d'icône

Ce dossier **n'est pas compilé** : il ne figure pas dans `apple/Eggshell/project.yml`,
donc il ne peut **jamais casser le build**. Il contient le code et les fichiers
de configuration prêts à copier pour activer deux fonctionnalités de parité avec
Android, chacune nécessitant une modification du portail Apple Developer / des
profils de provisionnement (raison pour laquelle elles ne sont pas activées par
défaut).

Fichiers fournis :

| Fichier | Rôle | Cible de destination |
|---|---|---|
| `WidgetMirror.swift` | Helper app : écrit/efface le miroir « prochaine dose » dans le conteneur App Group (jamais de nom en clair sauf mode `.name`, `clear()` pour le leurre). Pur Foundation. | **App** (`Eggshell/Sources/Platform/`) |
| `NextDoseWidget.swift` | Code WidgetKit complet (TimelineProvider lisant le JSON du miroir, TimelineEntry, vue, `@main` WidgetBundle). Autonome. | **Widget** (`Eggshell/Sources/Widget/`) |
| `Info-Widget.plist` | Exemple d'`Info.plist` pour la cible widget. | `Eggshell/Resources/Info-Widget.plist` |
| `project-widget-snippet.yml` | Snippet XcodeGen (cible widget + entitlements App Group). | à fusionner dans `Eggshell/project.yml` |

---

## Contexte : pourquoi un miroir NON chiffré hors-coffre ?

Sur **Android**, le widget (`EggshellWidgetProvider`) lit un miroir **en clair,
non chiffré, stocké hors du coffre** (`ReminderPrefs` + `LabReminderPrefs`) afin
de pouvoir s'afficher **sans déverrouiller le coffre**.

Sur **iOS**, une extension WidgetKit s'exécute dans un **processus séparé** qui
n'a **jamais** la clé du coffre : elle ne peut donc **pas** lire `vault.db`. Le
seul canal app → widget est le **conteneur d'un App Group partagé**
(`group.com.douxev.eggshell`). Le miroir y est donc, par conception, en clair.

Règles de confidentialité (identiques à Android, implémentées dans
`WidgetMirror.swift`) :

1. **Jamais de nom de médicament en clair**, sauf si l'utilisateur a choisi le
   mode `.name` (opt-in, comme `NotificationContentMode`). Défaut = `.generic`
   → titre neutre « Prochaine prise ». En mode `.generic`, `WidgetMirror.write`
   neutralise les titres en garde-fou (défense en profondeur).
2. **Mode leurre (decoy)** : l'app appelle `WidgetMirror.clear()` → le miroir
   est vidé → le widget affiche « Aucun rappel ». C'est l'équivalent iOS de
   `WidgetVisibility.setEnabled(false)` côté Android (qui désactive le receiver
   du widget quand un PIN leurre est configuré). **Aucune** donnée réelle ne
   doit fuiter vers l'écran d'accueil sous le PIN leurre.
3. Le miroir ne contient **que** le titre (générique ou opt-in) + l'heure
   d'échéance + un nom de symbole SF optionnel. Jamais la dose, la voie, le site
   d'injection, ni les notes.

### Où l'app doit appeler `WidgetMirror`

Après avoir copié `WidgetMirror.swift` dans la cible app, câbler (sans modifier
les fichiers de fondation au-delà du strict nécessaire — typiquement dans
`AppState.refreshNotifications()` et au passage en mode leurre) :

- **Après (re)planification des rappels** (là où `NotificationManager.reschedule`
  est appelé) : construire les `WidgetMirrorEntry` à partir des
  `listActiveSchedules()` (échéance = `nextDueAtMs`) **et** de
  `LabReminderStore.upcoming()`, puis :

  ```swift
  let mode: WidgetMirrorMode = /* lire le mode de contenu choisi */ .generic
  let rows = schedules
      .filter { $0.active }
      .map { WidgetMirrorEntry(
          title: mode == .name ? (names[$0.medicationId] ?? "Médicament") : WidgetMirror.genericTitle,
          dueAtMs: $0.nextDueAtMs,
          systemImage: "pills.fill") }
  WidgetMirror.write(entries: rows, mode: mode)
  WidgetCenter.shared.reloadAllTimelines()   // import WidgetKit dans l'app
  ```

- **À l'entrée en mode leurre** (`AppState.enterDecoy()`) et au verrouillage /
  effacement complet :

  ```swift
  WidgetMirror.clear()
  WidgetCenter.shared.reloadAllTimelines()
  ```

> `WidgetMirror` est **gracieux** : si l'App Group n'est pas provisionné
> (`WidgetMirror.available == false`), tous les appels sont des no-op silencieux.
> Le code peut donc être câblé **avant** que l'App Group ne soit créé, sans
> risque de crash.

---

## (a) Activer le widget WidgetKit « prochaine dose »

### 1. Créer l'App Group dans le portail Apple Developer

1. <https://developer.apple.com/account> → **Certificates, Identifiers & Profiles**.
2. **Identifiers** → bouton **+** → **App Groups** → Continue.
3. Description : `Eggshell shared group` ; Identifier : **`group.com.douxev.eggshell`**.
   (DOIT correspondre exactement à `WidgetMirror.appGroupId` et `NextDoseMirror.appGroupId`.)
4. Register.

### 2. Créer l'App ID de l'extension widget

1. **Identifiers** → **+** → **App IDs** → **App** → Continue.
2. Bundle ID : **`com.douxev.eggshell.widget`** (Explicit).
3. Dans **Capabilities**, cocher **App Groups**.
4. Register, puis **Edit** l'App ID → **App Groups** → **Configure** →
   cocher `group.com.douxev.eggshell` → Save.

### 3. Activer App Groups sur l'App ID de l'app principale

1. **Identifiers** → ouvrir **`com.douxev.eggshell`** → cocher **App Groups** →
   **Configure** → `group.com.douxev.eggshell` → Save.

### 4. Ajouter l'App Group aux entitlements (app + widget)

- **App** — fusionner dans `apple/Eggshell/Eggshell.entitlements` (conserver
  `keychain-access-groups` existant) :

  ```xml
  <key>com.apple.security.application-groups</key>
  <array>
    <string>group.com.douxev.eggshell</string>
  </array>
  ```

- **Widget** — créer `apple/Eggshell/EggshellWidget.entitlements` :

  ```xml
  <?xml version="1.0" encoding="UTF-8"?>
  <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
  <plist version="1.0">
  <dict>
    <key>com.apple.security.application-groups</key>
    <array>
      <string>group.com.douxev.eggshell</string>
    </array>
  </dict>
  </plist>
  ```

### 5. Régénérer les profils `match`

Les deux App IDs ont changé (capability App Groups) → il faut de **nouveaux
profils** signés en conséquence, pour **l'app ET le widget** :

```bash
cd apple
# régénère les profils (réécrit le repo match) :
bundle exec fastlane match appstore -a com.douxev.eggshell        --force
bundle exec fastlane match appstore -a com.douxev.eggshell.widget --force
```

Puis, dans `fastlane/Fastfile`, étendre `export_options.provisioningProfiles`
pour mapper aussi le widget :

```ruby
provisioningProfiles: {
  "com.douxev.eggshell"        => "match AppStore com.douxev.eggshell",
  "com.douxev.eggshell.widget" => "match AppStore com.douxev.eggshell.widget"
}
```

> En CI, `match` tourne en `readonly: true` ; le `--force` ci-dessus est une
> opération **manuelle, ponctuelle** (comme la lane `certs`). Ne pas l'exécuter
> dans la pipeline beta normale.

### 6. Ajouter la cible widget dans `project.yml`

1. Copier les sources :

   ```bash
   mkdir -p apple/Eggshell/Sources/Widget
   cp apple/WidgetEnable/NextDoseWidget.swift apple/Eggshell/Sources/Widget/
   cp apple/WidgetEnable/WidgetMirror.swift   apple/Eggshell/Sources/Platform/
   cp apple/WidgetEnable/Info-Widget.plist    apple/Eggshell/Resources/Info-Widget.plist
   ```

2. Fusionner `project-widget-snippet.yml` dans `apple/Eggshell/project.yml`
   (nouvelle cible `EggshellWidgetExtension` + dépendance depuis `Eggshell`).
   Veiller à **exclure** `Info-Widget.plist` de la compilation de ressources de
   l'app (comme `Info.plist` l'est déjà).

3. Régénérer le projet et builder :

   ```bash
   cd apple/Eggshell && xcodegen generate
   ```

### 7. Câbler l'app

Ajouter les appels `WidgetMirror.write(...)` / `WidgetMirror.clear()` +
`WidgetCenter.shared.reloadAllTimelines()` comme décrit plus haut, et
`import WidgetKit` là où l'on recharge les timelines.

---

## (b) Activer le déguisement d'icône (alternate app icons)

Parité avec `AppAliasManager` (Notes / Calculatrice / Météo). Le code iOS existe
déjà : `apple/Eggshell/Sources/Platform/AppIconManager.swift` (utilise
`UIApplication.setAlternateIconName`). Il ne manque QUE les assets PNG + l'entrée
Info.plist. **Aucune** modification d'entitlement ni de provisionnement n'est
requise pour les icônes alternatives.

`AppIconVariant` (déjà défini) attend ces noms d'icône :
`AppIcon-Notes`, `AppIcon-Calculator`, `AppIcon-Weather` (la variante `.default`
utilise l'`AppIcon` du catalogue d'assets).

### 1. Préparer les PNG

Pour chaque variante, fournir un PNG **opaque, carré, sans coins arrondis ni
canal alpha** (iOS arrondit lui-même). Les icônes alternatives **ne** vivent
**pas** dans `Assets.xcassets` : ce sont des fichiers **à la racine du bundle**.
Fournir au minimum la taille 60 pt @2x et @3x :

```
Eggshell/Resources/AltIcons/
  AppIcon-Notes@2x.png        (120 × 120)
  AppIcon-Notes@3x.png        (180 × 180)
  AppIcon-Calculator@2x.png   (120 × 120)
  AppIcon-Calculator@3x.png   (180 × 180)
  AppIcon-Weather@2x.png      (120 × 120)
  AppIcon-Weather@3x.png      (180 × 180)
```

Inclure ce dossier en **ressources du bundle** dans `project.yml` (les `.png`
sont copiés tels quels ; ne PAS les placer dans un `.xcassets`).

### 2. Déclarer `CFBundleIcons.CFBundleAlternateIcons` dans `Info.plist`

Fusionner dans `apple/Eggshell/Resources/Info.plist` :

```xml
<key>CFBundleIcons</key>
<dict>
    <key>CFBundleAlternateIcons</key>
    <dict>
        <key>AppIcon-Notes</key>
        <dict>
            <key>CFBundleIconFiles</key>
            <array><string>AppIcon-Notes</string></array>
            <key>UIPrerenderedIcon</key>
            <false/>
        </dict>
        <key>AppIcon-Calculator</key>
        <dict>
            <key>CFBundleIconFiles</key>
            <array><string>AppIcon-Calculator</string></array>
            <key>UIPrerenderedIcon</key>
            <false/>
        </dict>
        <key>AppIcon-Weather</key>
        <dict>
            <key>CFBundleIconFiles</key>
            <array><string>AppIcon-Weather</string></array>
            <key>UIPrerenderedIcon</key>
            <false/>
        </dict>
    </dict>
</dict>
```

> Le nom dans `CFBundleIconFiles` (`"AppIcon-Notes"`) est le **préfixe** des
> fichiers : iOS résolvera `AppIcon-Notes@2x.png` / `AppIcon-Notes@3x.png`. Ces
> clés doivent correspondre EXACTEMENT à `AppIconVariant.iconName`.

### 3. Vérifier

Une fois les PNG et l'entrée Info.plist en place :
`AppIconManager.available` devient `true` (au lieu de griser le sélecteur), et
`AppIconManager.set(.notes)` bascule l'icône de l'écran d'accueil. Régénérer le
projet (`xcodegen generate`) et relancer.

> **iPad** : si `TARGETED_DEVICE_FAMILY` inclut `2` un jour, ajouter aussi les
> variantes 76/83.5 pt. Pour iPhone uniquement, les tailles 60 pt suffisent.
