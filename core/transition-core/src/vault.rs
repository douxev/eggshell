//! Vault — orchestrates crypto + DB for the 4 supported security modes.
//!
//! The Rust core is mode-agnostic. It exposes building blocks:
//! - [`VaultKey::random`] / [`VaultKey::derive_from_passphrase`] — produce a 32-byte key
//! - [`VaultKey::wrap_with_passphrase`] / [`VaultKey::unwrap_with_passphrase`] — passphrase-KEK envelope
//! - [`VaultKey::export_raw`] / [`VaultKey::from_raw`] — for Keystore/Keychain wrap on the native side
//! - [`Vault::open`] — open or create the SQLCipher DB with a `VaultKey`
//!
//! Where each mode persists its auxiliary state (the Keystore-wrapped key blob,
//! the KDF salt and parameters) is the **native side's responsibility** — the
//! Rust core never touches non-DB files. This keeps the FFI surface narrow and
//! lets Android use EncryptedSharedPreferences while iOS uses the Keychain
//! later, without the core having to care.

use std::path::Path;
use std::sync::{Mutex, MutexGuard};

use crate::crypto::{
    EncryptedBlob, KEY_LEN, KdfParams, MasterKey, NONCE_LEN, SALT_LEN, decrypt, decrypt_with_aad,
    encrypt, encrypt_with_aad,
};
use crate::db::Database;
use crate::TransitionError;

/// HKDF info string for the file-blob sub-key. Bumping the version string
/// effectively rotates every photo/voice key — only use a new version when
/// you actually want all existing blobs to become unreadable.
const FILE_KEY_INFO: &[u8] = b"eggshell::files::v1";

/// Backup bundle magic. Encodes the format version — bumping the byte at
/// position 7 lets old apps detect-and-refuse newer bundles cleanly.
const BUNDLE_MAGIC_V2: &[u8; 8] = b"TRNSITN2";
/// Predecessor magic. We refuse v1 bundles with a typed error because they
/// don't include the master key and therefore cannot be restored without
/// the source device's Keystore — which by definition the user no longer
/// has access to (otherwise they wouldn't be restoring).
const BUNDLE_MAGIC_V1: &[u8; 8] = b"TRNSITN1";

/// A 32-byte symmetric key, used both to encrypt SQLCipher and as the
/// plaintext that the native side wraps with Keystore/Keychain.
pub struct VaultKey {
    inner: MasterKey,
}

impl VaultKey {
    /// Generate a fresh random key from the OS CSPRNG. Used by Keystore modes
    /// at vault initialization.
    pub fn random() -> Self {
        Self { inner: MasterKey::generate() }
    }

    /// Derive a key directly from a passphrase via Argon2id. Used by the
    /// paranoid mode, where no key material is ever persisted.
    pub fn derive_from_passphrase(
        passphrase: String,
        salt: Vec<u8>,
        m_cost_kib: u32,
        t_cost: u32,
        p_cost: u32,
    ) -> Result<Self, TransitionError> {
        let params = build_kdf_params(salt, m_cost_kib, t_cost, p_cost)?;
        let key = params.derive(passphrase.as_bytes())?;
        Ok(Self { inner: key })
    }

    /// Reconstruct a key from its raw bytes (e.g. after a Keystore unwrap).
    pub fn from_raw(bytes: Vec<u8>) -> Result<Self, TransitionError> {
        if bytes.len() != KEY_LEN {
            return Err(TransitionError::Crypto(format!(
                "VaultKey expects {KEY_LEN} bytes, got {}",
                bytes.len()
            )));
        }
        let mut buf = [0u8; KEY_LEN];
        buf.copy_from_slice(&bytes);
        Ok(Self { inner: MasterKey::from_bytes(buf) })
    }

    /// Export the raw bytes so the native layer can wrap them with
    /// Keystore/Keychain. Callers should drop the returned `Vec<u8>` as soon
    /// as they have stored the wrapped form.
    pub fn export_raw(&self) -> Vec<u8> {
        self.inner.expose().to_vec()
    }

    /// Wrap this key under a passphrase-derived KEK and return the
    /// serialized (`nonce || ciphertext`) blob suitable for storing on disk.
    pub fn wrap_with_passphrase(
        &self,
        passphrase: String,
        salt: Vec<u8>,
        m_cost_kib: u32,
        t_cost: u32,
        p_cost: u32,
    ) -> Result<Vec<u8>, TransitionError> {
        let kek_params = build_kdf_params(salt, m_cost_kib, t_cost, p_cost)?;
        let kek = kek_params.derive(passphrase.as_bytes())?;
        let blob = encrypt(&kek, self.inner.expose())?;
        Ok(blob.to_vec())
    }

    /// Inverse of [`wrap_with_passphrase`].
    pub fn unwrap_with_passphrase(
        wrapped: Vec<u8>,
        passphrase: String,
        salt: Vec<u8>,
        m_cost_kib: u32,
        t_cost: u32,
        p_cost: u32,
    ) -> Result<Self, TransitionError> {
        let kek_params = build_kdf_params(salt, m_cost_kib, t_cost, p_cost)?;
        let kek = kek_params.derive(passphrase.as_bytes())?;
        let blob = EncryptedBlob::from_slice(&wrapped)?;
        let plaintext = decrypt(&kek, &blob)?;
        if plaintext.len() != KEY_LEN {
            return Err(TransitionError::Crypto(
                "unwrapped material is not the expected key length".into(),
            ));
        }
        let mut buf = [0u8; KEY_LEN];
        buf.copy_from_slice(&plaintext);
        Ok(Self { inner: MasterKey::from_bytes(buf) })
    }
}

/// Bundle of fresh KDF material to hand back to the native side at init.
/// Returned as a plain record so UniFFI maps it cleanly.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct FreshKdfMaterial {
    pub salt: Vec<u8>,
    pub m_cost_kib: u32,
    pub t_cost: u32,
    pub p_cost: u32,
}

