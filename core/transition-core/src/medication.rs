//! Medication catalog + recorded doses + injection-site rotation helper.
//!
//! The module is data-oriented: every function takes a [`Database`] and
//! returns plain records. The [`Vault`] façade in `vault.rs` wraps these for
//! cross-FFI consumption.

use rusqlite::{params, Row};

use crate::db::Database;
use crate::TransitionError;

// -- Records --------------------------------------------------------------------

#[derive(Clone, Debug, PartialEq)]
pub struct Medication {
    pub id: i64,
    pub name: String,
    pub kind: String,
    pub route: String,
    pub default_dose: Option<f64>,
    pub default_dose_unit: Option<String>,
    /// Packed ARGB (0xAARRGGBB) hint for the UI.
    pub color: Option<i64>,
    pub notes: Option<String>,
    pub archived: bool,
    pub created_at_ms: i64,
}

#[derive(Clone, Debug)]
pub struct NewMedication {
    pub name: String,
    pub kind: String,
    pub route: String,
    pub default_dose: Option<f64>,
    pub default_dose_unit: Option<String>,
    pub color: Option<i64>,
    pub notes: Option<String>,
}

#[derive(Clone, Debug)]
pub struct MedicationUpdate {
    pub name: Option<String>,
    pub kind: Option<String>,
    pub route: Option<String>,
    pub default_dose: Option<Option<f64>>,
    pub default_dose_unit: Option<Option<String>>,
    pub color: Option<Option<i64>>,
    pub notes: Option<Option<String>>,
}

#[derive(Clone, Debug, PartialEq)]
pub struct DoseEvent {
    pub id: i64,
    pub medication_id: i64,
    pub taken_at_ms: i64,
    pub dose: Option<f64>,
    pub dose_unit: Option<String>,
    pub route: Option<String>,
    pub injection_site: Option<String>,
    pub notes: Option<String>,
    /// "taken" | "skipped" | "missed" | "delayed". Existing rows default to
    /// "taken" (migration 0009).
    pub status: String,
    /// When the dose was *due* (epoch ms), if it originated from a schedule.
    /// `taken_at_ms` is always the tap time, so this is what makes "late by N"
    /// computable rather than inferred.
    pub scheduled_at_ms: Option<i64>,
    /// Schedule this event belongs to, when known.
    pub schedule_id: Option<i64>,
}

#[derive(Clone, Debug)]
pub struct NewDoseEvent {
    pub medication_id: i64,
    pub taken_at_ms: i64,
    pub dose: Option<f64>,
    pub dose_unit: Option<String>,
    pub route: Option<String>,
    pub injection_site: Option<String>,
    pub notes: Option<String>,
    pub status: String,
    pub scheduled_at_ms: Option<i64>,
    pub schedule_id: Option<i64>,
}

// -- Medication CRUD ------------------------------------------------------------

pub fn add(db: &Database, m: NewMedication, now_ms: i64) -> Result<Medication, TransitionError> {
    db.conn()
        .execute(
            "INSERT INTO medications
                (name, kind, route, default_dose, default_dose_unit, color, notes, archived, created_at_ms)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, 0, ?8)",
            params![
                m.name, m.kind, m.route, m.default_dose, m.default_dose_unit,
                m.color, m.notes, now_ms,
            ],
        )
        .map_err(map_sql)?;
    let id = db.conn().last_insert_rowid();
    Ok(Medication {
        id,
        name: m.name,
        kind: m.kind,
        route: m.route,
        default_dose: m.default_dose,
        default_dose_unit: m.default_dose_unit,
        color: m.color,
        notes: m.notes,
        archived: false,
        created_at_ms: now_ms,
    })
}

pub fn get(db: &Database, id: i64) -> Result<Option<Medication>, TransitionError> {
    db.conn()
        .query_row("SELECT * FROM medications WHERE id = ?1", [id], parse_medication)
        .map(Some)
        .or_else(|e| match e {
            rusqlite::Error::QueryReturnedNoRows => Ok(None),
            other => Err(map_sql(other)),
        })
}

