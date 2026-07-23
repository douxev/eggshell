# TransitionCore — surface Swift générée (référence)

Généré par uniffi 0.28 depuis `core/transition-uniffi/src/transition.udl`.
`import TransitionCore` dans l'app. **Régénéré à chaque build** (`build-ios.sh`) —
ce fichier est la copie de référence figée pour le développement.

Mapping de types : `i64 → Int64`, `u32 → UInt32`, `i32 → Int32`, `f64 → Double`,
`bytes → Data`, `boolean → Bool`, `T? → T?`, `sequence<T> → [T]`.

## Fonctions de namespace (libres)

```swift
func hello(name: String) -> String
func freshKdfMaterial() -> FreshKdfMaterial
func standardInjectionSites() -> [String]
func convertHormoneValue(value: Double, fromUnit: String, toUnit: String, hormone: String) -> Double?
func vaultVerifyKey(dbPath: String, key: VaultKey) throws            // validation rapide avant unlock
func importEncrypted(bundle: Data, passphrase: String, targetDbPath: String) throws -> ImportedVault
```

## VaultKey (classe)

```swift
VaultKey.random() -> VaultKey                                        // 32 octets via OsRng
VaultKey.fromRaw(raw: Data) throws -> VaultKey                       // après unwrap Keychain
VaultKey.deriveFromPassphrase(passphrase: String, salt: Data,
        mCostKib: UInt32, tCost: UInt32, pCost: UInt32) throws -> VaultKey   // mode PARANOID
VaultKey.unwrapWithPassphrase(wrapped: Data, passphrase: String, salt: Data,
        mCostKib: UInt32, tCost: UInt32, pCost: UInt32) throws -> VaultKey
key.exportRaw() -> Data                                              // 32 octets, à enrober côté natif
key.wrapWithPassphrase(passphrase: String, salt: Data,
        mCostKib: UInt32, tCost: UInt32, pCost: UInt32) throws -> Data
```

## Vault (classe)

```swift
Vault(dbPath: String, key: VaultKey) throws                          // ouvre/crée le SQLCipher
vault.schemaVersion() throws -> UInt32

// Médicaments
vault.addMedication(med: NewMedication, nowMs: Int64) throws -> Medication
vault.getMedication(id: Int64) throws -> Medication?
vault.listMedications(includeArchived: Bool) throws -> [Medication]
vault.setMedicationArchived(id: Int64, archived: Bool) throws

// Doses + sites d'injection
vault.logDose(dose: NewDoseEvent) throws -> DoseEvent
vault.logDoses(doses: [NewDoseEvent]) throws -> [DoseEvent]          // batch, 1 transaction (log par période)
vault.updateDose(id: Int64, dose: NewDoseEvent) throws -> DoseEvent  // édition en place, id stable
vault.getDose(id: Int64) throws -> DoseEvent?
vault.listDoses(medicationId: Int64, offset: Int64, limit: Int64) throws -> [DoseEvent]
vault.suggestNextInjectionSite(medicationId: Int64, historyDepth: Int64) throws -> String?

// Plannings
vault.addSchedule(schedule: NewDoseSchedule, nowMs: Int64) throws -> DoseSchedule
vault.listActiveSchedules() throws -> [DoseSchedule]
vault.listSchedulesForMedication(medicationId: Int64, includeInactive: Bool) throws -> [DoseSchedule]
vault.updateSchedule(id: Int64, schedule: NewDoseSchedule) throws -> DoseSchedule // id stable; active/createdAtMs intouchés
vault.setScheduleActive(id: Int64, active: Bool) throws
vault.setScheduleNextDue(id: Int64, nextDueAtMs: Int64) throws

// Règles / bleeding (batch — « cette semaine = règles »)
vault.addBleedingEntries(entries: [NewBleedingEntry]) throws -> [BleedingEntry]

// Journal
vault.addJournalEntry(entry: NewJournalEntry) throws -> JournalEntry
vault.listJournalEntries(offset: Int64, limit: Int64) throws -> [JournalEntry]
vault.getJournalEntry(id: Int64) throws -> JournalEntry?
vault.deleteJournalEntry(id: Int64) throws

// Hormones
vault.addHormoneMeasurement(m: NewHormoneMeasurement) throws -> HormoneMeasurement
vault.listHormoneMeasurements(hormone: String, offset: Int64, limit: Int64) throws -> [HormoneMeasurement]
vault.distinctHormones() throws -> [String]
vault.deleteHormoneMeasurement(id: Int64) throws

// Photos
vault.addPhotoRecord(photo: NewPhotoRecord) throws -> PhotoRecord
vault.listPhotoRecords(offset: Int64, limit: Int64) throws -> [PhotoRecord]
vault.deletePhotoRecord(id: Int64) throws

// Clips vocaux
vault.addVoiceClip(clip: NewVoiceClip) throws -> VoiceClip
vault.listVoiceClips(offset: Int64, limit: Int64) throws -> [VoiceClip]
vault.deleteVoiceClip(id: String) throws

// Blobs (photos/audio) + backup
vault.encryptBlob(plaintext: Data) throws -> Data                   // nonce(12) || AES-256-GCM, file-key HKDF
vault.decryptBlob(ciphertext: Data) throws -> Data
vault.exportEncrypted(passphrase: String) throws -> Data            // bundle .transition.enc
```