/// Generate OWASP-recommended Argon2id parameters with a fresh random salt.
/// The caller persists this so unlock can reuse the same salt and params.
pub fn fresh_kdf_material() -> FreshKdfMaterial {
    let p = KdfParams::recommended();
    FreshKdfMaterial {
        salt: p.salt.to_vec(),
        m_cost_kib: p.m_cost_kib,
        t_cost: p.t_cost,
        p_cost: p.p_cost,
    }
}

/// Output of a successful [`import_encrypted`]: the 32-byte master key the
/// source device used to encrypt the DB. The native layer must wrap this
/// under its local Keystore (or chosen passphrase mode) before unlocking the
/// restored vault — otherwise the local wrapped_key would still point at
/// whatever was there before the import.
#[derive(Clone, Debug)]
pub struct ImportedVault {
    pub master_key: Vec<u8>,
}

/// Open or create the encrypted vault. Wraps a single SQLCipher connection.
///
/// `Vault` holds the master DB key in memory and an HKDF-derived sub-key for
/// opaque file blobs (photos, audio). The sub-key is bound to a versioned
/// info string so a future migration can rotate file-key derivation without
/// touching the DB.
pub struct Vault {
    db: Mutex<Database>,
    /// Master DB key. Kept alongside the connection so backup export can
    /// embed the raw bytes in the bundle — without this, restoring on a
    /// clean device is impossible (the source Keystore would be gone).
    master_key: MasterKey,
    /// HKDF-derived sub-key for opaque file blobs (photos, voice). Domain
    /// separation from the DB key: an oracle on encrypt_blob can't be
    /// replayed against SQLCipher pages.
    file_key: MasterKey,
    db_path: String,
}

impl Vault {
    /// Open or create the vault at `db_path`, keyed with `key`.
    pub fn open(db_path: String, key: &VaultKey) -> Result<Self, TransitionError> {
        let db = Database::open(Path::new(&db_path), &key.inner)?;
        let file_key = key.inner.derive_subkey(FILE_KEY_INFO);
        let mut buf = [0u8; KEY_LEN];
        buf.copy_from_slice(key.inner.expose());
        let master_key = MasterKey::from_bytes(buf);
        Ok(Self { db: Mutex::new(db), master_key, file_key, db_path })
    }

    /// Cheap key check that does not run migrations. Useful to validate an
    /// unlock attempt without re-opening the full database.
    pub fn verify_key(db_path: String, key: &VaultKey) -> Result<(), TransitionError> {
        Database::verify_key(Path::new(&db_path), &key.inner)
    }