pub fn list(db: &Database, include_archived: bool) -> Result<Vec<Medication>, TransitionError> {
    let sql = if include_archived {
        "SELECT * FROM medications ORDER BY archived ASC, name ASC"
    } else {
        "SELECT * FROM medications WHERE archived = 0 ORDER BY name ASC"
    };
    let mut stmt = db.conn().prepare(sql).map_err(map_sql)?;
    let rows = stmt
        .query_map([], parse_medication)
        .map_err(map_sql)?
        .collect::<Result<Vec<_>, _>>()
        .map_err(map_sql)?;
    Ok(rows)
}

pub fn update(db: &Database, id: i64, upd: MedicationUpdate) -> Result<(), TransitionError> {
    // Manual COALESCE-style update: each field is Some(value) to overwrite,
    // None to leave untouched. Nested Option<Option<T>> for nullable columns
    // distinguishes "set to NULL" (Some(None)) from "leave alone" (None).
    db.conn()
        .execute(
            "UPDATE medications SET
                name              = COALESCE(?1, name),
                kind              = COALESCE(?2, kind),
                route             = COALESCE(?3, route),
                default_dose      = CASE WHEN ?4 = 1 THEN ?5 ELSE default_dose END,
                default_dose_unit = CASE WHEN ?6 = 1 THEN ?7 ELSE default_dose_unit END,
                color             = CASE WHEN ?8 = 1 THEN ?9 ELSE color END,
                notes             = CASE WHEN ?10 = 1 THEN ?11 ELSE notes END
             WHERE id = ?12",
            params![
                upd.name, upd.kind, upd.route,
                upd.default_dose.is_some() as i64, upd.default_dose.flatten(),
                upd.default_dose_unit.is_some() as i64, upd.default_dose_unit.flatten(),
                upd.color.is_some() as i64, upd.color.flatten(),
                upd.notes.is_some() as i64, upd.notes.flatten(),
                id,
            ],
        )
        .map_err(map_sql)?;
    Ok(())
}

pub fn set_archived(db: &Database, id: i64, archived: bool) -> Result<(), TransitionError> {
    db.conn()
        .execute(
            "UPDATE medications SET archived = ?1 WHERE id = ?2",
            params![archived as i64, id],
        )
        .map_err(map_sql)?;
    Ok(())
}

// -- DoseEvent log --------------------------------------------------------------

pub fn log_dose(db: &Database, d: NewDoseEvent) -> Result<DoseEvent, TransitionError> {
    db.conn()
        .execute(
            "INSERT INTO dose_events
                (medication_id, taken_at_ms, dose, dose_unit, route, injection_site, notes,
                 status, scheduled_at_ms, schedule_id)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10)",
            params![
                d.medication_id, d.taken_at_ms, d.dose, d.dose_unit,
                d.route, d.injection_site, d.notes,
                d.status, d.scheduled_at_ms, d.schedule_id,
            ],
        )
        .map_err(map_sql)?;
    let id = db.conn().last_insert_rowid();
    Ok(DoseEvent {
        id,
        medication_id: d.medication_id,
        taken_at_ms: d.taken_at_ms,
        dose: d.dose,
        dose_unit: d.dose_unit,
        route: d.route,
        injection_site: d.injection_site,
        notes: d.notes,
        status: d.status,
        scheduled_at_ms: d.scheduled_at_ms,
        schedule_id: d.schedule_id,
    })
}

