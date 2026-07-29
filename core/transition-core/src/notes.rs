//! Notes: a flat, manually-ordered list of text notes with attached images.
//!
//! Deliberately not a folder tree. Nothing else in the app nests, and the
//! interaction being reproduced is the decoy notes app's drag-to-reorder grid,
//! so ordering is a `sort_order` integer per row — the same idiom the custom
//! metrics already use.
//!
//! Images are rows pointing at ciphertext files that the native side writes,
//! exactly like photos and voice clips. Nothing here ever sees image bytes.

use rusqlite::{params, Row};

use crate::db::Database;
use crate::TransitionError;

#[derive(Clone, Debug, PartialEq)]
pub struct Note {
    pub id: i64,
    pub title: String,
    pub body: String,
    /// Position in the manual order, ascending. Gaps are fine.
    pub sort_order: i64,
    pub created_ms: i64,
    pub updated_ms: i64,
}

#[derive(Clone, Debug)]
pub struct NewNote {
    pub title: String,
    pub body: String,
    pub created_ms: i64,
    pub updated_ms: i64,
}

#[derive(Clone, Debug, PartialEq)]
pub struct NoteImage {
    pub id: i64,
    pub note_id: i64,
    pub file_path: String,
    pub position: i64,
}

#[derive(Clone, Debug)]
pub struct NewNoteImage {
    pub note_id: i64,
    pub file_path: String,
    pub position: i64,
}

/// Append a note at the end of the manual order.
pub fn add(db: &Database, n: NewNote) -> Result<Note, TransitionError> {
    let next: i64 = db
        .conn()
        .query_row(
            "SELECT COALESCE(MAX(sort_order), -1) + 1 FROM notes",
            [],
            |r| r.get(0),
        )
        .map_err(map_sql)?;
    db.conn()
        .execute(
            "INSERT INTO notes (title, body, sort_order, created_ms, updated_ms)
             VALUES (?1, ?2, ?3, ?4, ?5)",
            params![n.title, n.body, next, n.created_ms, n.updated_ms],
        )
        .map_err(map_sql)?;
    Ok(Note {
        id: db.conn().last_insert_rowid(),
        title: n.title,
        body: n.body,
        sort_order: next,
        created_ms: n.created_ms,
        updated_ms: n.updated_ms,
    })
}

pub fn list(db: &Database) -> Result<Vec<Note>, TransitionError> {
    let conn = db.conn();
    let mut stmt = conn
        .prepare(
            "SELECT id, title, body, sort_order, created_ms, updated_ms
             FROM notes ORDER BY sort_order ASC, id ASC",
        )
        .map_err(map_sql)?;
    let rows = stmt.query_map([], parse).map_err(map_sql)?;
    rows.collect::<Result<_, _>>().map_err(map_sql)
}

pub fn get(db: &Database, id: i64) -> Result<Option<Note>, TransitionError> {
    let conn = db.conn();
    match conn.query_row(
        "SELECT id, title, body, sort_order, created_ms, updated_ms FROM notes WHERE id = ?1",
        params![id],
        parse,
    ) {
        Ok(n) => Ok(Some(n)),
        Err(rusqlite::Error::QueryReturnedNoRows) => Ok(None),
        Err(e) => Err(map_sql(e)),
    }
}

pub fn update(
    db: &Database,
    id: i64,
    title: String,
    body: String,
    updated_ms: i64,
) -> Result<Note, TransitionError> {
    let n = db
        .conn()
        .execute(
            "UPDATE notes SET title = ?1, body = ?2, updated_ms = ?3 WHERE id = ?4",
            params![title, body, updated_ms, id],
        )
        .map_err(map_sql)?;
    // A no-op UPDATE is otherwise indistinguishable from success across the FFI.
    if n == 0 {
        return Err(TransitionError::Database(format!("no note with id {id}")));
    }
    get(db, id)?.ok_or_else(|| TransitionError::Database(format!("no note with id {id}")))
}

pub fn delete(db: &Database, id: i64) -> Result<(), TransitionError> {
    db.conn()
        .execute("DELETE FROM notes WHERE id = ?1", params![id])
        .map_err(map_sql)?;
    Ok(())
}

/// Rewrite the whole manual order in one transaction.
///
/// The UI hands back the ids in their new order rather than a pair to swap:
/// a drag can move an item several positions, and replaying that as N swaps
/// would leave the list briefly inconsistent if any step failed.
pub fn reorder(db: &Database, ids_in_order: Vec<i64>) -> Result<(), TransitionError> {
    let conn = db.conn();
    let tx = conn.unchecked_transaction().map_err(map_sql)?;
    for (position, id) in ids_in_order.iter().enumerate() {
        tx.execute(
            "UPDATE notes SET sort_order = ?1 WHERE id = ?2",
            params![position as i64, id],
        )
        .map_err(map_sql)?;
    }
    tx.commit().map_err(map_sql)?;
    Ok(())
}

