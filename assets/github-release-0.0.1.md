## 🇫🇷 Version française

Première release publique d'**eggshell** — carnet privé de suivi
d'hormonothérapie (HRT). 100 % local, chiffré, zéro serveur, zéro tracker.

### Installer

Télécharge `eggshell-0.0.1.apk` ci-dessous et installe-le sur ton téléphone
Android (8.0 minimum, soit API 26+).

Sur la plupart des téléphones tu devras autoriser l'install d'apps depuis ton
navigateur la première fois (Réglages → Sécurité → Sources inconnues, ou bien
le prompt système qui apparaît au moment de l'install).

> Cette APK est l'APK universel — toutes les ABIs (arm64-v8a, armeabi-v7a,
> x86_64) sont packagées dedans, donc elle marche sur tous les téléphones
> mais pèse 72 Mo. La version Play Store fera ~25 Mo car Google distribue
> un split adapté à chaque appareil.

### Vérifier l'intégrité

```
SHA-256 : 714fb95c0eaa8fa28c86794933b92fdec2f7d9f7cdc6d29212dad58f796ba60c
```

Sur Linux/Mac :
```
sha256sum eggshell-0.0.1.apk
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

First public release of **eggshell** — a private notebook for hormone-therapy
(HRT) tracking. 100 % on-device, encrypted, no server, no tracker.

### Install

Download `eggshell-0.0.1.apk` below and install it on your Android phone
(8.0 minimum, i.e. API 26+).

On most phones you'll need to allow installs from your browser the first
time (Settings → Security → Unknown sources, or accept the system prompt
that shows up at install time).

> This is a universal APK — every ABI (arm64-v8a, armeabi-v7a, x86_64) is
> packaged inside so it runs on every phone, at the cost of weighing 72 MB.
> The Play Store version will be ~25 MB because Google ships a per-device
> split.

### Verify integrity

```
SHA-256: 714fb95c0eaa8fa28c86794933b92fdec2f7d9f7cdc6d29212dad58f796ba60c
```

On Linux/Mac:
```
sha256sum eggshell-0.0.1.apk
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