/// Page through doses for `medication_id`, newest first.
pub fn list_doses(
    db: &Database,
    medication_id: i64,
    offset: i64,
    limit: i64,
) -> Result<Vec<DoseEvent>, TransitionError> {
    let mut stmt = db
        .conn()
        .prepare(
            "SELECT id, medication_id, taken_at_ms, dose, dose_unit, route, injection_site, notes,
                    status, scheduled_at_ms, schedule_id
             FROM dose_events
             WHERE medication_id = ?1
             ORDER BY taken_at_ms DESC
             LIMIT ?2 OFFSET ?3",
        )
        .map_err(map_sql)?;
    let rows = stmt
        .query_map(params![medication_id, limit, offset], parse_dose_event)
        .map_err(map_sql)?
        .collect::<Result<Vec<_>, _>>()
        .map_err(map_sql)?;
    Ok(rows)
}

/// All dose events across every medication in a time window, newest first.
/// Powers the dose↔mood correlation timeline, which needs taken doses, skips
/// and misses on one axis regardless of which medication they belong to.
pub fn list_dose_events_between(
    db: &Database,
    from_ms: i64,
    to_ms: i64,
) -> Result<Vec<DoseEvent>, TransitionError> {
    let mut stmt = db
        .conn()
        .prepare(
            "SELECT id, medication_id, taken_at_ms, dose, dose_unit, route, injection_site, notes,
                    status, scheduled_at_ms, schedule_id
             FROM dose_events
             WHERE taken_at_ms >= ?1 AND taken_at_ms <= ?2
             ORDER BY taken_at_ms DESC",
        )
        .map_err(map_sql)?;
    let rows = stmt
        .query_map(params![from_ms, to_ms], parse_dose_event)
        .map_err(map_sql)?
        .collect::<Result<Vec<_>, _>>()
        .map_err(map_sql)?;
    Ok(rows)
}

// -- Treatment-change audit -----------------------------------------------------

#[derive(Clone, Debug, PartialEq)]
pub struct TreatmentChange {
    pub id: i64,
    pub medication_id: i64,
    pub at_ms: i64,
    pub field: String,
    pub old_value: Option<String>,
    pub new_value: Option<String>,
    pub note: Option<String>,
}

#[derive(Clone, Debug)]
pub struct NewTreatmentChange {
    pub medication_id: i64,
    pub at_ms: i64,
    pub field: String,
    pub old_value: Option<String>,
    pub new_value: Option<String>,
    pub note: Option<String>,
}

pub fn log_treatment_change(
    db: &Database,
    c: NewTreatmentChange,
) -> Result<TreatmentChange, TransitionError> {
    db.conn()
        .execute(
            "INSERT INTO treatment_changes
                (medication_id, at_ms, field, old_value, new_value, note)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
            params![c.medication_id, c.at_ms, c.field, c.old_value, c.new_value, c.note],
        )
        .map_err(map_sql)?;
    let id = db.conn().last_insert_rowid();
    Ok(TreatmentChange {
        id,
        medication_id: c.medication_id,
        at_ms: c.at_ms,
        field: c.field,
        old_value: c.old_value,
        new_value: c.new_value,
        note: c.note,
    })
}

/// Treatment changes across all medications in a time window, newest first.
pub fn list_treatment_changes(
    db: &Database,
    from_ms: i64,
    to_ms: i64,
) -> Result<Vec<TreatmentChange>, TransitionError> {
    let mut stmt = db
        .conn()
        .prepare(
            "SELECT id, medication_id, at_ms, field, old_value, new_value, note
             FROM treatment_changes
             WHERE at_ms >= ?1 AND at_ms <= ?2
             ORDER BY at_ms DESC",
        )
        .map_err(map_sql)?;
    let rows = stmt
        .query_map(params![from_ms, to_ms], |row| {
            Ok(TreatmentChange {
                id: row.get(0)?,
                medication_id: row.get(1)?,
                at_ms: row.get(2)?,
                field: row.get(3)?,
                old_value: row.get(4)?,
                new_value: row.get(5)?,
                note: row.get(6)?,
            })
        })
        .map_err(map_sql)?
        .collect::<Result<Vec<_>, _>>()
        .map_err(map_sql)?;
    Ok(rows)
}

