//! Appointments / notes ("RDV").
//!
//! A lightweight, fully-encrypted record for medical appointments and the
//! notes attached to them: when and where, which professional, free-form
//! notes, and a "to-do / done" checklist kept as a single free-text field
//! (one item per line — the UI parses it). `reminder_at_ms` is optional and,
//! when set, the native side schedules a one-shot notification for it; the
//! appointment's identifying details never leave the vault.
//!
//! Edits are in place so the row id stays stable (it is the key the alarm and
//! any future metric values hang off). Modeled on [`crate::bleeding`].

use rusqlite::{params, Row};

use crate::db::Database;
use crate::TransitionError;

#[derive(Clone, Debug, PartialEq)]
pub struct Appointment {
    pub id: i64,
    /// When the appointment takes place (epoch ms).
    pub at_ms: i64,
    pub place: Option<String>,
    pub professional_name: Option<String>,
    pub professional_role: Option<String>,
    pub notes: Option<String>,
    /// Free-form "à faire / fait" checklist, one item per line.
    pub todo: Option<String>,
    /// When to fire a reminder notification (epoch ms), if any.
    pub reminder_at_ms: Option<i64>,
}

#[derive(Clone, Debug)]
pub struct NewAppointment {
    pub at_ms: i64,
    pub place: Option<String>,
    pub professional_name: Option<String>,
    pub professional_role: Option<String>,
    pub notes: Option<String>,
    pub todo: Option<String>,
    pub reminder_at_ms: Option<i64>,
}

pub fn add(db: &Database, a: NewAppointment) -> Result<Appointment, TransitionError> {
    db.conn()
        .execute(
            "INSERT INTO appointments
                (at_ms, place, professional_name, professional_role, notes, todo, reminder_at_ms)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
            params![
                a.at_ms,
                a.place,
                a.professional_name,
                a.professional_role,
                a.notes,
                a.todo,
                a.reminder_at_ms,
            ],
        )
        .map_err(map_sql)?;
    let id = db.conn().last_insert_rowid();
    Ok(Appointment {
        id,
        at_ms: a.at_ms,
        place: a.place,
        professional_name: a.professional_name,
        professional_role: a.professional_role,
        notes: a.notes,
        todo: a.todo,
        reminder_at_ms: a.reminder_at_ms,
    })
}

/// Page through appointments, soonest-future-or-most-recent first. We sort by
/// `at_ms DESC` to match every other domain; the UI groups upcoming vs. past.
pub fn list(db: &Database, offset: i64, limit: i64) -> Result<Vec<Appointment>, TransitionError> {
    let conn = db.conn();
    let mut stmt = conn
        .prepare(
            "SELECT id, at_ms, place, professional_name, professional_role, notes, todo, reminder_at_ms
             FROM appointments
             ORDER BY at_ms DESC LIMIT ?1 OFFSET ?2",
        )
        .map_err(map_sql)?;
    let rows = stmt
        .query_map(params![limit, offset], parse)
        .map_err(map_sql)?
        .collect::<Result<Vec<_>, _>>()
        .map_err(map_sql)?;
    Ok(rows)
}

pub fn get(db: &Database, id: i64) -> Result<Option<Appointment>, TransitionError> {
    db.conn()
        .query_row(
            "SELECT id, at_ms, place, professional_name, professional_role, notes, todo, reminder_at_ms
             FROM appointments WHERE id = ?1",
            [id],
            parse,
        )
        .map(Some)
        .or_else(|e| match e {
            rusqlite::Error::QueryReturnedNoRows => Ok(None),
            other => Err(map_sql(other)),
        })
}

pub fn update(db: &Database, id: i64, a: NewAppointment) -> Result<Appointment, TransitionError> {
    let n = db
        .conn()
        .execute(
            "UPDATE appointments SET
                at_ms = ?1, place = ?2, professional_name = ?3, professional_role = ?4,
                notes = ?5, todo = ?6, reminder_at_ms = ?7
             WHERE id = ?8",
            params![
                a.at_ms,
                a.place,
                a.professional_name,
                a.professional_role,
                a.notes,
                a.todo,
                a.reminder_at_ms,
                id,
            ],
        )
        .map_err(map_sql)?;
    if n == 0 {
        return Err(TransitionError::Database(format!(
            "no appointment with id {id}"
        )));
    }
    Ok(Appointment {
        id,
        at_ms: a.at_ms,
        place: a.place,
        professional_name: a.professional_name,
        professional_role: a.professional_role,
        notes: a.notes,
        todo: a.todo,
        reminder_at_ms: a.reminder_at_ms,
    })
}

pub fn delete(db: &Database, id: i64) -> Result<(), TransitionError> {
    db.conn()
        .execute("DELETE FROM appointments WHERE id = ?1", [id])
        .map_err(map_sql)?;
    Ok(())
}

fn parse(row: &Row) -> rusqlite::Result<Appointment> {
    Ok(Appointment {
        id: row.get(0)?,
        at_ms: row.get(1)?,
        place: row.get(2)?,
        professional_name: row.get(3)?,
        professional_role: row.get(4)?,
        notes: row.get(5)?,
        todo: row.get(6)?,
        reminder_at_ms: row.get(7)?,
    })
}

fn map_sql(err: rusqlite::Error) -> TransitionError {
    crate::sanitize_db_err(err)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::crypto::MasterKey;
    use tempfile::NamedTempFile;

    fn fresh_db() -> (NamedTempFile, Database) {
        let f = NamedTempFile::new().unwrap();
        let path = f.path().to_path_buf();
        drop(f);
        let _ = std::fs::remove_file(&path);
        let db = Database::open(&path, &MasterKey::generate()).unwrap();
        let owner = NamedTempFile::new_in(path.parent().unwrap()).unwrap();
        (owner, db)
    }

    fn sample() -> NewAppointment {
        NewAppointment {
            at_ms: 1_700_000_000_000,
            place: Some("Centre médical".into()),
            professional_name: Some("Dr Martin".into()),
            professional_role: Some("Endocrinologue".into()),
            notes: Some("Discuter dosage".into()),
            todo: Some("Prise de sang\nAppeler le labo".into()),
            reminder_at_ms: Some(1_699_990_000_000),
        }
    }

    #[test]
    fn add_list_get_update_delete() {
        let (_k, db) = fresh_db();
        let a = add(&db, sample()).unwrap();
        assert_eq!(list(&db, 0, 10).unwrap().len(), 1);
        assert_eq!(get(&db, a.id).unwrap().unwrap(), a);

        let updated = update(
            &db,
            a.id,
            NewAppointment {
                professional_name: Some("Dr Durand".into()),
                reminder_at_ms: None,
                ..sample()
            },
        )
        .unwrap();
        assert_eq!(updated.professional_name.as_deref(), Some("Dr Durand"));
        assert_eq!(updated.reminder_at_ms, None);
        assert_eq!(get(&db, a.id).unwrap().unwrap().reminder_at_ms, None);

        delete(&db, a.id).unwrap();
        assert!(list(&db, 0, 10).unwrap().is_empty());
    }

    #[test]
    fn update_missing_id_errors() {
        let (_k, db) = fresh_db();
        assert!(update(&db, 42, sample()).is_err());
    }
}