    /// Acquire the inner DB lock, mapping poison errors to a typed
    /// [`TransitionError::VaultBusy`]. A panic in another thread that holds
    /// the lock would otherwise propagate as an unwrap panic across the FFI
    /// boundary and crash the whole app.
    fn db(&self) -> Result<MutexGuard<'_, Database>, TransitionError> {
        self.db.lock().map_err(|_| TransitionError::VaultBusy)
    }

    /// Current schema version of the open DB.
    pub fn schema_version(&self) -> Result<u32, TransitionError> {
        self.db()?.schema_version()
    }

    // -- Medication catalog (delegates to crate::medication) ----------------

    pub fn add_medication(
        &self,
        med: crate::medication::NewMedication,
        now_ms: i64,
    ) -> Result<crate::medication::Medication, TransitionError> {
        crate::medication::add(&*self.db()?, med, now_ms)
    }

    pub fn get_medication(
        &self,
        id: i64,
    ) -> Result<Option<crate::medication::Medication>, TransitionError> {
        crate::medication::get(&*self.db()?, id)
    }

    pub fn list_medications(
        &self,
        include_archived: bool,
    ) -> Result<Vec<crate::medication::Medication>, TransitionError> {
        crate::medication::list(&*self.db()?, include_archived)
    }

    pub fn set_medication_archived(
        &self,
        id: i64,
        archived: bool,
    ) -> Result<(), TransitionError> {
        crate::medication::set_archived(&*self.db()?, id, archived)
    }

    /// Hard-delete a medication. Its dose history, schedules and treatment
    /// changes cascade away (FKs are on). The native side must tear down any
    /// off-vault alarms/prefs for the med's schedules *before* calling this.
    pub fn delete_medication(&self, id: i64) -> Result<(), TransitionError> {
        crate::medication::delete(&*self.db()?, id)
    }

    /// Overwrite every editable field of a medication. The native side reads
    /// the current values, lets the user edit them, and passes the full new
    /// record back — so this is a full overwrite, not a partial patch.
    pub fn update_medication(
        &self,
        id: i64,
        med: crate::medication::NewMedication,
    ) -> Result<(), TransitionError> {
        let upd = crate::medication::MedicationUpdate {
            name: Some(med.name),
            kind: Some(med.kind),
            route: Some(med.route),
            default_dose: Some(med.default_dose),
            default_dose_unit: Some(med.default_dose_unit),
            color: Some(med.color),
            notes: Some(med.notes),
        };
        crate::medication::update(&*self.db()?, id, upd)
    }

    pub fn log_treatment_change(
        &self,
        change: crate::medication::NewTreatmentChange,
    ) -> Result<crate::medication::TreatmentChange, TransitionError> {
        crate::medication::log_treatment_change(&*self.db()?, change)
    }

    pub fn list_treatment_changes(
        &self,
        from_ms: i64,
        to_ms: i64,
    ) -> Result<Vec<crate::medication::TreatmentChange>, TransitionError> {
        crate::medication::list_treatment_changes(&*self.db()?, from_ms, to_ms)
    }

    // -- Dose log ------------------------------------------------------------

    pub fn log_dose(
        &self,
        dose: crate::medication::NewDoseEvent,
    ) -> Result<crate::medication::DoseEvent, TransitionError> {
        crate::medication::log_dose(&*self.db()?, dose)
    }

    pub fn log_doses(
        &self,
        doses: Vec<crate::medication::NewDoseEvent>,
    ) -> Result<Vec<crate::medication::DoseEvent>, TransitionError> {
        crate::medication::log_doses(&*self.db()?, doses)
    }

    pub fn update_dose(
        &self,
        id: i64,
        dose: crate::medication::NewDoseEvent,
    ) -> Result<crate::medication::DoseEvent, TransitionError> {
        crate::medication::update_dose(&*self.db()?, id, dose)
    }

    pub fn get_dose(
        &self,
        id: i64,
    ) -> Result<Option<crate::medication::DoseEvent>, TransitionError> {
        crate::medication::get_dose(&*self.db()?, id)
    }

    pub fn list_doses(
        &self,
        medication_id: i64,
        offset: i64,
        limit: i64,
    ) -> Result<Vec<crate::medication::DoseEvent>, TransitionError> {
        crate::medication::list_doses(&*self.db()?, medication_id, offset, limit)
    }

    pub fn list_dose_events_between(
        &self,
        from_ms: i64,
        to_ms: i64,
    ) -> Result<Vec<crate::medication::DoseEvent>, TransitionError> {
        crate::medication::list_dose_events_between(&*self.db()?, from_ms, to_ms)
    }

    pub fn delete_dose(&self, id: i64) -> Result<(), TransitionError> {
        crate::medication::delete_dose(&*self.db()?, id)
    }

    pub fn suggest_next_injection_site(
        &self,
        medication_id: i64,
        history_depth: i64,
    ) -> Result<Option<String>, TransitionError> {
        crate::medication::injection::next_site_for(
            &*self.db()?,
            medication_id,
            history_depth,
        )
    }

    // -- Schedules -----------------------------------------------------------

    pub fn add_schedule(
        &self,
        schedule: crate::dose_schedule::NewDoseSchedule,
        now_ms: i64,
    ) -> Result<crate::dose_schedule::DoseSchedule, TransitionError> {
        crate::dose_schedule::add(&*self.db()?, schedule, now_ms)
    }

    pub fn list_active_schedules(
        &self,
    ) -> Result<Vec<crate::dose_schedule::DoseSchedule>, TransitionError> {
        crate::dose_schedule::list_active(&*self.db()?)
    }

    pub fn list_schedules_for_medication(
        &self,
        medication_id: i64,
        include_inactive: bool,
    ) -> Result<Vec<crate::dose_schedule::DoseSchedule>, TransitionError> {
        crate::dose_schedule::list_for_medication(
            &*self.db()?,
            medication_id,
            include_inactive,
        )
    }

    pub fn update_schedule(
        &self,
        id: i64,
        schedule: crate::dose_schedule::NewDoseSchedule,
    ) -> Result<crate::dose_schedule::DoseSchedule, TransitionError> {
        crate::dose_schedule::update(&*self.db()?, id, schedule)
    }

    pub fn set_schedule_active(&self, id: i64, active: bool) -> Result<(), TransitionError> {
        crate::dose_schedule::set_active(&*self.db()?, id, active)
    }

    pub fn set_schedule_next_due(
        &self,
        id: i64,
        next_due_at_ms: i64,
    ) -> Result<(), TransitionError> {
        crate::dose_schedule::set_next_due(&*self.db()?, id, next_due_at_ms)
    }

    pub fn delete_schedule(&self, id: i64) -> Result<(), TransitionError> {
        crate::dose_schedule::delete(&*self.db()?, id)
    }

    // -- Journal -------------------------------------------------------------

    pub fn add_journal_entry(
        &self,
        entry: crate::journal::NewJournalEntry,
    ) -> Result<crate::journal::JournalEntry, TransitionError> {
        crate::journal::add(&*self.db()?, entry)
    }

    pub fn list_journal_entries(
        &self,
        offset: i64,
        limit: i64,
    ) -> Result<Vec<crate::journal::JournalEntry>, TransitionError> {
        crate::journal::list(&*self.db()?, offset, limit)
    }

    pub fn get_journal_entry(
        &self,
        id: i64,
    ) -> Result<Option<crate::journal::JournalEntry>, TransitionError> {
        crate::journal::get(&*self.db()?, id)
    }

    pub fn update_journal_entry(
        &self,
        id: i64,
        entry: crate::journal::NewJournalEntry,
    ) -> Result<crate::journal::JournalEntry, TransitionError> {
        crate::journal::update(&*self.db()?, id, entry)
    }

    pub fn delete_journal_entry(&self, id: i64) -> Result<(), TransitionError> {
        crate::journal::delete(&*self.db()?, id)
    }

    // -- Hormones ------------------------------------------------------------

    pub fn add_hormone_measurement(
        &self,
        m: crate::hormones::NewHormoneMeasurement,
    ) -> Result<crate::hormones::HormoneMeasurement, TransitionError> {
        crate::hormones::add(&*self.db()?, m)
    }

    pub fn list_hormone_measurements(
        &self,
        hormone: String,
        offset: i64,
        limit: i64,
    ) -> Result<Vec<crate::hormones::HormoneMeasurement>, TransitionError> {
        crate::hormones::list_for_hormone(&*self.db()?, hormone, offset, limit)
    }

    pub fn distinct_hormones(&self) -> Result<Vec<String>, TransitionError> {
        crate::hormones::distinct_hormones(&*self.db()?)
    }

    pub fn delete_hormone_measurement(&self, id: i64) -> Result<(), TransitionError> {
        crate::hormones::delete(&*self.db()?, id)
    }

    // -- Photos --------------------------------------------------------------

    pub fn add_photo_record(
        &self,
        photo: crate::photos::NewPhotoRecord,
    ) -> Result<crate::photos::PhotoRecord, TransitionError> {
        crate::photos::add(&*self.db()?, photo)
    }

    pub fn list_photo_records(
        &self,
        offset: i64,
        limit: i64,
    ) -> Result<Vec<crate::photos::PhotoRecord>, TransitionError> {
        crate::photos::list(&*self.db()?, offset, limit)
    }

    pub fn delete_photo_record(&self, id: i64) -> Result<(), TransitionError> {
        crate::photos::delete(&*self.db()?, id)
    }

    // -- Voice clips ---------------------------------------------------------

    pub fn add_voice_clip(
        &self,
        clip: crate::voice::NewVoiceClip,
    ) -> Result<crate::voice::VoiceClip, TransitionError> {
        crate::voice::add(&*self.db()?, clip)
    }

    pub fn list_voice_clips(
        &self,
        offset: i64,
        limit: i64,
    ) -> Result<Vec<crate::voice::VoiceClip>, TransitionError> {
        crate::voice::list(&*self.db()?, offset, limit)
    }

    pub fn delete_voice_clip(&self, id: String) -> Result<(), TransitionError> {
        crate::voice::delete(&*self.db()?, id)
    }

    // -- Metric definitions / values (shared customizable sliders) -----------

    pub fn list_metric_definitions(
        &self,
        domain: String,
        include_archived: bool,
    ) -> Result<Vec<crate::metrics::MetricDefinition>, TransitionError> {
        crate::metrics::list_definitions(&*self.db()?, domain, include_archived)
    }

    pub fn add_metric_definition(
        &self,
        def: crate::metrics::NewMetricDefinition,
    ) -> Result<crate::metrics::MetricDefinition, TransitionError> {
        crate::metrics::add_definition(&*self.db()?, def)
    }

    pub fn update_metric_definition(
        &self,
        id: i64,
        upd: crate::metrics::MetricDefinitionUpdate,
    ) -> Result<(), TransitionError> {
        crate::metrics::update_definition(&*self.db()?, id, upd)
    }

    pub fn archive_metric_definition(&self, id: i64) -> Result<(), TransitionError> {
        crate::metrics::archive_definition(&*self.db()?, id)
    }

    pub fn list_metric_values(
        &self,
        entry_domain: String,
        entry_id: i64,
    ) -> Result<Vec<crate::metrics::MetricValue>, TransitionError> {
        crate::metrics::list_values(&*self.db()?, entry_domain, entry_id)
    }

    pub fn replace_metric_values(
        &self,
        entry_domain: String,
        entry_id: i64,
        values: Vec<crate::metrics::MetricValue>,
    ) -> Result<(), TransitionError> {
        crate::metrics::replace_values(&*self.db()?, entry_domain, entry_id, values)
    }

    // -- Bleeding / cycle tracking -------------------------------------------

    pub fn add_bleeding_entry(
        &self,
        entry: crate::bleeding::NewBleedingEntry,
    ) -> Result<crate::bleeding::BleedingEntry, TransitionError> {
        crate::bleeding::add(&*self.db()?, entry)
    }

    pub fn add_bleeding_entries(
        &self,
        entries: Vec<crate::bleeding::NewBleedingEntry>,
    ) -> Result<Vec<crate::bleeding::BleedingEntry>, TransitionError> {
        crate::bleeding::add_many(&*self.db()?, entries)
    }

    pub fn list_bleeding_entries(
        &self,
        offset: i64,
        limit: i64,
    ) -> Result<Vec<crate::bleeding::BleedingEntry>, TransitionError> {
        crate::bleeding::list(&*self.db()?, offset, limit)
    }

    pub fn get_bleeding_entry(
        &self,
        id: i64,
    ) -> Result<Option<crate::bleeding::BleedingEntry>, TransitionError> {
        crate::bleeding::get(&*self.db()?, id)
    }

    pub fn update_bleeding_entry(
        &self,
        id: i64,
        entry: crate::bleeding::NewBleedingEntry,
    ) -> Result<crate::bleeding::BleedingEntry, TransitionError> {
        crate::bleeding::update(&*self.db()?, id, entry)
    }

    pub fn delete_bleeding_entry(&self, id: i64) -> Result<(), TransitionError> {
        crate::bleeding::delete(&*self.db()?, id)
    }

    // -- Appointments / notes ("RDV") ----------------------------------------

    pub fn add_appointment(
        &self,
        appt: crate::appointments::NewAppointment,
    ) -> Result<crate::appointments::Appointment, TransitionError> {
        crate::appointments::add(&*self.db()?, appt)
    }

    pub fn list_appointments(
        &self,
        offset: i64,
        limit: i64,
    ) -> Result<Vec<crate::appointments::Appointment>, TransitionError> {
        crate::appointments::list(&*self.db()?, offset, limit)
    }

    pub fn get_appointment(
        &self,
        id: i64,
    ) -> Result<Option<crate::appointments::Appointment>, TransitionError> {
        crate::appointments::get(&*self.db()?, id)
    }

    pub fn update_appointment(
        &self,
        id: i64,
        appt: crate::appointments::NewAppointment,
    ) -> Result<crate::appointments::Appointment, TransitionError> {
        crate::appointments::update(&*self.db()?, id, appt)
    }

    pub fn delete_appointment(&self, id: i64) -> Result<(), TransitionError> {
        crate::appointments::delete(&*self.db()?, id)
    }

    // -- In-vault settings ---------------------------------------------------
    //
    // The only encrypted key/value store the apps have. Anything that
    // identifies the person — the two fields of the doctor report's identity
    // block, for instance — belongs here and never in the platforms' own
    // preference files, which are not encrypted.

    pub fn get_setting(&self, key: String) -> Result<Option<String>, TransitionError> {
        crate::settings::get(&*self.db()?, &key)
    }

    pub fn set_setting(&self, key: String, value: String) -> Result<(), TransitionError> {
        crate::settings::set(&*self.db()?, &key, &value)
    }

    pub fn delete_setting(&self, key: String) -> Result<(), TransitionError> {
        crate::settings::delete(&*self.db()?, &key)
    }

    // -- Generic blob encryption (for photos, voice clips, etc.) -------------

    pub fn encrypt_blob(&self, plaintext: Vec<u8>) -> Result<Vec<u8>, TransitionError> {
        let blob = encrypt(&self.file_key, &plaintext)?;
        Ok(blob.to_vec())
    }

    pub fn decrypt_blob(&self, ciphertext: Vec<u8>) -> Result<Vec<u8>, TransitionError> {
        let blob = EncryptedBlob::from_slice(&ciphertext)?;
        let plain = decrypt(&self.file_key, &blob)?;
        Ok(plain.to_vec())
    }

    // -- Backup --------------------------------------------------------------

    /// Export the vault as a self-contained encrypted blob (v2).
    ///
    /// Layout (all little-endian):
    /// ```text
    ///   [magic "TRNSITN2"   8B]
    ///   [salt              16B]
    ///   [m_cost_kib u32     4B]
    ///   [t_cost     u32     4B]
    ///   [p_cost     u32     4B]
    ///   [nonce             12B]
    ///   [AES-256-GCM ciphertext + tag]
    /// ```
    /// The plaintext is `[master_key 32B][db_bytes…]` — including the master
    /// key means the bundle can be restored on a clean device (the source
    /// Keystore is gone forever in that scenario).
    ///
    /// All header bytes (magic + salt + params + nonce) are bound as AAD so
    /// any tampering (downgrade to v1 magic, parameter substitution to
    /// weaken Argon2id, nonce reuse) breaks the GCM tag.
    ///
    /// To avoid DB tearing under concurrent writes, we snapshot via
    /// `VACUUM INTO` into a temp file (SQLCipher keeps the same key for the
    /// destination), read it, then delete the temp.
    pub fn export_encrypted(
        &self,
        passphrase: String,
    ) -> Result<Vec<u8>, TransitionError> {
        // 1. Snapshot the DB to a temp file so we don't race writers.
        let snapshot_path = format!("{}.snapshot-{}", self.db_path, std::process::id());
        let _ = std::fs::remove_file(&snapshot_path);
        {
            let guard = self.db()?;
            guard
                .conn()
                .execute(&format!("VACUUM INTO '{}'", snapshot_path.replace('\'', "''")), [])
                .map_err(crate::sanitize_db_err)?;
        }
        let snapshot_bytes = std::fs::read(&snapshot_path)
            .map_err(|e| TransitionError::Database(format!("read snapshot: {}", io_kind(&e))))?;
        let _ = std::fs::remove_file(&snapshot_path);

        // 2. Build [master_key || snapshot].
        let mut plain = Vec::with_capacity(KEY_LEN + snapshot_bytes.len());
        plain.extend_from_slice(self.master_key.expose());
        plain.extend_from_slice(&snapshot_bytes);

        // 3. Derive KEK from passphrase + fresh params, AEAD-encrypt with header AAD.
        let params = KdfParams::recommended();
        let kek = params.derive(passphrase.as_bytes())?;
        let header = build_bundle_header(BUNDLE_MAGIC_V2, &params);
        let blob = encrypt_with_aad(&kek, &plain, &header)?;
        // Wipe plaintext (contains master key + DB bytes).
        plain.fill(0);

        // 4. Pack bundle: header || nonce || ciphertext.
        let mut out = Vec::with_capacity(header.len() + NONCE_LEN + blob.ciphertext.len());
        out.extend_from_slice(&header);
        out.extend_from_slice(&blob.nonce);
        out.extend_from_slice(&blob.ciphertext);
        Ok(out)
    }

}

