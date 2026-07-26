//! In-vault key/value settings.
//!
//! The `app_settings` table has existed since the first migration but was never
//! reachable from the apps, so anything that had to be remembered ended up in
//! the platforms' own preference stores. Those are **not encrypted** — on
//! Android `SecurePrefs` only obfuscates the file *name* — which is fine for a
//! counter or a toggle and unacceptable for anything that identifies the person
//! using an app that ships a decoy mode.
//!
//! This is that missing store: rows live inside the SQLCipher database, so they
//! are encrypted at rest with everything else and disappear with the vault when
//! it is wiped.
//!
//! Values are stored as BLOBs and handed over as UTF-8 strings. A caller that
//! needs structure encodes it itself; keeping the surface to two calls means
//! there is nothing here to migrate later.

use rusqlite::{params, OptionalExtension};

use crate::db::Database;
use crate::TransitionError;

/// Reads a setting. Returns `None` when the key was never written.
pub fn get(db: &Database, key: &str) -> Result<Option<String>, TransitionError> {
    let raw: Option<Vec<u8>> = db
        .conn()
        .query_row(
            "SELECT value FROM app_settings WHERE key = ?1",
            params![key],
            |row| row.get(0),
        )
        .optional()
        .map_err(crate::sanitize_db_err)?;
    match raw {
        None => Ok(None),
        // A row that is not valid UTF-8 was not written by us. Report it as
        // absent rather than failing the caller: settings are always optional,
        // and a corrupt one must never be able to block a screen from opening.
        Some(bytes) => Ok(String::from_utf8(bytes).ok()),
    }
}

/// Writes a setting, replacing any previous value.
pub fn set(db: &Database, key: &str, value: &str) -> Result<(), TransitionError> {
    db.conn()
        .execute(
            "INSERT INTO app_settings (key, value) VALUES (?1, ?2)
             ON CONFLICT(key) DO UPDATE SET value = excluded.value",
            params![key, value.as_bytes()],
        )
        .map_err(crate::sanitize_db_err)?;
    Ok(())
}

/// Forgets a setting. A no-op when the key is absent, so clearing a field the
/// user never filled is not an error.
pub fn delete(db: &Database, key: &str) -> Result<(), TransitionError> {
    db.conn()
        .execute("DELETE FROM app_settings WHERE key = ?1", params![key])
        .map_err(crate::sanitize_db_err)?;
    Ok(())
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
    fn absent_key_reads_as_none() {
        let (_owner, db) = fresh_db();
        assert_eq!(get(&db, "report.person").unwrap(), None);
    }

    #[test]
    fn set_then_get_round_trips_and_replaces() {
        let (_owner, db) = fresh_db();
        set(&db, "report.person", "Alex Marchand").unwrap();
        assert_eq!(
            get(&db, "report.person").unwrap().as_deref(),
            Some("Alex Marchand")
        );
        set(&db, "report.person", "Alex M.").unwrap();
        assert_eq!(get(&db, "report.person").unwrap().as_deref(), Some("Alex M."));
    }

    #[test]
    fn delete_is_idempotent() {
        let (_owner, db) = fresh_db();
        set(&db, "k", "v").unwrap();
        delete(&db, "k").unwrap();
        delete(&db, "k").unwrap();
        assert_eq!(get(&db, "k").unwrap(), None);
    }

    #[test]
    fn accents_survive_the_blob_round_trip() {
        let (_owner, db) = fresh_db();
        set(&db, "k", "Éléonore Ruíz — née le 3 février").unwrap();
        assert_eq!(
            get(&db, "k").unwrap().as_deref(),
            Some("Éléonore Ruíz — née le 3 février")
        );
    }

    #[test]
    fn empty_string_is_a_value_not_an_absence() {
        // The UI clears a field by deleting the key, never by writing "" — so
        // the two must stay distinguishable at this layer.
        let (_owner, db) = fresh_db();
        set(&db, "k", "").unwrap();
        assert_eq!(get(&db, "k").unwrap().as_deref(), Some(""));
    }
}
