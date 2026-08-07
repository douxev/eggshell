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
pub use transition_core::medication::{
    DoseEvent, Medication, NewDoseEvent, NewMedication, NewTreatmentChange, TreatmentChange,
};
pub use transition_core::dose_schedule::{DoseSchedule, NewDoseSchedule};
pub use transition_core::hormones::{HormoneMeasurement, NewHormoneMeasurement};
pub use transition_core::journal::{JournalEntry, NewJournalEntry};
pub use transition_core::metrics::{
    MetricDefinition, MetricDefinitionUpdate, MetricValue, NewMetricDefinition,
};
pub use transition_core::bleeding::{BleedingEntry, NewBleedingEntry};
pub use transition_core::appointments::{Appointment, NewAppointment};
pub use transition_core::insights::{Insight, Strength, Valence};
pub use transition_core::dreams::{
    Dream, DreamAudio, DreamTag, NewDream, NewDreamAudio,
};
pub use transition_core::notes::{
    NewNote, NewNoteFolder, NewNoteImage, Note, NoteFolder, NoteImage,
};
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

    pub fn update_medication(&self, id: i64, med: NewMedication) -> Result<(), TransitionError> {
        self.inner.update_medication(id, med)
    }

    pub fn delete_medication(&self, id: i64) -> Result<(), TransitionError> {
        self.inner.delete_medication(id)
    }

    pub fn log_treatment_change(
        &self,
        change: NewTreatmentChange,
    ) -> Result<TreatmentChange, TransitionError> {
        self.inner.log_treatment_change(change)
    }

    pub fn list_treatment_changes(
        &self,
        from_ms: i64,
        to_ms: i64,
    ) -> Result<Vec<TreatmentChange>, TransitionError> {
        self.inner.list_treatment_changes(from_ms, to_ms)
    }

    pub fn log_dose(&self, dose: NewDoseEvent) -> Result<DoseEvent, TransitionError> {
        self.inner.log_dose(dose)
    }

    pub fn log_doses(&self, doses: Vec<NewDoseEvent>) -> Result<Vec<DoseEvent>, TransitionError> {
        self.inner.log_doses(doses)
    }

    pub fn update_dose(&self, id: i64, dose: NewDoseEvent) -> Result<DoseEvent, TransitionError> {
        self.inner.update_dose(id, dose)
    }

    pub fn get_dose(&self, id: i64) -> Result<Option<DoseEvent>, TransitionError> {
        self.inner.get_dose(id)
    }

    pub fn list_doses(
        &self,
        medication_id: i64,
        offset: i64,
        limit: i64,
    ) -> Result<Vec<DoseEvent>, TransitionError> {
        self.inner.list_doses(medication_id, offset, limit)
    }

    pub fn list_dose_events_between(
        &self,
        from_ms: i64,
        to_ms: i64,
    ) -> Result<Vec<DoseEvent>, TransitionError> {
        self.inner.list_dose_events_between(from_ms, to_ms)
    }

    pub fn delete_dose(&self, id: i64) -> Result<(), TransitionError> {
        self.inner.delete_dose(id)
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

    pub fn update_schedule(
        &self,
        id: i64,
        schedule: NewDoseSchedule,
    ) -> Result<DoseSchedule, TransitionError> {
        self.inner.update_schedule(id, schedule)
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

    pub fn delete_schedule(&self, id: i64) -> Result<(), TransitionError> {
        self.inner.delete_schedule(id)
    }

    pub fn add_journal_entry(&self, entry: NewJournalEntry) -> Result<JournalEntry, TransitionError> {
        self.inner.add_journal_entry(entry)
    }

    pub fn update_journal_entry(
        &self,
        id: i64,
        entry: NewJournalEntry,
    ) -> Result<JournalEntry, TransitionError> {
        self.inner.update_journal_entry(id, entry)
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

    pub fn list_metric_definitions(
        &self,
        domain: String,
        include_archived: bool,
    ) -> Result<Vec<MetricDefinition>, TransitionError> {
        self.inner.list_metric_definitions(domain, include_archived)
    }

    pub fn add_metric_definition(
        &self,
        def: NewMetricDefinition,
    ) -> Result<MetricDefinition, TransitionError> {
        self.inner.add_metric_definition(def)
    }

    pub fn update_metric_definition(
        &self,
        id: i64,
        upd: MetricDefinitionUpdate,
    ) -> Result<(), TransitionError> {
        self.inner.update_metric_definition(id, upd)
    }

    pub fn archive_metric_definition(&self, id: i64) -> Result<(), TransitionError> {
        self.inner.archive_metric_definition(id)
    }

    pub fn list_metric_values(
        &self,
        entry_domain: String,
        entry_id: i64,
    ) -> Result<Vec<MetricValue>, TransitionError> {
        self.inner.list_metric_values(entry_domain, entry_id)
    }

    pub fn replace_metric_values(
        &self,
        entry_domain: String,
        entry_id: i64,
        values: Vec<MetricValue>,
    ) -> Result<(), TransitionError> {
        self.inner.replace_metric_values(entry_domain, entry_id, values)
    }

    pub fn add_bleeding_entry(
        &self,
        entry: NewBleedingEntry,
    ) -> Result<BleedingEntry, TransitionError> {
        self.inner.add_bleeding_entry(entry)
    }

    pub fn add_bleeding_entries(
        &self,
        entries: Vec<NewBleedingEntry>,
    ) -> Result<Vec<BleedingEntry>, TransitionError> {
        self.inner.add_bleeding_entries(entries)
    }

    pub fn list_bleeding_entries(
        &self,
        offset: i64,
        limit: i64,
    ) -> Result<Vec<BleedingEntry>, TransitionError> {
        self.inner.list_bleeding_entries(offset, limit)
    }

    pub fn get_bleeding_entry(&self, id: i64) -> Result<Option<BleedingEntry>, TransitionError> {
        self.inner.get_bleeding_entry(id)
    }

    pub fn update_bleeding_entry(
        &self,
        id: i64,
        entry: NewBleedingEntry,
    ) -> Result<BleedingEntry, TransitionError> {
        self.inner.update_bleeding_entry(id, entry)
    }

    pub fn delete_bleeding_entry(&self, id: i64) -> Result<(), TransitionError> {
        self.inner.delete_bleeding_entry(id)
    }

    pub fn insights(
        &self,
        from_ms: i64,
        to_ms: i64,
        day_starts_ms: Vec<i64>,
    ) -> Result<Vec<Insight>, TransitionError> {
        self.inner.insights(from_ms, to_ms, day_starts_ms)
    }

    // -- Dreams ------------------------------------------------------------

    pub fn add_dream(&self, dream: NewDream) -> Result<Dream, TransitionError> {
        self.inner.add_dream(dream)
    }

    pub fn get_dream(&self, id: i64) -> Result<Option<Dream>, TransitionError> {
        self.inner.get_dream(id)
    }

    pub fn list_dreams(
        &self,
        tag_id: Option<i64>,
        limit: i64,
        offset: i64,
    ) -> Result<Vec<Dream>, TransitionError> {
        self.inner.list_dreams(tag_id, limit, offset)
    }

    pub fn list_dreams_between(&self, from_ms: i64, to_ms: i64) -> Result<Vec<Dream>, TransitionError> {
        self.inner.list_dreams_between(from_ms, to_ms)
    }

    pub fn update_dream(
        &self,
        id: i64,
        night_ms: i64,
        title: String,
        body: String,
        lucid: bool,
        updated_ms: i64,
    ) -> Result<Dream, TransitionError> {
        self.inner.update_dream(id, night_ms, title, body, lucid, updated_ms)
    }

    pub fn delete_dream(&self, id: i64) -> Result<(), TransitionError> {
        self.inner.delete_dream(id)
    }

    pub fn add_dream_tag(
        &self,
        label: String,
        color: Option<i64>,
        created_ms: i64,
    ) -> Result<DreamTag, TransitionError> {
        self.inner.add_dream_tag(label, color, created_ms)
    }

    pub fn list_dream_tags(&self) -> Result<Vec<DreamTag>, TransitionError> {
        self.inner.list_dream_tags()
    }

    pub fn rename_dream_tag(&self, id: i64, label: String) -> Result<(), TransitionError> {
        self.inner.rename_dream_tag(id, label)
    }

    pub fn delete_dream_tag(&self, id: i64) -> Result<(), TransitionError> {
        self.inner.delete_dream_tag(id)
    }

    pub fn tag_dream(&self, dream_id: i64, tag_id: i64) -> Result<(), TransitionError> {
        self.inner.tag_dream(dream_id, tag_id)
    }

    pub fn untag_dream(&self, dream_id: i64, tag_id: i64) -> Result<(), TransitionError> {
        self.inner.untag_dream(dream_id, tag_id)
    }

    pub fn tags_for_dream(&self, dream_id: i64) -> Result<Vec<DreamTag>, TransitionError> {
        self.inner.tags_for_dream(dream_id)
    }

    pub fn add_dream_audio(&self, audio: NewDreamAudio) -> Result<DreamAudio, TransitionError> {
        self.inner.add_dream_audio(audio)
    }

    pub fn dream_audio(&self, dream_id: i64) -> Result<Vec<DreamAudio>, TransitionError> {
        self.inner.dream_audio(dream_id)
    }

    pub fn set_dream_transcript(
        &self,
        audio_id: i64,
        transcript: Option<String>,
    ) -> Result<(), TransitionError> {
        self.inner.set_dream_transcript(audio_id, transcript)
    }

    pub fn delete_dream_audio(&self, audio_id: i64) -> Result<(), TransitionError> {
        self.inner.delete_dream_audio(audio_id)
    }

    pub fn all_dream_audio_paths(&self) -> Result<Vec<String>, TransitionError> {
        self.inner.all_dream_audio_paths()
    }

    pub fn add_note(&self, note: NewNote) -> Result<Note, TransitionError> {
        self.inner.add_note(note)
    }

    pub fn list_notes(&self, folder_id: Option<i64>) -> Result<Vec<Note>, TransitionError> {
        self.inner.list_notes(folder_id)
    }

    pub fn move_note_to_folder(&self, id: i64, folder_id: Option<i64>) -> Result<(), TransitionError> {
        self.inner.move_note_to_folder(id, folder_id)
    }

    pub fn add_note_folder(&self, folder: NewNoteFolder) -> Result<NoteFolder, TransitionError> {
        self.inner.add_note_folder(folder)
    }

    pub fn list_note_folders(&self, parent_id: Option<i64>) -> Result<Vec<NoteFolder>, TransitionError> {
        self.inner.list_note_folders(parent_id)
    }

    pub fn rename_note_folder(&self, id: i64, name: String) -> Result<(), TransitionError> {
        self.inner.rename_note_folder(id, name)
    }

    pub fn note_folder_contents_count(&self, id: i64) -> Result<i64, TransitionError> {
        self.inner.note_folder_contents_count(id)
    }

    pub fn note_image_paths_under_folder(&self, id: i64) -> Result<Vec<String>, TransitionError> {
        self.inner.note_image_paths_under_folder(id)
    }

    pub fn delete_note_folder(&self, id: i64) -> Result<(), TransitionError> {
        self.inner.delete_note_folder(id)
    }

    pub fn get_note(&self, id: i64) -> Result<Option<Note>, TransitionError> {
        self.inner.get_note(id)
    }

    pub fn update_note(
        &self,
        id: i64,
        title: String,
        body: String,
        updated_ms: i64,
    ) -> Result<Note, TransitionError> {
        self.inner.update_note(id, title, body, updated_ms)
    }

    pub fn delete_note(&self, id: i64) -> Result<(), TransitionError> {
        self.inner.delete_note(id)
    }

    pub fn reorder_notes(&self, ids_in_order: Vec<i64>) -> Result<(), TransitionError> {
        self.inner.reorder_notes(ids_in_order)
    }

    pub fn add_note_image(&self, img: NewNoteImage) -> Result<NoteImage, TransitionError> {
        self.inner.add_note_image(img)
    }

    pub fn note_images(&self, note_id: i64) -> Result<Vec<NoteImage>, TransitionError> {
        self.inner.note_images(note_id)
    }

    pub fn all_note_image_paths(&self) -> Result<Vec<String>, TransitionError> {
        self.inner.all_note_image_paths()
    }

    pub fn delete_note_image(&self, id: i64) -> Result<(), TransitionError> {
        self.inner.delete_note_image(id)
    }

    pub fn add_appointment(&self, appt: NewAppointment) -> Result<Appointment, TransitionError> {
        self.inner.add_appointment(appt)
    }

    pub fn list_appointments(
        &self,
        offset: i64,
        limit: i64,
    ) -> Result<Vec<Appointment>, TransitionError> {
        self.inner.list_appointments(offset, limit)
    }

    pub fn get_appointment(&self, id: i64) -> Result<Option<Appointment>, TransitionError> {
        self.inner.get_appointment(id)
    }

    pub fn update_appointment(
        &self,
        id: i64,
        appt: NewAppointment,
    ) -> Result<Appointment, TransitionError> {
        self.inner.update_appointment(id, appt)
    }

    pub fn delete_appointment(&self, id: i64) -> Result<(), TransitionError> {
        self.inner.delete_appointment(id)
    }

    pub fn get_setting(&self, key: String) -> Result<Option<String>, TransitionError> {
        self.inner.get_setting(key)
    }

    pub fn set_setting(&self, key: String, value: String) -> Result<(), TransitionError> {
        self.inner.set_setting(key, value)
    }

    pub fn delete_setting(&self, key: String) -> Result<(), TransitionError> {
        self.inner.delete_setting(key)
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