/// Build the AAD header for a backup bundle. Format must stay in lock-step
/// with the on-disk layout in `Vault::export_encrypted`.
fn build_bundle_header(magic: &[u8; 8], params: &KdfParams) -> Vec<u8> {
    let mut h = Vec::with_capacity(8 + SALT_LEN + 4 + 4 + 4);
    h.extend_from_slice(magic);
    h.extend_from_slice(&params.salt);
    h.extend_from_slice(&params.m_cost_kib.to_le_bytes());
    h.extend_from_slice(&params.t_cost.to_le_bytes());
    h.extend_from_slice(&params.p_cost.to_le_bytes());
    h
}

fn io_kind(e: &std::io::Error) -> String {
    // Only the kind, not the path. The path lives inside the app sandbox
    // anyway, but we keep the FFI surface uniformly tag-shaped.
    format!("{:?}", e.kind())
}

/// Restore a vault from an exported `.transition.enc` bundle.
///
/// Returns the master key bytes that were embedded in the bundle. The native
/// caller MUST persist those bytes (wrapped by Keystore, or wrapped by a
/// passphrase, depending on the mode the user picks at restore time) before
/// attempting to open the restored DB — otherwise unlock will fail with
/// SQLCipher "file is not in database" because the locally wrapped key
/// would still be the pre-import one.
pub fn import_encrypted(
    bundle: Vec<u8>,
    passphrase: String,
    target_db_path: String,
) -> Result<ImportedVault, TransitionError> {
    if bundle.len() < 8 {
        return Err(TransitionError::Crypto("backup bundle truncated".into()));
    }
    let magic: &[u8] = &bundle[..8];

    if magic == &BUNDLE_MAGIC_V1[..] {
        // v1 bundles never contained the master key; they're unrecoverable
        // without the source device's Keystore. Refuse with a typed message
        // so the UI can explain.
        return Err(TransitionError::Crypto(
            "backup format v1 is no longer supported; re-export from your source device".into(),
        ));
    }
    if magic != &BUNDLE_MAGIC_V2[..] {
        return Err(TransitionError::Crypto(
            "unrecognised backup bundle (bad magic)".into(),
        ));
    }

    const HEADER_LEN: usize = 8 + SALT_LEN + 4 + 4 + 4;
    if bundle.len() < HEADER_LEN + NONCE_LEN {
        return Err(TransitionError::Crypto("backup bundle truncated".into()));
    }

    let mut cursor = 8usize;
    let mut salt = [0u8; SALT_LEN];
    salt.copy_from_slice(&bundle[cursor..cursor + SALT_LEN]);
    cursor += SALT_LEN;
    let m_cost_kib = u32::from_le_bytes(bundle[cursor..cursor + 4].try_into().unwrap());
    cursor += 4;
    let t_cost = u32::from_le_bytes(bundle[cursor..cursor + 4].try_into().unwrap());
    cursor += 4;
    let p_cost = u32::from_le_bytes(bundle[cursor..cursor + 4].try_into().unwrap());
    cursor += 4;
    let mut nonce = [0u8; NONCE_LEN];
    nonce.copy_from_slice(&bundle[cursor..cursor + NONCE_LEN]);
    cursor += NONCE_LEN;
    let ciphertext = bundle[cursor..].to_vec();

    let header = bundle[..HEADER_LEN].to_vec();
    let params = KdfParams::from_persisted(salt, m_cost_kib, t_cost, p_cost);
    let kek = params.derive(passphrase.as_bytes())?;
    let blob = EncryptedBlob { nonce, ciphertext };
    let plain = decrypt_with_aad(&kek, &blob, &header)?;

    if plain.len() < KEY_LEN + 1 {
        return Err(TransitionError::Crypto(
            "decrypted bundle is too short to contain a key + DB".into(),
        ));
    }
    let master_key = plain[..KEY_LEN].to_vec();
    let db_bytes = &plain[KEY_LEN..];

    // Write the DB atomically (tmp + rename) so a crash mid-write doesn't
    // leave a corrupted vault.db that blocks the next unlock.
    let tmp_path = format!("{target_db_path}.import-tmp");
    let _ = std::fs::remove_file(&tmp_path);
    std::fs::write(&tmp_path, db_bytes)
        .map_err(|e| TransitionError::Database(format!("write tmp db: {}", io_kind(&e))))?;
    std::fs::rename(&tmp_path, &target_db_path)
        .map_err(|e| TransitionError::Database(format!("rename db: {}", io_kind(&e))))?;

    Ok(ImportedVault { master_key })
}