// -- images ------------------------------------------------------------------

pub fn add_image(db: &Database, img: NewNoteImage) -> Result<NoteImage, TransitionError> {
    db.conn()
        .execute(
            "INSERT INTO note_images (note_id, file_path, position) VALUES (?1, ?2, ?3)",
            params![img.note_id, img.file_path, img.position],
        )
        .map_err(map_sql)?;
    Ok(NoteImage {
        id: db.conn().last_insert_rowid(),
        note_id: img.note_id,
        file_path: img.file_path,
        position: img.position,
    })
}

pub fn images_for(db: &Database, note_id: i64) -> Result<Vec<NoteImage>, TransitionError> {
    let conn = db.conn();
    let mut stmt = conn
        .prepare(
            "SELECT id, note_id, file_path, position FROM note_images
             WHERE note_id = ?1 ORDER BY position ASC, id ASC",
        )
        .map_err(map_sql)?;
    let rows = stmt
        .query_map(params![note_id], |r| {
            Ok(NoteImage {
                id: r.get(0)?,
                note_id: r.get(1)?,
                file_path: r.get(2)?,
                position: r.get(3)?,
            })
        })
        .map_err(map_sql)?;
    rows.collect::<Result<_, _>>().map_err(map_sql)
}

/// Every image path in the vault, for the native orphan sweep.
pub fn all_image_paths(db: &Database) -> Result<Vec<String>, TransitionError> {
    let conn = db.conn();
    let mut stmt = conn.prepare("SELECT file_path FROM note_images").map_err(map_sql)?;
    let rows = stmt.query_map([], |r| r.get::<_, String>(0)).map_err(map_sql)?;
    rows.collect::<Result<_, _>>().map_err(map_sql)
}

pub fn delete_image(db: &Database, id: i64) -> Result<(), TransitionError> {
    db.conn()
        .execute("DELETE FROM note_images WHERE id = ?1", params![id])
        .map_err(map_sql)?;
    Ok(())
}

fn parse(row: &Row) -> rusqlite::Result<Note> {
    Ok(Note {
        id: row.get(0)?,
        title: row.get(1)?,
        body: row.get(2)?,
        sort_order: row.get(3)?,
        created_ms: row.get(4)?,
        updated_ms: row.get(5)?,
    })
}

fn map_sql(err: rusqlite::Error) -> TransitionError {
    crate::sanitize_db_err(err)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::crypto::MasterKey;

    fn fresh_db() -> (Database, tempfile::NamedTempFile) {
        let f = tempfile::NamedTempFile::new().unwrap();
        let db = Database::open(f.path(), &MasterKey::generate()).unwrap();
        (db, f)
    }

    #[test]
    fn notes_append_in_order_and_reorder_rewrites_it() {
        let (db, _f) = fresh_db();
        let a = add(&db, NewNote { title: "A".into(), body: "a".into(), created_ms: 1, updated_ms: 1 }).unwrap();
        let b = add(&db, NewNote { title: "B".into(), body: "b".into(), created_ms: 2, updated_ms: 2 }).unwrap();
        let c = add(&db, NewNote { title: "C".into(), body: "c".into(), created_ms: 3, updated_ms: 3 }).unwrap();
        assert_eq!(
            list(&db).unwrap().iter().map(|n| n.title.clone()).collect::<Vec<_>>(),
            vec!["A", "B", "C"],
        );

        reorder(&db, vec![c.id, a.id, b.id]).unwrap();
        assert_eq!(
            list(&db).unwrap().iter().map(|n| n.title.clone()).collect::<Vec<_>>(),
            vec!["C", "A", "B"],
        );
    }

    #[test]
    fn deleting_a_note_cascades_its_images() {
        let (db, _f) = fresh_db();
        let n = add(&db, NewNote { title: "N".into(), body: "".into(), created_ms: 1, updated_ms: 1 }).unwrap();
        add_image(&db, NewNoteImage { note_id: n.id, file_path: "/x/1.bin".into(), position: 0 }).unwrap();
        add_image(&db, NewNoteImage { note_id: n.id, file_path: "/x/2.bin".into(), position: 1 }).unwrap();
        assert_eq!(images_for(&db, n.id).unwrap().len(), 2);

        delete(&db, n.id).unwrap();
        assert!(images_for(&db, n.id).unwrap().is_empty());
        assert!(
            all_image_paths(&db).unwrap().is_empty(),
            "orphan image rows would keep their .bin files alive forever",
        );
    }

    #[test]
    fn updating_a_missing_note_is_an_error_not_a_silent_no_op() {
        let (db, _f) = fresh_db();
        assert!(update(&db, 999, "t".into(), "b".into(), 1).is_err());
    }
}
