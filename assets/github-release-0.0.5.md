## 🇫🇷 Version française

Mise à jour **0.0.5** — fixes critiques sur le mode biométrique.

### Quoi de neuf

- **Fix critique data-loss** : l'activation biométrique pouvait corrompre le
  coffre de manière irrécupérable (clé Keystore supprimée avant validation du
  nouveau mode). Si tu as été affectée par la 0.0.4, désinstalle/réinstalle ou
  efface les données depuis les paramètres Android, puis restaure depuis un
  backup `.transition.enc` si tu en as un.
- **Fix** : le prompt biométrique ne s'affichait pas sur certains appareils
  (combinaison StrongBox + biométrie qui plante silencieusement sur Samsung
  Knox notamment). Le module sécurisé TEE est utilisé à la place pour les clés
  bio.
- **Fix** : flash d'erreur "must be called from main thread" avant le prompt
  d'empreinte, et "FragmentManager is already executing transactions"
  intermittent — l'appel au prompt se fait maintenant correctement sur le
  thread principal.
- Messages d'erreur plus précis (nom d'exception inclus) pour faciliter le
  diagnostic.

### Installer

Télécharge `eggshell-0.0.5.apk` ci-dessous et installe-le sur ton téléphone
Android (8.0 minimum, soit API 26+).

Sur la plupart des téléphones tu devras autoriser l'install d'apps depuis ton
navigateur la première fois (Réglages → Sécurité → Sources inconnues, ou bien
le prompt système qui apparaît au moment de l'install).

> Cette APK est l'APK universel — toutes les ABIs (arm64-v8a, armeabi-v7a,
> x86_64) sont packagées dedans, donc elle marche sur tous les téléphones
> mais pèse 65 Mo. La version Play Store fera ~25 Mo car Google distribue
> un split adapté à chaque appareil.

### Vérifier l'intégrité

```
SHA-256 : 0f503ea4ed1c454574883c1c53f073910a6a5e60530258bc2623f6ad05dab12b
```

Sur Linux/Mac :
```
sha256sum eggshell-0.0.5.apk
```

L'APK est signée avec ma clé de release dédiée — empreinte SHA-1 du
certificat :
```
53:64:88:86:CC:84:90:94:23:36:61:F9:47:E6:D5:37:7F:EF:25:75
```

### Compatible avec

- Android 8.0 (API 26) et +
- arm64, arm32, x86_64
- **Sans Google Play Services** — fonctionne sur GrapheneOS, CalyxOS,
  LineageOS et tout autre Android dé-googlisé.

### Politique de confidentialité

→ <https://douxev.github.io/eggshell/>

---

## 🇬🇧 English version

Update **0.0.5** — critical fixes around biometric mode.

### What's new

- **Critical data-loss fix**: enabling biometric mode could permanently
  corrupt the vault (Keystore key was deleted before the new mode was
  committed). If you hit this on 0.0.4, uninstall/reinstall or clear app data
  from Android settings, then restore from a `.transition.enc` backup if you
  have one.
- **Fix**: biometric prompt didn't show on some devices (StrongBox + biometric
  silently fails on Samsung Knox among others). The TEE secure module is now
  used instead for biometric keys.
- **Fix**: "must be called from main thread" error flash before the
  fingerprint prompt, and intermittent "FragmentManager is already executing
  transactions" — the prompt invocation now correctly runs on the main thread.
- Clearer error messages (exception class included) to make diagnostics easier.

### Install

Download `eggshell-0.0.5.apk` below and install it on your Android phone
(8.0 minimum, i.e. API 26+).

On most phones you'll need to allow installs from your browser the first
time (Settings → Security → Unknown sources, or accept the system prompt
that shows up at install time).

> This is a universal APK — every ABI (arm64-v8a, armeabi-v7a, x86_64) is
> packaged inside so it runs on every phone, at the cost of weighing 65 MB.
> The Play Store version will be ~25 MB because Google ships a per-device
> split.

### Verify integrity

```
SHA-256: 0f503ea4ed1c454574883c1c53f073910a6a5e60530258bc2623f6ad05dab12b
```

On Linux/Mac:
```
sha256sum eggshell-0.0.5.apk
```

The APK is signed with my dedicated release key — certificate SHA-1
fingerprint:
```
53:64:88:86:CC:84:90:94:23:36:61:F9:47:E6:D5:37:7F:EF:25:75
```

### Compatible with

- Android 8.0 (API 26) and up
- arm64, arm32, x86_64
- **No Google Play Services required** — works on GrapheneOS, CalyxOS,
  LineageOS and any other de-Googled Android build.

### Privacy policy

→ <https://douxev.github.io/eggshell/en/>
