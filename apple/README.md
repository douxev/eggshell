# eggshell — iOS (SwiftUI + Liquid Glass, Rust core réutilisé)

L'app iOS **réutilise le même cœur Rust** que l'app Android (`core/`), exposé en
Swift via uniffi → un **XCFramework** statique. L'UI est en **SwiftUI** avec le
design **Liquid Glass** (iOS 26) et un repli `.ultraThinMaterial` sur iOS 18–25.

> **Tu n'as pas de Mac.** Tout (compilation, signature, upload TestFlight) tourne
> sur les **runners macOS de GitHub Actions**. Tu valides visuellement en
> installant le build via **TestFlight sur ton iPhone**. Ce guide est écrit pour
> ce mode 100 % CI.

---

## 1. Arborescence

```
apple/
├── build-ios.sh                # Rust → XCFramework + bindings Swift (tourne sur macOS/CI)
├── Gemfile                     # fastlane
├── TransitionCore/             # package SwiftPM enveloppant le XCFramework
│   ├── Package.swift
│   └── Sources/TransitionCore/ # TransitionCore.swift (commité) + transition.swift (généré)
├── Eggshell/                   # l'app
│   ├── project.yml             # spec XcodeGen → .xcodeproj généré (jamais commité)
│   ├── Resources/Info.plist + Assets.xcassets (icône)
│   └── Sources/{App,Theme,Platform,Core}/
└── fastlane/{Appfile,Matchfile,Fastfile}

.github/workflows/
├── ios-ci.yml          # build de vérif simulateur, sans secret, à chaque push
├── ios-certs.yml       # ONE-TIME : crée les certificats (manuel)
└── ios-testflight.yml  # build signé + upload TestFlight (tag ios-v* ou manuel)
```

Artefacts **générés** (gitignorés, reconstruits par la CI) : le `.xcframework`,
le `transition.swift`, le `.xcodeproj`.

---

## 2. Mise en ligne — étapes (à faire une seule fois)