// -- Injection-site rotation ----------------------------------------------------

pub mod injection {
    use super::*;

    /// Standard rotation set. Stored as snake_case identifiers; the UI maps
    /// each to a localised label.
    pub const STANDARD_SITES: &[&str] = &[
        "thigh_left",
        "thigh_right",
        "abdomen_left_upper",
        "abdomen_right_upper",
        "abdomen_left_lower",
        "abdomen_right_lower",
        "glute_left",
        "glute_right",
        "deltoid_left",
        "deltoid_right",
    ];

    /// Suggest the next site to use, based on which of `candidates` has been
    /// the longest unused given the recent history.
    ///
    /// `recent_sites` is ordered newest-first (index 0 = most recent dose).
    /// Each candidate is scored by its position in `recent_sites`; sites that
    /// never appear in the history win. Ties are broken by the order in
    /// `candidates` to keep the suggestion deterministic.
    pub fn suggest_next_site(recent_sites: &[String], candidates: &[&str]) -> Option<String> {
        if candidates.is_empty() {
            return None;
        }
        candidates
            .iter()
            .enumerate()
            .map(|(idx, c)| {
                let recency = recent_sites
                    .iter()
                    .position(|s| s == c)
                    .map(|i| i as i64)
                    .unwrap_or(i64::MAX);
                (recency, idx, c.to_string())
            })
            // Lexicographic max on (recency, -idx) so higher recency wins, and
            // among equals the earliest candidate in the list wins.
            .max_by(|(ar, ai, _), (br, bi, _)| ar.cmp(br).then(bi.cmp(ai)))
            .map(|(_, _, s)| s)
    }

    /// Convenience: pull the latest N injection sites for a medication and
    /// suggest the next one from `STANDARD_SITES`.
    pub fn next_site_for(
        db: &Database,
        medication_id: i64,
        history_depth: i64,
    ) -> Result<Option<String>, TransitionError> {
        let mut stmt = db
            .conn()
            .prepare(
                "SELECT injection_site FROM dose_events
                 WHERE medication_id = ?1 AND injection_site IS NOT NULL
                 ORDER BY taken_at_ms DESC LIMIT ?2",
            )
            .map_err(map_sql)?;
        let recents: Vec<String> = stmt
            .query_map(params![medication_id, history_depth], |row| row.get::<_, String>(0))
            .map_err(map_sql)?
            .collect::<Result<_, _>>()
            .map_err(map_sql)?;
        Ok(suggest_next_site(&recents, STANDARD_SITES))
    }
}

// -- Row parsers ----------------------------------------------------------------

fn parse_medication(row: &Row) -> rusqlite::Result<Medication> {
    Ok(Medication {
        id: row.get("id")?,
        name: row.get("name")?,
        kind: row.get("kind")?,
        route: row.get("route")?,
        default_dose: row.get("default_dose")?,
        default_dose_unit: row.get("default_dose_unit")?,
        color: row.get("color")?,
        notes: row.get("notes")?,
        archived: row.get::<_, i64>("archived")? != 0,
        created_at_ms: row.get("created_at_ms")?,
    })
}

fn parse_dose_event(row: &Row) -> rusqlite::Result<DoseEvent> {
    Ok(DoseEvent {
        id: row.get(0)?,
        medication_id: row.get(1)?,
        taken_at_ms: row.get(2)?,
        dose: row.get(3)?,
        dose_unit: row.get(4)?,
        route: row.get(5)?,
        injection_site: row.get(6)?,
        notes: row.get(7)?,
        status: row.get(8)?,
        scheduled_at_ms: row.get(9)?,
        schedule_id: row.get(10)?,
    })
}

