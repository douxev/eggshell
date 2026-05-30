//! transition-uniffi — thin FFI façade over `transition-core`.
//!
//! Each interface here is a one-field wrapper around the corresponding
//! `transition_core` type. The wrappers exist purely to give UniFFI a stable
//! type to attach scaffolding to without forcing the core crate to depend on
//! UniFFI itself.

use std::sync::Arc;

use transition_core::vault as core_vault;

pub use transition_core::TransitionError;
// The medication types are simple data records — re-export so UniFFI scaffolds
// them directly without an extra wrapper layer.
pub use transition_core::medication::{DoseEvent, Medication, NewDoseEvent, NewMedication};
pub use transition_core::dose_schedule::{DoseSchedule, NewDoseSchedule};
pub use transition_core::hormones::{HormoneMeasurement, NewHormoneMeasurement};
pub use transition_core::journal::{JournalEntry, NewJournalEntry};
pub use transition_core::photos::{NewPhotoRecord, PhotoRecord};
pub use transition_core::voice::{NewVoiceClip, VoiceClip};

uniffi::include_scaffolding!("transition");

// -- Namespace-level functions ------------------------------------------------

fn hello(name: String) -> String {
    transition_core::hello(name)
}

fn fresh_kdf_material() -> FreshKdfMaterial {
    let m = core_vault::fresh_kdf_material();
    FreshKdfMaterial {
        salt: m.salt,
        m_cost_kib: m.m_cost_kib,
        t_cost: m.t_cost,
        p_cost: m.p_cost,
    }
}

fn vault_verify_key(db_path: String, key: Arc<VaultKey>) -> Result<(), TransitionError> {
    core_vault::Vault::verify_key(db_path, &key.inner)
}

fn standard_injection_sites() -> Vec<String> {
    transition_core::medication::injection::STANDARD_SITES
        .iter()
        .map(|s| s.to_string())
        .collect()
}

fn convert_hormone_value(
    value: f64,
    from_unit: String,
    to_unit: String,
    hormone: String,
) -> Option<f64> {
    core_vault::convert_hormone_value(value, from_unit, to_unit, hormone)
}

fn import_encrypted(
    bundle: Vec<u8>,
    passphrase: String,
    target_db_path: String,
) -> Result<ImportedVault, TransitionError> {
    let imported = core_vault::import_encrypted(bundle, passphrase, target_db_path)?;
    Ok(ImportedVault { master_key: imported.master_key })
}

#[derive(Clone, Debug)]
pub struct ImportedVault {
    pub master_key: Vec<u8>,
}

// -- Dictionaries -------------------------------------------------------------

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct FreshKdfMaterial {
    pub salt: Vec<u8>,
    pub m_cost_kib: u32,
    pub t_cost: u32,
    pub p_cost: u32,
}

// -- VaultKey -----------------------------------------------------------------

pub struct VaultKey {
    inner: core_vault::VaultKey,
}

impl VaultKey {
    pub fn random() -> Self {
        Self { inner: core_vault::VaultKey::random() }
    }

    pub fn from_raw(raw: Vec<u8>) -> Result<Self, TransitionError> {
        Ok(Self { inner: core_vault::VaultKey::from_raw(raw)? })
    }

    pub fn derive_from_passphrase(
        passphrase: String,
        salt: Vec<u8>,
        m_cost_kib: u32,
        t_cost: u32,
        p_cost: u32,
    ) -> Result<Self, TransitionError> {
        Ok(Self {
            inner: core_vault::VaultKey::derive_from_passphrase(
                passphrase,
                salt,
                m_cost_kib,
                t_cost,
                p_cost,
            )?,
        })
    }

    pub fn unwrap_with_passphrase(
        wrapped: Vec<u8>,
        passphrase: String,
        salt: Vec<u8>,
        m_cost_kib: u32,
        t_cost: u32,
        p_cost: u32,
    ) -> Result<Self, TransitionError> {
        Ok(Self {
            inner: core_vault::VaultKey::unwrap_with_passphrase(
                wrapped,
                passphrase,
                salt,
                m_cost_kib,
                t_cost,
                p_cost,
            )?,
        })
    }

    pub fn export_raw(&self) -> Vec<u8> {
        self.inner.export_raw()
    }

    pub fn wrap_with_passphrase(
        &self,
        passphrase: String,
        salt: Vec<u8>,
        m_cost_kib: u32,
        t_cost: u32,
        p_cost: u32,
    ) -> Result<Vec<u8>, TransitionError> {
        self.inner
            .wrap_with_passphrase(passphrase, salt, m_cost_kib, t_cost, p_cost)
    }
}

// -- Vault --------------------------------------------------------------------

pub struct Vault {
    inner: core_vault::Vault,
}

impl Vault {
    /// UDL's unnamed `constructor(...)` looks for `new`. We delegate to the
    /// core `Vault::open`.
    pub fn new(db_path: String, key: Arc<VaultKey>) -> Result<Self, TransitionError> {
        Ok(Self {
            inner: core_vault::Vault::open(db_path, &key.inner)?,
        })
    }

    pub fn schema_version(&self) -> Result<u32, TransitionError> {
        self.inner.schema_version()
    }

    pub fn add_medication(
        &self,
        med: NewMedication,
        now_ms: i64,
    ) -> Result<Medication, TransitionError> {
        self.inner.add_medication(med, now_ms)
    }