### 2.1 App Store Connect : enregistrer l'app
1. <https://developer.apple.com/account> → **Certificates, Identifiers & Profiles**
   → **Identifiers** → **+** → *App IDs* → *App* → Bundle ID **explicite**
   `com.douxev.eggshell`. Coche les capacités nécessaires (aucune particulière
   pour l'instant ; Push non requis).
2. <https://appstoreconnect.apple.com> → **Apps** → **+** → **New App** :
   - Plateforme **iOS**, nom (ex. *eggshell*), langue principale **Français**,
   - Bundle ID = `com.douxev.eggshell`, SKU libre (ex. `eggshell-ios`).
   - *(Tant que l'app record n'existe pas, aucun upload TestFlight n'est possible.)*
3. Note ton **Team ID** (Membership) → ce sera le secret `DEVELOPMENT_TEAM`.

### 2.2 Clé API App Store Connect (auth sans 2FA pour la CI)
App Store Connect → **Users and Access** → **Integrations** → **App Store Connect
API** → **+** (rôle *App Manager*). Tu obtiens :
- **Key ID** → secret `APP_STORE_CONNECT_API_KEY_KEY_ID`
- **Issuer ID** → secret `APP_STORE_CONNECT_API_KEY_ISSUER_ID`
- le fichier **`AuthKey_XXXX.p8`** (téléchargeable **une seule fois**).

Encode le `.p8` en base64 pour le secret `APP_STORE_CONNECT_API_KEY_KEY` :
```bash
base64 -i AuthKey_XXXX.p8 | tr -d '\n'      # macOS/Linux ; colle la sortie dans le secret
```

### 2.3 Dépôt privé `match` (stockage chiffré des certificats)
Crée un **dépôt GitHub privé** vide, p.ex. `douxev/eggshell-ios-certs`.
- `MATCH_GIT_URL` = `https://github.com/douxev/eggshell-ios-certs.git`
- `MATCH_PASSWORD` = une **passphrase forte que tu inventes** (elle chiffre le repo).
- `MATCH_GIT_BASIC_AUTHORIZATION` = base64 de `utilisateur:TOKEN` (un **PAT** GitHub
  avec accès *write* à ce repo) :
  ```bash
  printf 'douxev:ghp_xxxTONPAT' | base64 | tr -d '\n'
  ```

### 2.4 Renseigner les secrets GitHub
Repo `douxev/eggshell` → **Settings → Secrets and variables → Actions → New
repository secret**. Ajoute **les 7** (tableau §3).

### 2.5 Générer les certificats (sans Mac)
Onglet **Actions** → workflow **iOS Certificates (one-time setup)** → **Run
workflow**. Il crée le certificat *Apple Distribution* + le profil *App Store* et
les pousse, chiffrés, dans ton repo `match`. À refaire seulement si le certificat
expire/est révoqué.

### 2.6 Premier build TestFlight
Deux options :
- **Manuel** : Actions → **iOS TestFlight (beta)** → **Run workflow**.
- **Par tag** :
  ```bash
  git tag ios-v0.0.6 && git push origin ios-v0.0.6
  ```
Le pipeline : build Rust (3 cibles) → XCFramework → XcodeGen → signature (match)
→ archive → **upload TestFlight** (testeurs **internes**, donc **sans Beta App
Review**, dispo en quelques minutes).

### 2.7 Tester sur ton iPhone
App Store Connect → ton app → **TestFlight** → **Internal Testing** → ajoute-toi
comme testeur interne (ton Apple ID doit être un utilisateur du compte). Installe
l'app **TestFlight** sur l'iPhone → le build apparaît → installe.

---

## 3. Secrets GitHub requis

| Secret | Contenu |
|---|---|
| `DEVELOPMENT_TEAM` | Ton Apple **Team ID** (10 caractères) |
| `APP_STORE_CONNECT_API_KEY_KEY_ID` | Key ID de la clé API |
| `APP_STORE_CONNECT_API_KEY_ISSUER_ID` | Issuer ID (UUID) |
| `APP_STORE_CONNECT_API_KEY_KEY` | **base64** du fichier `.p8` |
| `MATCH_GIT_URL` | URL du dépôt privé `match` |
| `MATCH_PASSWORD` | Passphrase de chiffrement du repo `match` |
| `MATCH_GIT_BASIC_AUTHORIZATION` | base64 de `user:PAT` (write sur le repo `match`) |

---

## 4. Conformité chiffrement (export compliance)

`Info.plist` déclare `ITSAppUsesNonExemptEncryption = false` : l'app ne chiffre
que **ses propres données locales** avec des algorithmes **standard publiés**
(AES-GCM, Argon2id, SQLCipher), ce qui relève de l'**exemption**. Cela évite le
blocage « Missing Compliance » sur chaque build.
⚠️ **À confirmer pour ta juridiction.** Si tu ajoutes un chiffrement non
standard, passe la clé à `true` et complète l'auto-classification dans App Store
Connect.

## 5. Beta interne vs externe

- **Interne** (jusqu'à 100 testeurs du compte) : **pas de revue**, immédiat → ce
  que fait le pipeline par défaut (`distribute_external: false`).
- **Externe** (jusqu'à 10 000, lien public) : **Beta App Review requise** pour le
  1ᵉʳ build d'une version + infos de test (email, « quoi tester »). À promouvoir
  manuellement quand tu seras prêt.

## 6. Liquid Glass / Xcode 26

Les workflows utilisent `xcode-version: latest-stable` (toujours compilable). Pour
le **vrai** Liquid Glass, épingle une version **Xcode 26.x** dans les trois
workflows une fois l'image runner disponible. Sans Xcode 26, l'app se construit
quand même avec le repli `.ultraThinMaterial` (voir `Theme/GlassCompat.swift`).

## 7. Dépannage

| Symptôme | Cause / correctif |
|---|---|
| `Missing Compliance` sur TestFlight | clé `ITSAppUsesNonExemptEncryption` non répondue → déjà gérée (`false`) ; sinon réponds dans ASC |
| `No profiles for 'com.douxev.eggshell'` | lance d'abord **ios-certs** ; vérifie `DEVELOPMENT_TEAM` et le Bundle ID enregistré |
| build OpenSSL/SQLCipher échoue pour le simulateur | doit tourner sur **macOS** ; `openssl-src` doit être en 300.x (déjà le cas) |
| `module 'transitionFFI' not found` | le modulemap doit s'appeler `module.modulemap` → géré par `build-ios.sh` |
| upload bloque le runner | `skip_waiting_for_build_processing: true` (déjà activé) |

## 8. Si tu obtiens un Mac plus tard

```bash
bash apple/build-ios.sh                      # construit le XCFramework + bindings
brew install xcodegen
cd apple/Eggshell && xcodegen generate
open Eggshell.xcodeproj                       # itère dans le simulateur
```