/// Standalone helper: convert a hormone value between known units.
pub fn convert_hormone_value(
    value: f64,
    from_unit: String,
    to_unit: String,
    hormone: String,
) -> Option<f64> {
    crate::hormones::convert(value, &from_unit, &to_unit, &hormone)
}

fn build_kdf_params(
    salt: Vec<u8>,
    m_cost_kib: u32,
    t_cost: u32,
    p_cost: u32,
) -> Result<KdfParams, TransitionError> {
    if salt.len() != SALT_LEN {
        return Err(TransitionError::Crypto(format!(
            "salt expects {SALT_LEN} bytes, got {}",
            salt.len()
        )));
    }
    let mut buf = [0u8; SALT_LEN];
    buf.copy_from_slice(&salt);
    Ok(KdfParams::from_persisted(buf, m_cost_kib, t_cost, p_cost))
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::NamedTempFile;

    /// Smaller-than-real KDF parameters so the test suite stays fast.
    /// 1 MiB / 1 iteration / 1 lane — strictly for unit tests.
    const TEST_M_COST: u32 = 1024;
    const TEST_T_COST: u32 = 1;
    const TEST_P_COST: u32 = 1;

    fn fresh_db_path() -> std::path::PathBuf {
        let f = NamedTempFile::new().unwrap();
        let p = f.path().to_path_buf();
        drop(f);
        let _ = std::fs::remove_file(&p);
        p
    }

    #[test]
    fn keystore_mode_init_and_unlock_roundtrip() {
        let path = fresh_db_path();
        // 1. Init: random key → caller would Keystore-wrap export_raw()
        let key = VaultKey::random();
        let exported = key.export_raw();
        let vault = Vault::open(path.to_string_lossy().into_owned(), &key).unwrap();
        assert_eq!(vault.schema_version().unwrap(), crate::db::CURRENT_SCHEMA_VERSION);
        drop(vault);

        // 2. Unlock: reconstruct key from raw, reopen
        let reborn = VaultKey::from_raw(exported).unwrap();
        Vault::verify_key(path.to_string_lossy().into_owned(), &reborn).unwrap();
    }

    #[test]
    fn passphrase_wrap_roundtrip() {
        let key = VaultKey::random();
        let original = key.export_raw();

        let salt = vec![3u8; SALT_LEN];
        let wrapped = key
            .wrap_with_passphrase("hunter2".into(), salt.clone(), TEST_M_COST, TEST_T_COST, TEST_P_COST)
            .unwrap();

        let unwrapped = VaultKey::unwrap_with_passphrase(
            wrapped,
            "hunter2".into(),
            salt,
            TEST_M_COST,
            TEST_T_COST,
            TEST_P_COST,
        )
        .unwrap();
        assert_eq!(original, unwrapped.export_raw());
    }

    #[test]
    fn wrong_passphrase_fails_unwrap() {
        let key = VaultKey::random();
        let salt = vec![3u8; SALT_LEN];
        let wrapped = key
            .wrap_with_passphrase("hunter2".into(), salt.clone(), TEST_M_COST, TEST_T_COST, TEST_P_COST)
            .unwrap();
        let err = VaultKey::unwrap_with_passphrase(
            wrapped,
            "wrong".into(),
            salt,
            TEST_M_COST,
            TEST_T_COST,
            TEST_P_COST,
        );
        assert!(err.is_err());
    }

    #[test]
    fn paranoid_mode_full_roundtrip() {
        let path = fresh_db_path();
        let salt = vec![9u8; SALT_LEN];
        // Init
        let key1 =
            VaultKey::derive_from_passphrase("paranoid-pw".into(), salt.clone(), TEST_M_COST, TEST_T_COST, TEST_P_COST)
                .unwrap();
        let vault = Vault::open(path.to_string_lossy().into_owned(), &key1).unwrap();
        drop(vault);
        // Unlock — re-derive from passphrase
        let key2 =
            VaultKey::derive_from_passphrase("paranoid-pw".into(), salt, TEST_M_COST, TEST_T_COST, TEST_P_COST)
                .unwrap();
        Vault::verify_key(path.to_string_lossy().into_owned(), &key2).unwrap();
    }

    #[test]
    fn paranoid_mode_wrong_passphrase_rejected() {
        let path = fresh_db_path();
        let salt = vec![9u8; SALT_LEN];
        let good =
            VaultKey::derive_from_passphrase("paranoid-pw".into(), salt.clone(), TEST_M_COST, TEST_T_COST, TEST_P_COST)
                .unwrap();
        Vault::open(path.to_string_lossy().into_owned(), &good).unwrap();

        let bad =
            VaultKey::derive_from_passphrase("attacker".into(), salt, TEST_M_COST, TEST_T_COST, TEST_P_COST)
                .unwrap();
        assert!(Vault::verify_key(path.to_string_lossy().into_owned(), &bad).is_err());
    }

    #[test]
    fn keystore_passphrase_mode_full_roundtrip() {
        let path = fresh_db_path();
        let salt = vec![5u8; SALT_LEN];

        // Init: random DB key, then wrap with passphrase (Keystore wrap would
        // happen on top in the native layer — we just test the Rust slice).
        let key = VaultKey::random();
        let raw = key.export_raw();
        let wrapped = key
            .wrap_with_passphrase(
                "pp".into(),
                salt.clone(),
                TEST_M_COST,
                TEST_T_COST,
                TEST_P_COST,
            )
            .unwrap();
        let vault = Vault::open(path.to_string_lossy().into_owned(), &key).unwrap();
        drop(vault);

        // Unlock: passphrase-unwrap, then reopen
        let unwrapped = VaultKey::unwrap_with_passphrase(
            wrapped,
            "pp".into(),
            salt,
            TEST_M_COST,
            TEST_T_COST,
            TEST_P_COST,
        )
        .unwrap();
        assert_eq!(unwrapped.export_raw(), raw);
        Vault::verify_key(path.to_string_lossy().into_owned(), &unwrapped).unwrap();
    }

    #[test]
    fn from_raw_rejects_wrong_length() {
        assert!(VaultKey::from_raw(vec![0u8; 31]).is_err());
        assert!(VaultKey::from_raw(vec![0u8; 33]).is_err());
        assert!(VaultKey::from_raw(vec![0u8; 32]).is_ok());
    }

    /// End-to-end: open a vault, write a row, export, wipe the disk, import,
    /// re-open with the *imported* master key, confirm the row is still there.
    /// This is the exact path that was broken before — we'd lose access to
    /// the restored DB because the local Keystore-wrapped key didn't match.
    #[test]
    fn backup_export_then_import_roundtrip() {
        let path = fresh_db_path();
        let source_key = VaultKey::random();
        let source_master = source_key.export_raw();

        let bundle = {
            let vault = Vault::open(path.to_string_lossy().into_owned(), &source_key).unwrap();
            // Drop a recognizable row so we can detect a successful restore.
            vault.add_medication(
                crate::medication::NewMedication {
                    name: "Estradiol".into(),
                    kind: "hormone".into(),
                    route: "transdermal".into(),
                    default_dose: Some(2.0),
                    default_dose_unit: Some("mg".into()),
                    color: None,
                    notes: None,
                },
                1_700_000_000_000,
            ).unwrap();
            vault.export_encrypted("user-passphrase".into()).unwrap()
        };

        // Simulate "fresh device": wipe the DB file and pretend we have no
        // local Keystore-wrapped key. Restore from the bundle.
        std::fs::remove_file(&path).unwrap();

        let imported = import_encrypted(
            bundle,
            "user-passphrase".into(),
            path.to_string_lossy().into_owned(),
        ).unwrap();

        // The bundle's master key must equal the source's master key — that's
        // exactly what unblocks restoring on a clean device.
        assert_eq!(imported.master_key, source_master);

        // Open with the imported key; the medication row from earlier must
        // be present.
        let restored_key = VaultKey::from_raw(imported.master_key).unwrap();
        let restored = Vault::open(path.to_string_lossy().into_owned(), &restored_key).unwrap();
        let meds = restored.list_medications(false).unwrap();
        assert_eq!(meds.len(), 1);
        assert_eq!(meds[0].name, "Estradiol");
    }

    #[test]
    fn backup_roundtrip_preserves_v14_batch_data() {
        let path = fresh_db_path();
        let key = VaultKey::random();
        let day = 86_400_000i64;
        let bundle = {
            let vault = Vault::open(path.to_string_lossy().into_owned(), &key).unwrap();
            let med = vault
                .add_medication(
                    crate::medication::NewMedication {
                        name: "Estradiol gel".into(),
                        kind: "estrogen".into(),
                        route: "transdermal".into(),
                        default_dose: Some(2.0),
                        default_dose_unit: Some("mg".into()),
                        color: None,
                        notes: None,
                    },
                    1_700_000_000_000,
                )
                .unwrap();
            let sched = vault
                .add_schedule(
                    crate::dose_schedule::NewDoseSchedule {
                        medication_id: med.id,
                        kind: "daily".into(),
                        interval_minutes: None,
                        daily_hour: Some(9),
                        daily_minute: Some(0),
                        interval_days: None,
                        next_due_at_ms: 1_700_000_100_000,
                        label: Some("Aller chercher le traitement".into()),
                    },
                    1_700_000_000_000,
                )
                .unwrap();
            let doses = vault
                .log_doses(
                    (0..3)
                        .map(|i| crate::medication::NewDoseEvent {
                            medication_id: med.id,
                            taken_at_ms: 1_700_000_000_000 + i * day,
                            dose: Some(2.0),
                            dose_unit: Some("mg".into()),
                            route: Some("transdermal".into()),
                            injection_site: None,
                            notes: None,
                            status: "taken".into(),
                            scheduled_at_ms: None,
                            schedule_id: Some(sched.id),
                        })
                        .collect(),
                )
                .unwrap();
            vault
                .update_dose(
                    doses[0].id,
                    crate::medication::NewDoseEvent {
                        medication_id: med.id,
                        taken_at_ms: doses[0].taken_at_ms,
                        dose: Some(2.0),
                        dose_unit: Some("mg".into()),
                        route: Some("sublingual".into()),
                        injection_site: None,
                        notes: Some("corrigée".into()),
                        status: "taken".into(),
                        scheduled_at_ms: None,
                        schedule_id: Some(sched.id),
                    },
                )
                .unwrap();
            vault
                .add_bleeding_entries(
                    (0..4)
                        .map(|i| crate::bleeding::NewBleedingEntry {
                            at_ms: 1_699_000_000_000 + i * day,
                            is_spotting: Some(false),
                            free_text: None,
                        })
                        .collect(),
                )
                .unwrap();
            vault.export_encrypted("pp".into()).unwrap()
        };

        std::fs::remove_file(&path).unwrap();
        let imported =
            import_encrypted(bundle, "pp".into(), path.to_string_lossy().into_owned()).unwrap();
        let restored_key = VaultKey::from_raw(imported.master_key).unwrap();
        let restored = Vault::open(path.to_string_lossy().into_owned(), &restored_key).unwrap();

        let med = restored.list_medications(false).unwrap().remove(0);
        let scheds = restored.list_schedules_for_medication(med.id, true).unwrap();
        assert_eq!(scheds.len(), 1);
        assert_eq!(scheds[0].label.as_deref(), Some("Aller chercher le traitement"));
        let doses = restored.list_doses(med.id, 0, 10).unwrap();
        assert_eq!(doses.len(), 3);
        assert!(doses.iter().any(|d| {
            d.route.as_deref() == Some("sublingual") && d.notes.as_deref() == Some("corrigée")
        }));
        assert_eq!(restored.list_bleeding_entries(0, 10).unwrap().len(), 4);
    }

    #[test]
    fn restore_of_pre_label_backup_migrates_to_current_schema() {
        let path = fresh_db_path();
        let key = VaultKey::random();
        let bundle = {
            let vault = Vault::open(path.to_string_lossy().into_owned(), &key).unwrap();
            vault
                .add_medication(
                    crate::medication::NewMedication {
                        name: "Estradiol".into(),
                        kind: "estrogen".into(),
                        route: "oral".into(),
                        default_dose: None,
                        default_dose_unit: None,
                        color: None,
                        notes: None,
                    },
                    1_700_000_000_000,
                )
                .unwrap();
            // Rewind the fresh DB to the pre-label schema: drop the column
            // added by migration 0014 and reset user_version — byte-for-byte
            // what a backup exported by the previous release looks like.
            {
                let guard = vault.db().unwrap();
                guard
                    .conn()
                    .execute("ALTER TABLE dose_schedules DROP COLUMN label", [])
                    .unwrap();
                guard.conn().pragma_update(None, "user_version", 13).unwrap();
            }
            vault.export_encrypted("pp".into()).unwrap()
        };

        std::fs::remove_file(&path).unwrap();
        let imported =
            import_encrypted(bundle, "pp".into(), path.to_string_lossy().into_owned()).unwrap();
        let restored_key = VaultKey::from_raw(imported.master_key).unwrap();
        // Opening the restored file must run migration 14 transparently…
        let restored = Vault::open(path.to_string_lossy().into_owned(), &restored_key).unwrap();
        assert_eq!(
            restored.schema_version().unwrap(),
            crate::db::CURRENT_SCHEMA_VERSION
        );
        // …so v14 features work on data restored from an old backup.
        let med = restored.list_medications(false).unwrap().remove(0);
        let s = restored
            .add_schedule(
                crate::dose_schedule::NewDoseSchedule {
                    medication_id: med.id,
                    kind: "daily".into(),
                    interval_minutes: None,
                    daily_hour: Some(8),
                    daily_minute: Some(0),
                    interval_days: None,
                    next_due_at_ms: 1,
                    label: Some("après restauration".into()),
                },
                1,
            )
            .unwrap();
        assert_eq!(s.label.as_deref(), Some("après restauration"));
    }

    #[test]
    fn backup_v1_bundles_are_explicitly_refused() {
        // A v1-shaped header is enough to trigger the typed refusal — we
        // don't need a valid ciphertext.
        let mut bundle = Vec::new();
        bundle.extend_from_slice(b"TRNSITN1");
        bundle.extend_from_slice(&[0u8; 16 + 4 + 4 + 4 + 12 + 1]);
        let err = import_encrypted(bundle, "x".into(), "/tmp/x".into()).unwrap_err();
        match err {
            TransitionError::Crypto(msg) => assert!(msg.contains("v1")),
            other => panic!("expected typed refusal, got {other:?}"),
        }
    }

    #[test]
    fn backup_with_wrong_passphrase_fails() {
        let path = fresh_db_path();
        let key = VaultKey::random();
        let bundle = {
            let v = Vault::open(path.to_string_lossy().into_owned(), &key).unwrap();
            v.export_encrypted("right".into()).unwrap()
        };
        std::fs::remove_file(&path).unwrap();
        let err = import_encrypted(
            bundle,
            "wrong".into(),
            path.to_string_lossy().into_owned(),
        ).unwrap_err();
        assert!(matches!(err, TransitionError::Crypto(_)));
    }

    #[test]
    fn backup_tampered_header_fails() {
        let path = fresh_db_path();
        let key = VaultKey::random();
        let mut bundle = {
            let v = Vault::open(path.to_string_lossy().into_owned(), &key).unwrap();
            v.export_encrypted("pw".into()).unwrap()
        };
        // Flip a bit in the salt — that's part of the AAD, so decrypt must fail.
        bundle[10] ^= 0x01;
        std::fs::remove_file(&path).unwrap();
        let err = import_encrypted(bundle, "pw".into(), path.to_string_lossy().into_owned())
            .unwrap_err();
        assert!(matches!(err, TransitionError::Crypto(_)));
    }
}