    pub fn get_medication(&self, id: i64) -> Result<Option<Medication>, TransitionError> {
        self.inner.get_medication(id)
    }

    pub fn list_medications(
        &self,
        include_archived: bool,
    ) -> Result<Vec<Medication>, TransitionError> {
        self.inner.list_medications(include_archived)
    }

    pub fn set_medication_archived(
        &self,
        id: i64,
        archived: bool,
    ) -> Result<(), TransitionError> {
        self.inner.set_medication_archived(id, archived)
    }

    pub fn log_dose(&self, dose: NewDoseEvent) -> Result<DoseEvent, TransitionError> {
        self.inner.log_dose(dose)
    }

    pub fn list_doses(
        &self,
        medication_id: i64,
        offset: i64,
        limit: i64,
    ) -> Result<Vec<DoseEvent>, TransitionError> {
        self.inner.list_doses(medication_id, offset, limit)
    }

    pub fn suggest_next_injection_site(
        &self,
        medication_id: i64,
        history_depth: i64,
    ) -> Result<Option<String>, TransitionError> {
        self.inner.suggest_next_injection_site(medication_id, history_depth)
    }

    pub fn add_schedule(
        &self,
        schedule: NewDoseSchedule,
        now_ms: i64,
    ) -> Result<DoseSchedule, TransitionError> {
        self.inner.add_schedule(schedule, now_ms)
    }

    pub fn list_active_schedules(&self) -> Result<Vec<DoseSchedule>, TransitionError> {
        self.inner.list_active_schedules()
    }

    pub fn list_schedules_for_medication(
        &self,
        medication_id: i64,
        include_inactive: bool,
    ) -> Result<Vec<DoseSchedule>, TransitionError> {
        self.inner.list_schedules_for_medication(medication_id, include_inactive)
    }

    pub fn set_schedule_active(&self, id: i64, active: bool) -> Result<(), TransitionError> {
        self.inner.set_schedule_active(id, active)
    }

    pub fn set_schedule_next_due(
        &self,
        id: i64,
        next_due_at_ms: i64,
    ) -> Result<(), TransitionError> {
        self.inner.set_schedule_next_due(id, next_due_at_ms)
    }

    pub fn add_journal_entry(&self, entry: NewJournalEntry) -> Result<JournalEntry, TransitionError> {
        self.inner.add_journal_entry(entry)
    }

    pub fn list_journal_entries(&self, offset: i64, limit: i64) -> Result<Vec<JournalEntry>, TransitionError> {
        self.inner.list_journal_entries(offset, limit)
    }

    pub fn get_journal_entry(&self, id: i64) -> Result<Option<JournalEntry>, TransitionError> {
        self.inner.get_journal_entry(id)
    }

    pub fn delete_journal_entry(&self, id: i64) -> Result<(), TransitionError> {
        self.inner.delete_journal_entry(id)
    }

    pub fn add_hormone_measurement(
        &self,
        m: NewHormoneMeasurement,
    ) -> Result<HormoneMeasurement, TransitionError> {
        self.inner.add_hormone_measurement(m)
    }

    pub fn list_hormone_measurements(
        &self,
        hormone: String,
        offset: i64,
        limit: i64,
    ) -> Result<Vec<HormoneMeasurement>, TransitionError> {
        self.inner.list_hormone_measurements(hormone, offset, limit)
    }

    pub fn distinct_hormones(&self) -> Result<Vec<String>, TransitionError> {
        self.inner.distinct_hormones()
    }

    pub fn delete_hormone_measurement(&self, id: i64) -> Result<(), TransitionError> {
        self.inner.delete_hormone_measurement(id)
    }

    pub fn add_photo_record(
        &self,
        photo: NewPhotoRecord,
    ) -> Result<PhotoRecord, TransitionError> {
        self.inner.add_photo_record(photo)
    }

    pub fn list_photo_records(
        &self,
        offset: i64,
        limit: i64,
    ) -> Result<Vec<PhotoRecord>, TransitionError> {
        self.inner.list_photo_records(offset, limit)
    }

    pub fn delete_photo_record(&self, id: i64) -> Result<(), TransitionError> {
        self.inner.delete_photo_record(id)
    }

    pub fn add_voice_clip(&self, clip: NewVoiceClip) -> Result<VoiceClip, TransitionError> {
        self.inner.add_voice_clip(clip)
    }

    pub fn list_voice_clips(
        &self,
        offset: i64,
        limit: i64,
    ) -> Result<Vec<VoiceClip>, TransitionError> {
        self.inner.list_voice_clips(offset, limit)
    }

    pub fn delete_voice_clip(&self, id: String) -> Result<(), TransitionError> {
        self.inner.delete_voice_clip(id)
    }

    pub fn encrypt_blob(&self, plaintext: Vec<u8>) -> Result<Vec<u8>, TransitionError> {
        self.inner.encrypt_blob(plaintext)
    }

    pub fn decrypt_blob(&self, ciphertext: Vec<u8>) -> Result<Vec<u8>, TransitionError> {
        self.inner.decrypt_blob(ciphertext)
    }

    pub fn export_encrypted(&self, passphrase: String) -> Result<Vec<u8>, TransitionError> {
        self.inner.export_encrypted(passphrase)
    }
}
