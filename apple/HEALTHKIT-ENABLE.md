# Turning on the Santé (HealthKit) import

The import is written (`Eggshell/Sources/Sport/HealthKitImporter.swift`), but
it ships **parked**. The App Store provisioning profile that fastlane match
installs was made for an App ID without the HealthKit capability. Signing with
the HealthKit entitlement against that profile fails, and the TestFlight
pipeline would break.

So, until the steps below are done:

- `project.yml` keeps `CODE_SIGN_ENTITLEMENTS: Eggshell.entitlements` (no
  HealthKit);
- `Eggshell-HealthKit.entitlements` sits next to it, unused. It has the same
  keychain group plus `com.apple.developer.healthkit` = true and an empty
  `com.apple.developer.healthkit.access`;
- `Info.plist` has `EggshellHealthKitEnabled` = `false`. Every HealthKit entry
  point checks it: the switch « Importer depuis Santé (Apple) » is not shown,
  and the unlock and Sport-screen syncs return at once. A build without the
  entitlement therefore never offers a feature that cannot work.
  `NSHealthShareUsageDescription` is already in place.

## Steps (in this order)

1. **Apple Developer portal → Certificates, IDs & Profiles → Identifiers →
   `com.douxev.eggshell`.** Tick **HealthKit** and save. Do not tick
   "Clinical Health Records" (the app reads none).
2. **Regenerate the App Store profile.** The capability change makes the
   current `match AppStore com.douxev.eggshell` profile invalid.
   - Run the **iOS Certificates** workflow (`.github/workflows/ios-certs.yml`,
     manual dispatch). Its `certs` lane runs match with `readonly: false`.
   - If match reports the existing profile as still valid and does not
     regenerate it, delete that one profile (not the certificate) in the
     portal under Profiles, then run the workflow again.
   - Never use `match nuke`: it also revokes the distribution certificate.
3. **Point signing at the HealthKit entitlements.** In
   `apple/Eggshell/project.yml`, under `targets.Eggshell.settings.base`:
   ```yaml
   CODE_SIGN_ENTITLEMENTS: Eggshell-HealthKit.entitlements
   ```
4. **Flip the flag.** In `apple/Eggshell/Resources/Info.plist`:
   ```xml
   <key>EggshellHealthKitEnabled</key>
   <true/>
   ```
5. **Ship through TestFlight**, then check on a device: Sport › Types
   d'activité et podomètre › « Importer depuis Santé (Apple) ». Turning it on
   shows the Santé permission sheet.

Do steps 3 and 4 in the same commit, and only after step 2 has succeeded.
Either one without the new profile breaks signing (step 3) or shows a switch
that fails (step 4).

## App Store Connect

- **App Privacy.** Health and fitness data is read on the device and stored in
  the encrypted vault on the device. Nothing is transmitted, so under Apple's
  definition nothing is "collected". Re-check the answers when the feature
  ships.
- **Review notes.** Mention that Santé access is read only, opt-in from the
  Sport module, and that the data never leaves the device (guideline 5.1.3:
  no iCloud, no third party, no advertising use).

## What it reads

Read only; the app writes nothing to Santé:

- workouts, which become sessions with source `"health"`;
- steps, as daily sums from local midnight, recorded with the vault's
  keep-the-larger rule;
- heart rate, as average and peak over each workout, kept only within
  20–300 bpm;
- walking/running and cycling distance, from each workout's statistics.

A workout is skipped when any session starts at exactly the same millisecond,
or when a non-manual session starts within ±60 s. A session typed by hand is
never replaced.

The first sync covers 30 days. Each later one starts 2 days before the
previous sync. Syncs run after a real unlock and when the Sport screen opens.
