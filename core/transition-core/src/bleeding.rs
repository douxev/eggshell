//! Bleeding / cycle tracking.
//!
//! Deliberately neutral: a [`BleedingEntry`] records a timestamped bleed or
//! spotting event plus optional notes. The slider values (flow/pain/cramps and
//! any user-defined slider) live in the shared `metric_values` table
//! (`entry_domain = "bleeding"`, see [`crate::metrics`]). Edits are in place so
//! the entry id stays stable for its metric values.

use rusqlite::{params, Row};

use crate::db::Database;
use crate::TransitionError;

pub const DOMAIN: &str = "bleeding";

#[derive(Clone, Debug, PartialEq)]
pub struct BleedingEntry {
    pub id: i64,
    pub at_ms: i64,
    /// `Some(true)` = spotting/breakthrough, `Some(false)` = a full bleed,
    /// `None` = unspecified.
    pub is_spotting: Option<bool>,
    pub free_text: Option<String>,
}

#[derive(Clone, Debug)]
pub struct NewBleedingEntry {
    pub at_ms: i64,
    pub is_spotting: Option<bool>,
    pub free_text: Option<String>,
}

pub fn add(db: &Database, e: NewBleedingEntry) -> Result<BleedingEntry, TransitionError> {
    db.conn()
        .execute(
            "INSERT INTO bleeding_entries (at_ms, is_spotting, free_text) VALUES (?1, ?2, ?3)",
            params![e.at_ms, e.is_spotting.map(|b| b as i64), e.free_text],
        )
        .map_err(map_sql)?;
    let id = db.conn().last_insert_rowid();
    Ok(BleedingEntry {
        id,
        at_ms: e.at_ms,
        is_spotting: e.is_spotting,
        free_text: e.free_text,
    })
}

pub fn list(db: &Database, offset: i64, limit: i64) -> Result<Vec<BleedingEntry>, TransitionError> {
    let conn = db.conn();
    let mut stmt = conn
        .prepare(
            "SELECT id, at_ms, is_spotting, free_text FROM bleeding_entries
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

pub fn get(db: &Database, id: i64) -> Result<Option<BleedingEntry>, TransitionError> {
    db.conn()
        .query_row(
            "SELECT id, at_ms, is_spotting, free_text FROM bleeding_entries WHERE id = ?1",
            [id],
            parse,
        )
        .map(Some)
        .or_else(|e| match e {
            rusqlite::Error::QueryReturnedNoRows => Ok(None),
            other => Err(map_sql(other)),
        })
}

pub fn update(db: &Database, id: i64, e: NewBleedingEntry) -> Result<BleedingEntry, TransitionError> {
    let n = db
        .conn()
        .execute(
            "UPDATE bleeding_entries SET at_ms = ?1, is_spotting = ?2, free_text = ?3 WHERE id = ?4",
            params![e.at_ms, e.is_spotting.map(|b| b as i64), e.free_text, id],
        )
        .map_err(map_sql)?;
    if n == 0 {
        return Err(TransitionError::Database(format!(
            "no bleeding entry with id {id}"
        )));
    }
    Ok(BleedingEntry {
        id,
        at_ms: e.at_ms,
        is_spotting: e.is_spotting,
        free_text: e.free_text,
    })
}

pub fn delete(db: &Database, id: i64) -> Result<(), TransitionError> {
    db.conn()
        .execute("DELETE FROM bleeding_entries WHERE id = ?1", [id])
        .map_err(map_sql)?;
    // Drop its slider values too — there is no FK from metric_values to a
    // bleeding entry (entry_id is polymorphic), so clean up explicitly.
    db.conn()
        .execute(
            "DELETE FROM metric_values WHERE entry_domain = ?1 AND entry_id = ?2",
            params![DOMAIN, id],
        )
        .map_err(map_sql)?;
    Ok(())
}

fn parse(row: &Row) -> rusqlite::Result<BleedingEntry> {
    Ok(BleedingEntry {
        id: row.get(0)?,
        at_ms: row.get(1)?,
        is_spotting: row.get::<_, Option<i64>>(2)?.map(|v| v != 0),
        free_text: row.get(3)?,
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

    #[test]
    fn add_list_update_delete() {
        let (_k, db) = fresh_db();
        let e = add(
            &db,
            NewBleedingEntry { at_ms: 1_000, is_spotting: Some(true), free_text: Some("light".into()) },
        )
        .unwrap();
        assert_eq!(list(&db, 0, 10).unwrap().len(), 1);
        let updated = update(
            &db,
            e.id,
            NewBleedingEntry { at_ms: 1_000, is_spotting: Some(false), free_text: None },
        )
        .unwrap();
        assert_eq!(updated.is_spotting, Some(false));
        assert_eq!(get(&db, e.id).unwrap().unwrap().is_spotting, Some(false));
        delete(&db, e.id).unwrap();
        assert!(list(&db, 0, 10).unwrap().is_empty());
    }
}