fn map_sql(err: rusqlite::Error) -> TransitionError {
    crate::sanitize_db_err(err)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::crypto::MasterKey;
    use crate::db::Database;
    use tempfile::NamedTempFile;

    fn fresh_db() -> (tempfile::NamedTempFile, Database) {
        let f = NamedTempFile::new().unwrap();
        let path = f.path().to_path_buf();
        drop(f);
        let _ = std::fs::remove_file(&path);
        let key = MasterKey::generate();
        let db = Database::open(&path, &key).unwrap();
        // Return a fresh tempfile that owns the path so it cleans up at the
        // end of the test.
        let owner = tempfile::NamedTempFile::new_in(path.parent().unwrap()).unwrap();
        (owner, db)
    }

    fn sample_medication(name: &str) -> NewMedication {
        NewMedication {
            name: name.into(),
            kind: "estrogen".into(),
            route: "transdermal".into(),
            default_dose: Some(0.1),
            default_dose_unit: Some("mg".into()),
            color: Some(0xFF_6750_A4),
            notes: None,
        }
    }

    #[test]
    fn add_and_list_medications() {
        let (_keepalive, db) = fresh_db();
        let m1 = add(&db, sample_medication("Estradiol gel"), 1_000).unwrap();
        let m2 = add(
            &db,
            NewMedication {
                name: "Bicalutamide".into(),
                kind: "anti_androgen".into(),
                route: "oral".into(),
                default_dose: Some(50.0),
                default_dose_unit: Some("mg".into()),
                color: None,
                notes: None,
            },
            2_000,
        )
        .unwrap();

        let all = list(&db, false).unwrap();
        assert_eq!(all.len(), 2);
        // Sorted alphabetically.
        assert_eq!(all[0].name, "Bicalutamide");
        assert_eq!(all[1].name, "Estradiol gel");
        assert_eq!(get(&db, m1.id).unwrap().unwrap(), m1);
        assert_eq!(get(&db, m2.id).unwrap().unwrap(), m2);
    }

    #[test]
    fn archived_meds_are_hidden_by_default() {
        let (_k, db) = fresh_db();
        let m = add(&db, sample_medication("Old patch"), 1_000).unwrap();
        set_archived(&db, m.id, true).unwrap();
        assert!(list(&db, false).unwrap().is_empty());
        assert_eq!(list(&db, true).unwrap().len(), 1);
    }

    #[test]
    fn update_overwrites_only_provided_fields() {
        let (_k, db) = fresh_db();
        let m = add(&db, sample_medication("Gel"), 1_000).unwrap();
        update(
            &db,
            m.id,
            MedicationUpdate {
                name: Some("Estradiol patch".into()),
                kind: None,
                route: Some("transdermal".into()),
                default_dose: Some(Some(0.05)),
                default_dose_unit: None,
                color: Some(None), // explicitly clear
                notes: Some(Some("Cut into halves".into())),
            },
        )
        .unwrap();
        let after = get(&db, m.id).unwrap().unwrap();
        assert_eq!(after.name, "Estradiol patch");
        assert_eq!(after.kind, "estrogen"); // untouched
        assert_eq!(after.default_dose, Some(0.05));
        assert_eq!(after.default_dose_unit, Some("mg".into())); // untouched
        assert_eq!(after.color, None);
        assert_eq!(after.notes.as_deref(), Some("Cut into halves"));
    }

    #[test]
    fn log_dose_and_paginate_history() {
        let (_k, db) = fresh_db();
        let m = add(&db, sample_medication("Estradiol injection"), 1_000).unwrap();
        for i in 0..5 {
            log_dose(
                &db,
                NewDoseEvent {
                    medication_id: m.id,
                    taken_at_ms: 10_000 + i * 1_000,
                    dose: Some(5.0),
                    dose_unit: Some("mg".into()),
                    route: Some("injection_im".into()),
                    injection_site: Some(format!("thigh_{}", if i % 2 == 0 { "left" } else { "right" })),
                    notes: None,
                    status: "taken".into(),
                    scheduled_at_ms: None,
                    schedule_id: None,
                },
            )
            .unwrap();
        }
        let page1 = list_doses(&db, m.id, 0, 3).unwrap();
        assert_eq!(page1.len(), 3);
        // Newest first.
        assert_eq!(page1[0].taken_at_ms, 14_000);
        assert_eq!(page1[2].taken_at_ms, 12_000);

        let page2 = list_doses(&db, m.id, 3, 3).unwrap();
        assert_eq!(page2.len(), 2);
        assert_eq!(page2[0].taken_at_ms, 11_000);
    }

    #[test]
    fn dose_event_cascades_when_medication_deleted() {
        let (_k, db) = fresh_db();
        let m = add(&db, sample_medication("Tmp"), 1_000).unwrap();
        log_dose(
            &db,
            NewDoseEvent {
                medication_id: m.id,
                taken_at_ms: 10_000,
                dose: None,
                dose_unit: None,
                route: None,
                injection_site: None,
                notes: None,
                status: "taken".into(),
                scheduled_at_ms: None,
                schedule_id: None,
            },
        )
        .unwrap();
        // PRAGMA foreign_keys must be enabled for cascades; rusqlite enables
        // it via Connection but we double-check the cascade actually happens.
        db.conn().execute("DELETE FROM medications WHERE id = ?1", [m.id]).unwrap();
        assert!(list_doses(&db, m.id, 0, 10).unwrap().is_empty());
    }

    #[test]
    fn suggest_next_site_picks_unused_sites_first() {
        let recents = vec!["thigh_left".to_string(), "thigh_right".to_string()];
        let pick = injection::suggest_next_site(
            &recents,
            &["thigh_left", "thigh_right", "abdomen_left_upper"],
        );
        assert_eq!(pick.as_deref(), Some("abdomen_left_upper"));
    }

    #[test]
    fn suggest_next_site_falls_back_to_least_recent() {
        // Newest at index 0.
        let recents = vec![
            "thigh_left".to_string(),
            "thigh_right".to_string(),
            "abdomen_left_upper".to_string(),
        ];
        let pick = injection::suggest_next_site(
            &recents,
            &["thigh_left", "thigh_right", "abdomen_left_upper"],
        );
        // abdomen_left_upper has the highest recency index (2), so it wins.
        assert_eq!(pick.as_deref(), Some("abdomen_left_upper"));
    }

    #[test]
    fn suggest_next_site_with_empty_history() {
        let pick = injection::suggest_next_site(&[], &["thigh_left", "thigh_right"]);
        assert_eq!(pick.as_deref(), Some("thigh_left"));
    }

    #[test]
    fn suggest_next_site_with_empty_candidates() {
        let recents = vec!["thigh_left".to_string()];
        assert!(injection::suggest_next_site(&recents, &[]).is_none());
    }

    #[test]
    fn next_site_for_uses_db_history() {
        let (_k, db) = fresh_db();
        let m = add(&db, sample_medication("Estradiol injection"), 1_000).unwrap();
        for (t, site) in [
            (10_000, "thigh_left"),
            (11_000, "thigh_right"),
            (12_000, "thigh_left"),
        ] {
            log_dose(
                &db,
                NewDoseEvent {
                    medication_id: m.id,
                    taken_at_ms: t,
                    dose: None,
                    dose_unit: None,
                    route: None,
                    injection_site: Some(site.into()),
                    notes: None,
                    status: "taken".into(),
                    scheduled_at_ms: None,
                    schedule_id: None,
                },
            )
            .unwrap();
        }
        // History (newest first): thigh_left, thigh_right, thigh_left.
        // thigh_right is older than thigh_left, but anything else in the
        // standard set is unused → wins.
        let pick = injection::next_site_for(&db, m.id, 10).unwrap();
        assert_eq!(pick.as_deref(), Some("abdomen_left_upper"));
    }
}
