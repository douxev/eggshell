//! Progress photo metadata.
//!
//! The encrypted bytes live as opaque blobs on disk (the native layer writes
//! them via the [`Vault::encrypt_blob`] / `decrypt_blob` helpers). This module
//! only manages the index.

use rusqlite::{params, Row};

use crate::db::Database;
use crate::TransitionError;

#[derive(Clone, Debug, PartialEq)]
pub struct PhotoRecord {
    pub id: i64,
    pub at_ms: i64,
    pub category: Option<String>,
    pub file_path: String,
    pub notes: Option<String>,
}

#[derive(Clone, Debug)]
pub struct NewPhotoRecord {
    pub at_ms: i64,
    pub category: Option<String>,
    pub file_path: String,
    pub notes: Option<String>,
}

pub fn add(db: &Database, p: NewPhotoRecord) -> Result<PhotoRecord, TransitionError> {
    db.conn()
        .execute(
            "INSERT INTO photo_records (at_ms, category, file_path, notes)
             VALUES (?1, ?2, ?3, ?4)",
            params![p.at_ms, p.category, p.file_path, p.notes],
        )
        .map_err(map_sql)?;
    let id = db.conn().last_insert_rowid();
    Ok(PhotoRecord {
        id,
        at_ms: p.at_ms,
        category: p.category,
        file_path: p.file_path,
        notes: p.notes,
    })
}

pub fn list(db: &Database, offset: i64, limit: i64) -> Result<Vec<PhotoRecord>, TransitionError> {
    let conn = db.conn();
    let mut stmt = conn
        .prepare(
            "SELECT id, at_ms, category, file_path, notes
             FROM photo_records ORDER BY at_ms DESC LIMIT ?1 OFFSET ?2",
        )
        .map_err(map_sql)?;
    let rows = stmt
        .query_map(params![limit, offset], parse)
        .map_err(map_sql)?
        .collect::<Result<Vec<_>, _>>()
        .map_err(map_sql)?;
    Ok(rows)
}

pub fn get(db: &Database, id: i64) -> Result<Option<PhotoRecord>, TransitionError> {
    db.conn()
        .query_row(
            "SELECT id, at_ms, category, file_path, notes FROM photo_records WHERE id = ?1",
            [id],
            parse,
        )
        .map(Some)
        .or_else(|e| match e {
            rusqlite::Error::QueryReturnedNoRows => Ok(None),
            other => Err(map_sql(other)),
        })
}

pub fn delete(db: &Database, id: i64) -> Result<(), TransitionError> {
    db.conn()
        .execute("DELETE FROM photo_records WHERE id = ?1", [id])
        .map_err(map_sql)?;
    Ok(())
}

fn parse(row: &Row) -> rusqlite::Result<PhotoRecord> {
    Ok(PhotoRecord {
        id: row.get(0)?,
        at_ms: row.get(1)?,
        category: row.get(2)?,
        file_path: row.get(3)?,
        notes: row.get(4)?,
    })
}

fn map_sql(err: rusqlite::Error) -> TransitionError {
    crate::sanitize_db_err(err)
}