## Structs (dictionaries)

`FreshKdfMaterial{salt:Data, mCostKib:UInt32, tCost:UInt32, pCost:UInt32}` ·
`ImportedVault{masterKey:Data}` ·
`Medication{id, name, kind, route, defaultDose:Double?, defaultDoseUnit:String?, color:Int64?, notes:String?, archived:Bool, createdAtMs:Int64}` ·
`NewMedication{name, kind, route, defaultDose:Double?, defaultDoseUnit:String?, color:Int64?, notes:String?}` ·
`DoseEvent / NewDoseEvent{medicationId, takenAtMs, dose:Double?, doseUnit:String?, route:String?, injectionSite:String?, notes:String?}` ·
`DoseSchedule / NewDoseSchedule{medicationId, kind, intervalMinutes:UInt32?, dailyHour:UInt32?, dailyMinute:UInt32?, intervalDays:UInt32?, nextDueAtMs:Int64, label:String? = nil, ...}` — `label` = texte de rappel personnalisé (schema v14) ·
`JournalEntry / NewJournalEntry{atMs, mood:UInt32?, dysphoria:UInt32?, euphoria:UInt32?, libido:UInt32?, energy:UInt32?, freeText:String?, sideEffects:String?}` ·
`HormoneMeasurement / NewHormoneMeasurement{atMs, hormone, value:Double, unit, labName:String?, notes:String?}` ·
`PhotoRecord / NewPhotoRecord{atMs, category:String?, filePath, notes:String?}` ·
`VoiceClip / NewVoiceClip{id:String, atMs, durationMs, filePath, pitchHz:Int32?}`

## Erreurs

```swift
enum TransitionError: Error {
    case Unimplemented(message: String)
    case Crypto(message: String)
    case Database(message: String)
    case Migration(message: String)
    case WrongKey(message: String)     // ← mauvaise clé / mauvais PIN-passphrase
    case VaultBusy(message: String)
}
```

## Cycle de vie de la clé — ce que le natif iOS doit faire

Le **core ne persiste rien** : c'est la couche iOS qui gère le stockage de la clé.
1. **Création** : `VaultKey.random()` → `key.exportRaw()` → enrober via Secure
   Enclave/Keychain (AES-GCM) → stocker le blob. (Mode passphrase : aussi
   `wrapWithPassphrase` ; mode paranoïaque : ne rien stocker, redériver à chaque fois.)
2. **Déverrouillage** : déchiffrer le blob (Keychain/biométrie) → `VaultKey.fromRaw`
   (ou `unwrapWithPassphrase` / `deriveFromPassphrase`) → `vaultVerifyKey` (check
   rapide) → `Vault(dbPath:key:)`.
3. **Import backup** : `importEncrypted(...)` renvoie `ImportedVault.masterKey` →
   `VaultKey.fromRaw` → ré-enrober localement → `Vault(dbPath:key:)`.
