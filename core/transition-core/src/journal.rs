//! Feelings journal — quick gauge check-ins + free-text entries.

use rusqlite::{params, Row};

use crate::db::Database;
use crate::TransitionError;

#[derive(Clone, Debug, PartialEq)]
pub struct JournalEntry {
    pub id: i64,
    pub at_ms: i64,
    pub mood: Option<u32>,
    pub dysphoria: Option<u32>,
    pub euphoria: Option<u32>,
    pub libido: Option<u32>,
    pub energy: Option<u32>,
    pub free_text: Option<String>,
    pub side_effects: Option<String>,
}

#[derive(Clone, Debug)]
pub struct NewJournalEntry {
    pub at_ms: i64,
    pub mood: Option<u32>,
    pub dysphoria: Option<u32>,
    pub euphoria: Option<u32>,
    pub libido: Option<u32>,
    pub energy: Option<u32>,
    pub free_text: Option<String>,
    pub side_effects: Option<String>,
}

pub fn add(db: &Database, e: NewJournalEntry) -> Result<JournalEntry, TransitionError> {
    db.conn()
        .execute(
            "INSERT INTO journal_entries
                (at_ms, mood, dysphoria, euphoria, libido, energy, free_text, side_effects)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)",
            params![
                e.at_ms, e.mood, e.dysphoria, e.euphoria, e.libido,
                e.energy, e.free_text, e.side_effects,
            ],
        )
        .map_err(map_sql)?;
    let id = db.conn().last_insert_rowid();
    Ok(JournalEntry {
        id,
        at_ms: e.at_ms,
        mood: e.mood,
        dysphoria: e.dysphoria,
        euphoria: e.euphoria,
        libido: e.libido,
        energy: e.energy,
        free_text: e.free_text,
        side_effects: e.side_effects,
    })
}

pub fn list(db: &Database, offset: i64, limit: i64) -> Result<Vec<JournalEntry>, TransitionError> {
    let conn = db.conn();
    let mut stmt = conn
        .prepare(
            "SELECT id, at_ms, mood, dysphoria, euphoria, libido, energy, free_text, side_effects
             FROM journal_entries
             ORDER BY at_ms DESC
             LIMIT ?1 OFFSET ?2",
        )
        .map_err(map_sql)?;
    let rows = stmt
        .query_map(params![limit, offset], parse)
        .map_err(map_sql)?
        .collect::<Result<Vec<_>, _>>()
        .map_err(map_sql)?;
    Ok(rows)
}

pub fn get(db: &Database, id: i64) -> Result<Option<JournalEntry>, TransitionError> {
    db.conn()
        .query_row(
            "SELECT id, at_ms, mood, dysphoria, euphoria, libido, energy, free_text, side_effects
             FROM journal_entries WHERE id = ?1",
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
        .execute("DELETE FROM journal_entries WHERE id = ?1", [id])
        .map_err(map_sql)?;
    Ok(())
}

fn parse(row: &Row) -> rusqlite::Result<JournalEntry> {
    Ok(JournalEntry {
        id: row.get(0)?,
        at_ms: row.get(1)?,
        mood: row.get(2)?,
        dysphoria: row.get(3)?,
        euphoria: row.get(4)?,
        libido: row.get(5)?,
        energy: row.get(6)?,
        free_text: row.get(7)?,
        side_effects: row.get(8)?,
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
    fn add_and_list_entries_newest_first() {
        let (_k, db) = fresh_db();
        for (i, mood) in [(1_000, 5u32), (2_000, 7), (3_000, 8)].iter() {
            add(
                &db,
                NewJournalEntry {
                    at_ms: *i,
                    mood: Some(*mood),
                    dysphoria: None,
                    euphoria: None,
                    libido: None,
                    energy: None,
                    free_text: None,
                    side_effects: None,
                },
            )
            .unwrap();
        }
        let xs = list(&db, 0, 10).unwrap();
        assert_eq!(xs.len(), 3);
        assert_eq!(xs[0].at_ms, 3_000);
        assert_eq!(xs[2].at_ms, 1_000);
    }

    #[test]
    fn gauge_out_of_range_is_rejected_by_check() {
        let (_k, db) = fresh_db();
        let err = add(
            &db,
            NewJournalEntry {
                at_ms: 1,
                mood: Some(42),
                dysphoria: None,
                euphoria: None,
                libido: None,
                energy: None,
                free_text: None,
                side_effects: None,
            },
        );
        assert!(err.is_err());
    }

    #[test]
    fn delete_removes_entry() {
        let (_k, db) = fresh_db();
        let e = add(
            &db,
            NewJournalEntry {
                at_ms: 1,
                mood: Some(5),
                dysphoria: None,
                euphoria: None,
                libido: None,
                energy: None,
                free_text: Some("note".into()),
                side_effects: None,
            },
        )
        .unwrap();
        delete(&db, e.id).unwrap();
        assert!(list(&db, 0, 10).unwrap().is_empty());
    }
}
