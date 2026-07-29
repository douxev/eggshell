//! Notes: markdown notes with attached images, organised in nested folders.
//!
//! Folders nest, notes do not: a note lives in exactly one folder, or at the
//! root. Within a folder the order is manual — a `sort_order` integer per row,
//! the same idiom the custom metrics already use — so a drag is a rewrite of
//! integers rather than tree surgery.
//!
//! Images are rows pointing at ciphertext files that the native side writes,
//! exactly like photos and voice clips. Nothing here ever sees image bytes.

use rusqlite::{params, Row};

use crate::db::Database;
use crate::TransitionError;

#[derive(Clone, Debug, PartialEq)]
pub struct Note {
    pub id: i64,
    /// The folder this note sits in, or None at the root.
    pub folder_id: Option<i64>,
    pub title: String,
    pub body: String,
    /// Position in the manual order, ascending. Gaps are fine.
    pub sort_order: i64,
    pub created_ms: i64,
    pub updated_ms: i64,
}

#[derive(Clone, Debug)]
pub struct NewNote {
    pub folder_id: Option<i64>,
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
    // Ordering is per folder: appending to one must not depend on how many
    // notes exist elsewhere.
    let next: i64 = db
        .conn()
        .query_row(
            "SELECT COALESCE(MAX(sort_order), -1) + 1 FROM notes
             WHERE folder_id IS ?1",
            params![n.folder_id],
            |r| r.get(0),
        )
        .map_err(map_sql)?;
    db.conn()
        .execute(
            "INSERT INTO notes (folder_id, title, body, sort_order, created_ms, updated_ms)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
            params![n.folder_id, n.title, n.body, next, n.created_ms, n.updated_ms],
        )
        .map_err(map_sql)?;
    Ok(Note {
        id: db.conn().last_insert_rowid(),
        folder_id: n.folder_id,
        title: n.title,
        body: n.body,
        sort_order: next,
        created_ms: n.created_ms,
        updated_ms: n.updated_ms,
    })
}

/// Notes directly inside `folder_id` (None = the root), in manual order.
pub fn list(db: &Database, folder_id: Option<i64>) -> Result<Vec<Note>, TransitionError> {
    let conn = db.conn();
    let mut stmt = conn
        .prepare(
            "SELECT id, folder_id, title, body, sort_order, created_ms, updated_ms
             FROM notes WHERE folder_id IS ?1 ORDER BY sort_order ASC, id ASC",
        )
        .map_err(map_sql)?;
    let rows = stmt.query_map(params![folder_id], parse).map_err(map_sql)?;
    rows.collect::<Result<_, _>>().map_err(map_sql)
}

