import Foundation
import TransitionCore

// Serializes all access to the opened Rust `Vault` off the main thread. The core
// returns `VaultBusy` if hit concurrently, so a single actor is the right model.
// UI code never touches `Vault` directly — it calls these async methods.
actor VaultService {
    private let vault: Vault
    /// Which DB is open — used to hide/disable destructive features under decoy.
    let isDecoy: Bool

    init(vault: Vault, isDecoy: Bool) {
        self.vault = vault
        self.isDecoy = isDecoy
    }

    func schemaVersion() throws -> UInt32 { try vault.schemaVersion() }

    // MARK: Medications
    func addMedication(_ med: NewMedication, nowMs: Int64 = Time.nowMs()) throws -> Medication {
        try vault.addMedication(med: med, nowMs: nowMs)
    }
    func getMedication(_ id: Int64) throws -> Medication? { try vault.getMedication(id: id) }
    func listMedications(includeArchived: Bool = false) throws -> [Medication] {
        try vault.listMedications(includeArchived: includeArchived)
    }
    func setMedicationArchived(_ id: Int64, _ archived: Bool) throws {
        try vault.setMedicationArchived(id: id, archived: archived)
    }
    func updateMedication(_ id: Int64, _ med: NewMedication) throws {
        try vault.updateMedication(id: id, med: med)
    }
    /// Hard-delete a medication; the core cascades its doses, schedules and
    /// treatment changes. Off-vault reminders are torn down by the caller first.
    func deleteMedication(_ id: Int64) throws { try vault.deleteMedication(id: id) }

    // MARK: Treatment changes (dose/route edit audit, feeds the correlation view)
    @discardableResult
    func logTreatmentChange(_ change: NewTreatmentChange) throws -> TreatmentChange {
        try vault.logTreatmentChange(change: change)
    }
    func listTreatmentChanges(fromMs: Int64, toMs: Int64) throws -> [TreatmentChange] {
        try vault.listTreatmentChanges(fromMs: fromMs, toMs: toMs)
    }

    // MARK: Doses
    func logDose(_ dose: NewDoseEvent) throws -> DoseEvent { try vault.logDose(dose: dose) }
    /// Batch insert in one core transaction — the "log a date range" flow
    /// (e.g. a daily topical declared for several months in one go).
    @discardableResult
    func logDoses(_ doses: [NewDoseEvent]) throws -> [DoseEvent] { try vault.logDoses(doses: doses) }
    /// Overwrite a recorded dose in place (fix route/date/amount after the
    /// fact). The id stays stable.
    @discardableResult
    func updateDose(_ id: Int64, _ dose: NewDoseEvent) throws -> DoseEvent {
        try vault.updateDose(id: id, dose: dose)
    }
    func getDose(_ id: Int64) throws -> DoseEvent? { try vault.getDose(id: id) }
    func listDoses(medicationId: Int64, offset: Int64 = 0, limit: Int64 = 50) throws -> [DoseEvent] {
        try vault.listDoses(medicationId: medicationId, offset: offset, limit: limit)
    }
    /// All dose events across meds in a time window (for the correlation view).
    func listDoseEventsBetween(fromMs: Int64, toMs: Int64) throws -> [DoseEvent] {
        try vault.listDoseEventsBetween(fromMs: fromMs, toMs: toMs)
    }
    func suggestNextInjectionSite(medicationId: Int64, historyDepth: Int64 = 8) throws -> String? {
        try vault.suggestNextInjectionSite(medicationId: medicationId, historyDepth: historyDepth)
    }
    /// Remove a single recorded dose from the history.
    func deleteDose(_ id: Int64) throws { try vault.deleteDose(id: id) }

    // MARK: Schedules
    func addSchedule(_ s: NewDoseSchedule, nowMs: Int64 = Time.nowMs()) throws -> DoseSchedule {
        try vault.addSchedule(schedule: s, nowMs: nowMs)
    }
    func listActiveSchedules() throws -> [DoseSchedule] { try vault.listActiveSchedules() }
    func listSchedulesForMedication(_ medicationId: Int64, includeInactive: Bool) throws -> [DoseSchedule] {
        try vault.listSchedulesForMedication(medicationId: medicationId, includeInactive: includeInactive)
    }
    /// Edit a reminder in place — id stays stable so scheduled notifications
    /// keep pointing at the same schedule; `active`/`createdAtMs` untouched.
    @discardableResult
    func updateSchedule(_ id: Int64, _ s: NewDoseSchedule) throws -> DoseSchedule {
        try vault.updateSchedule(id: id, schedule: s)
    }
    func setScheduleActive(_ id: Int64, _ active: Bool) throws { try vault.setScheduleActive(id: id, active: active) }
    func setScheduleNextDue(_ id: Int64, _ nextDueAtMs: Int64) throws {
        try vault.setScheduleNextDue(id: id, nextDueAtMs: nextDueAtMs)
    }
    func deleteSchedule(_ id: Int64) throws { try vault.deleteSchedule(id: id) }

    // MARK: Journal
    func addJournalEntry(_ e: NewJournalEntry) throws -> JournalEntry { try vault.addJournalEntry(entry: e) }
    func listJournalEntries(offset: Int64 = 0, limit: Int64 = 200) throws -> [JournalEntry] {
        try vault.listJournalEntries(offset: offset, limit: limit)
    }
    func getJournalEntry(_ id: Int64) throws -> JournalEntry? { try vault.getJournalEntry(id: id) }
    /// Non-destructive in-place update — keeps the entry id (and its linked
    /// MetricValues) instead of delete+recreate.
    @discardableResult
    func updateJournalEntry(_ id: Int64, _ e: NewJournalEntry) throws -> JournalEntry {
        try vault.updateJournalEntry(id: id, entry: e)
    }
    func deleteJournalEntry(_ id: Int64) throws { try vault.deleteJournalEntry(id: id) }

    // MARK: Customizable metric definitions / values (shared by journal + bleeding)
    func listMetricDefinitions(domain: String, includeArchived: Bool = false) throws -> [MetricDefinition] {
        try vault.listMetricDefinitions(domain: domain, includeArchived: includeArchived)
    }
    @discardableResult
    func addMetricDefinition(_ def: NewMetricDefinition) throws -> MetricDefinition {
        try vault.addMetricDefinition(def: def)
    }
    func updateMetricDefinition(_ id: Int64, _ upd: MetricDefinitionUpdate) throws {
        try vault.updateMetricDefinition(id: id, upd: upd)
    }
    func archiveMetricDefinition(_ id: Int64) throws { try vault.archiveMetricDefinition(id: id) }
    func listMetricValues(entryDomain: String, entryId: Int64) throws -> [MetricValue] {
        try vault.listMetricValues(entryDomain: entryDomain, entryId: entryId)
    }
    func replaceMetricValues(entryDomain: String, entryId: Int64, values: [MetricValue]) throws {
        try vault.replaceMetricValues(entryDomain: entryDomain, entryId: entryId, values: values)
    }

    // MARK: Bleeding / cycle tracking
    @discardableResult
    func addBleedingEntry(_ e: NewBleedingEntry) throws -> BleedingEntry { try vault.addBleedingEntry(entry: e) }
    /// Batch insert in one core transaction — « cette semaine = règles »
    /// logged in one action instead of one entry per day.
    @discardableResult
    func addBleedingEntries(_ entries: [NewBleedingEntry]) throws -> [BleedingEntry] {
        try vault.addBleedingEntries(entries: entries)
    }
    func listBleedingEntries(offset: Int64 = 0, limit: Int64 = 500) throws -> [BleedingEntry] {
        try vault.listBleedingEntries(offset: offset, limit: limit)
    }
    func getBleedingEntry(_ id: Int64) throws -> BleedingEntry? { try vault.getBleedingEntry(id: id) }
    @discardableResult
    func updateBleedingEntry(_ id: Int64, _ e: NewBleedingEntry) throws -> BleedingEntry {
        try vault.updateBleedingEntry(id: id, entry: e)
    }
    func deleteBleedingEntry(_ id: Int64) throws { try vault.deleteBleedingEntry(id: id) }

    // MARK: Appointments / notes ("RDV")
    @discardableResult
    func addAppointment(_ a: NewAppointment) throws -> Appointment { try vault.addAppointment(appt: a) }
    func listAppointments(offset: Int64 = 0, limit: Int64 = 500) throws -> [Appointment] {
        try vault.listAppointments(offset: offset, limit: limit)
    }
    func getAppointment(_ id: Int64) throws -> Appointment? { try vault.getAppointment(id: id) }
    @discardableResult
    func updateAppointment(_ id: Int64, _ a: NewAppointment) throws -> Appointment {
        try vault.updateAppointment(id: id, appt: a)
    }
    func deleteAppointment(_ id: Int64) throws { try vault.deleteAppointment(id: id) }

    // MARK: Hormones
    func addHormoneMeasurement(_ m: NewHormoneMeasurement) throws -> HormoneMeasurement {
        try vault.addHormoneMeasurement(m: m)
    }
    func listHormoneMeasurements(hormone: String, offset: Int64 = 0, limit: Int64 = 500) throws -> [HormoneMeasurement] {
        try vault.listHormoneMeasurements(hormone: hormone, offset: offset, limit: limit)
    }
    func distinctHormones() throws -> [String] { try vault.distinctHormones() }
    func deleteHormoneMeasurement(_ id: Int64) throws { try vault.deleteHormoneMeasurement(id: id) }

    // MARK: Photos
    func addPhotoRecord(_ p: NewPhotoRecord) throws -> PhotoRecord { try vault.addPhotoRecord(photo: p) }
    func listPhotoRecords(offset: Int64 = 0, limit: Int64 = 500) throws -> [PhotoRecord] {
        try vault.listPhotoRecords(offset: offset, limit: limit)
    }
    func deletePhotoRecord(_ id: Int64) throws { try vault.deletePhotoRecord(id: id) }

    // MARK: Voice
    func addVoiceClip(_ c: NewVoiceClip) throws -> VoiceClip { try vault.addVoiceClip(clip: c) }
    func listVoiceClips(offset: Int64 = 0, limit: Int64 = 500) throws -> [VoiceClip] {
        try vault.listVoiceClips(offset: offset, limit: limit)
    }
    func deleteVoiceClip(_ id: String) throws { try vault.deleteVoiceClip(id: id) }

    // MARK: Settings (in-vault key/value)
    /// The only *encrypted* preference store the app has. An absent key reads
    /// nil; the empty string is a value, so clear a field with `deleteSetting`.
    func getSetting(_ key: String) throws -> String? { try vault.getSetting(key: key) }
    func setSetting(_ key: String, _ value: String) throws {
        try vault.setSetting(key: key, value: value)
    }
    func deleteSetting(_ key: String) throws { try vault.deleteSetting(key: key) }

    // MARK: Backup
    func exportEncrypted(passphrase: String) throws -> Data { try vault.exportEncrypted(passphrase: passphrase) }

    // MARK: Encrypted blob files (photos/voice live as <uuid>.bin on disk)

    /// Encrypt `plaintext` and write it to `dir/<uuid>.bin`. Returns the file path.
    func encryptBlobToFile(_ plaintext: Data, in dir: URL, ext: String = "bin") throws -> URL {
        let cipher = try vault.encryptBlob(plaintext: plaintext)
        let url = dir.appendingPathComponent(UUID().uuidString).appendingPathExtension(ext)
        try cipher.write(to: url, options: .atomic)
        return url
    }

    /// Read an encrypted blob file and return the decrypted plaintext.
    func decryptBlobFile(_ url: URL) throws -> Data {
        let cipher = try Data(contentsOf: url)
        return try vault.decryptBlob(ciphertext: cipher)
    }
}

enum Time {
    static func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
}