pub fn get(db: &Database, id: i64) -> Result<Option<Note>, TransitionError> {
    let conn = db.conn();
    match conn.query_row(
        "SELECT id, folder_id, title, body, sort_order, created_ms, updated_ms FROM notes WHERE id = ?1",
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

/// Move a note into another folder (None = the root), appended at the end.
pub fn move_to_folder(db: &Database, id: i64, folder_id: Option<i64>) -> Result<(), TransitionError> {
    let next: i64 = db
        .conn()
        .query_row(
            "SELECT COALESCE(MAX(sort_order), -1) + 1 FROM notes WHERE folder_id IS ?1",
            params![folder_id],
            |r| r.get(0),
        )
        .map_err(map_sql)?;
    let n = db
        .conn()
        .execute(
            "UPDATE notes SET folder_id = ?1, sort_order = ?2 WHERE id = ?3",
            params![folder_id, next, id],
        )
        .map_err(map_sql)?;
    if n == 0 {
        return Err(TransitionError::Database(format!("no note with id {id}")));
    }
    Ok(())
}

// -- folders -----------------------------------------------------------------

#[derive(Clone, Debug, PartialEq)]
pub struct NoteFolder {
    pub id: i64,
    pub name: String,
    pub parent_id: Option<i64>,
    pub sort_order: i64,
    pub created_ms: i64,
}

#[derive(Clone, Debug)]
pub struct NewNoteFolder {
    pub name: String,
    pub parent_id: Option<i64>,
    pub created_ms: i64,
}

pub fn add_folder(db: &Database, f: NewNoteFolder) -> Result<NoteFolder, TransitionError> {
    let next: i64 = db
        .conn()
        .query_row(
            "SELECT COALESCE(MAX(sort_order), -1) + 1 FROM note_folders WHERE parent_id IS ?1",
            params![f.parent_id],
            |r| r.get(0),
        )
        .map_err(map_sql)?;
    db.conn()
        .execute(
            "INSERT INTO note_folders (name, parent_id, sort_order, created_ms)
             VALUES (?1, ?2, ?3, ?4)",
            params![f.name, f.parent_id, next, f.created_ms],
        )
        .map_err(map_sql)?;
    Ok(NoteFolder {
        id: db.conn().last_insert_rowid(),
        name: f.name,
        parent_id: f.parent_id,
        sort_order: next,
        created_ms: f.created_ms,
    })
}

/// Folders directly inside `parent_id` (None = the root).
pub fn list_folders(db: &Database, parent_id: Option<i64>) -> Result<Vec<NoteFolder>, TransitionError> {
    let conn = db.conn();
    let mut stmt = conn
        .prepare(
            "SELECT id, name, parent_id, sort_order, created_ms FROM note_folders
             WHERE parent_id IS ?1 ORDER BY sort_order ASC, id ASC",
        )
        .map_err(map_sql)?;
    let rows = stmt
        .query_map(params![parent_id], |r| {
            Ok(NoteFolder {
                id: r.get(0)?,
                name: r.get(1)?,
                parent_id: r.get(2)?,
                sort_order: r.get(3)?,
                created_ms: r.get(4)?,
            })
        })
        .map_err(map_sql)?;
    rows.collect::<Result<_, _>>().map_err(map_sql)
}

pub fn rename_folder(db: &Database, id: i64, name: String) -> Result<(), TransitionError> {
    let n = db
        .conn()
        .execute("UPDATE note_folders SET name = ?1 WHERE id = ?2", params![name, id])
        .map_err(map_sql)?;
    if n == 0 {
        return Err(TransitionError::Database(format!("no folder with id {id}")));
    }
    Ok(())
}

/// How much a folder deletion would take with it, counted recursively.
///
/// The cascade is silent at the SQL level, so the UI has to be able to say
/// "this also deletes 12 notes" before the user commits to it.
pub fn folder_contents_count(db: &Database, id: i64) -> Result<i64, TransitionError> {
    db.conn()
        .query_row(
            "WITH RECURSIVE tree(fid) AS (
                 SELECT ?1
                 UNION ALL
                 SELECT f.id FROM note_folders f JOIN tree ON f.parent_id = tree.fid
             )
             SELECT COUNT(*) FROM notes WHERE folder_id IN (SELECT fid FROM tree)",
            params![id],
            |r| r.get(0),
        )
        .map_err(map_sql)
}

/// Every image path under a folder, so the caller can delete the files that
/// the row cascade is about to orphan.
pub fn image_paths_under_folder(db: &Database, id: i64) -> Result<Vec<String>, TransitionError> {
    let conn = db.conn();
    let mut stmt = conn
        .prepare(
            "WITH RECURSIVE tree(fid) AS (
                 SELECT ?1
                 UNION ALL
                 SELECT f.id FROM note_folders f JOIN tree ON f.parent_id = tree.fid
             )
             SELECT i.file_path FROM note_images i
             JOIN notes n ON n.id = i.note_id
             WHERE n.folder_id IN (SELECT fid FROM tree)",
        )
        .map_err(map_sql)?;
    let rows = stmt.query_map(params![id], |r| r.get::<_, String>(0)).map_err(map_sql)?;
    rows.collect::<Result<_, _>>().map_err(map_sql)
}

pub fn delete_folder(db: &Database, id: i64) -> Result<(), TransitionError> {
    db.conn()
        .execute("DELETE FROM note_folders WHERE id = ?1", params![id])
        .map_err(map_sql)?;
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
        folder_id: row.get(1)?,
        title: row.get(2)?,
        body: row.get(3)?,
        sort_order: row.get(4)?,
        created_ms: row.get(5)?,
        updated_ms: row.get(6)?,
    })
}

fn map_sql(err: rusqlite::Error) -> TransitionError {
    crate::sanitize_db_err(err)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn Vec_single_title(v: &[Note]) -> String { v.first().map(|n| n.title.clone()).unwrap_or_default() }
    trait SingleTitle { fn single_title(&self) -> String; }
    impl SingleTitle for Vec<Note> { fn single_title(&self) -> String { Vec_single_title(self) } }
    use crate::crypto::MasterKey;

    fn fresh_db() -> (Database, tempfile::NamedTempFile) {
        let f = tempfile::NamedTempFile::new().unwrap();
        let db = Database::open(f.path(), &MasterKey::generate()).unwrap();
        (db, f)
    }

    #[test]
    fn folders_scope_both_listing_and_ordering() {
        let (db, _f) = fresh_db();
        let f = add_folder(&db, NewNoteFolder { name: "Suivi".into(), parent_id: None, created_ms: 1 }).unwrap();
        add(&db, NewNote { folder_id: None, title: "root".into(), body: "".into(), created_ms: 1, updated_ms: 1 }).unwrap();
        let inside = add(&db, NewNote { folder_id: Some(f.id), title: "inside".into(), body: "".into(), created_ms: 2, updated_ms: 2 }).unwrap();

        assert_eq!(list(&db, None).unwrap().len(), 1, "the root must not show nested notes");
        assert_eq!(list(&db, Some(f.id)).unwrap().len(), 1);
        // Ordering restarts per folder rather than continuing a global counter.
        assert_eq!(inside.sort_order, 0);

        // Deleting a folder is a cascade, so the UI has to be able to warn first.
        assert_eq!(folder_contents_count(&db, f.id).unwrap(), 1);
        delete_folder(&db, f.id).unwrap();
        assert!(list(&db, Some(f.id)).unwrap().is_empty());
        assert_eq!(list(&db, None).unwrap().len(), 1, "the root note must be untouched");
    }

    #[test]
    fn moving_a_note_puts_it_at_the_end_of_its_new_folder() {
        let (db, _f) = fresh_db();
        let f = add_folder(&db, NewNoteFolder { name: "F".into(), parent_id: None, created_ms: 1 }).unwrap();
        let n = add(&db, NewNote { folder_id: None, title: "n".into(), body: "".into(), created_ms: 1, updated_ms: 1 }).unwrap();
        move_to_folder(&db, n.id, Some(f.id)).unwrap();
        assert!(list(&db, None).unwrap().is_empty());
        assert_eq!(list(&db, Some(f.id)).unwrap().single_title(), "n");
    }

    #[test]
    fn notes_append_in_order_and_reorder_rewrites_it() {
        let (db, _f) = fresh_db();
        let a = add(&db, NewNote { folder_id: None, title: "A".into(), body: "a".into(), created_ms: 1, updated_ms: 1 }).unwrap();
        let b = add(&db, NewNote { folder_id: None, title: "B".into(), body: "b".into(), created_ms: 2, updated_ms: 2 }).unwrap();
        let c = add(&db, NewNote { folder_id: None, title: "C".into(), body: "c".into(), created_ms: 3, updated_ms: 3 }).unwrap();
        assert_eq!(
            list(&db, None).unwrap().iter().map(|n| n.title.clone()).collect::<Vec<_>>(),
            vec!["A", "B", "C"],
        );

        reorder(&db, vec![c.id, a.id, b.id]).unwrap();
        assert_eq!(
            list(&db, None).unwrap().iter().map(|n| n.title.clone()).collect::<Vec<_>>(),
            vec!["C", "A", "B"],
        );
    }

    #[test]
    fn deleting_a_note_cascades_its_images() {
        let (db, _f) = fresh_db();
        let n = add(&db, NewNote { folder_id: None, title: "N".into(), body: "".into(), created_ms: 1, updated_ms: 1 }).unwrap();
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
