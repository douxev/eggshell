//! Encrypted SQLite layer (SQLCipher).
//!
//! `Database` wraps a `rusqlite::Connection` keyed with the user's master key.
//! Every connection issues `PRAGMA key = ...` immediately after opening; if the
//! key is wrong, the first query will fail with a `SqliteFailure` of code
//! `SQLITE_NOTADB` (the page cannot be decrypted).
//!
//! Schema is versioned via `PRAGMA user_version`. Migrations are pure SQL and
//! run inside a single transaction so a crash leaves the DB at a known version.

use rusqlite::{Connection, OpenFlags, params};
use std::path::Path;

use crate::TransitionError;
use crate::crypto::MasterKey;

/// Latest schema version this build of `transition-core` understands.
pub const CURRENT_SCHEMA_VERSION: u32 = 14;

pub struct Database {
    conn: Connection,
}

impl Database {
    /// Open (or create) the encrypted database at `path` using `key`.
    /// Runs all pending migrations before returning.
    pub fn open(path: &Path, key: &MasterKey) -> Result<Self, TransitionError> {
        let conn = Connection::open_with_flags(
            path,
            OpenFlags::SQLITE_OPEN_READ_WRITE | OpenFlags::SQLITE_OPEN_CREATE | OpenFlags::SQLITE_OPEN_NO_MUTEX,
        )
        .map_err(map_sql)?;
        set_key(&conn, key.expose())?;
        enable_foreign_keys(&conn)?;
        let mut db = Self { conn };
        db.run_migrations()?;
        Ok(db)
    }

    /// Verify that the supplied key actually decrypts an existing database
    /// without running migrations. Returns `Ok` on success or
    /// `TransitionError::WrongKey` if the key does not match.
    pub fn verify_key(path: &Path, key: &MasterKey) -> Result<(), TransitionError> {
        let conn = Connection::open_with_flags(path, OpenFlags::SQLITE_OPEN_READ_ONLY)
            .map_err(map_sql)?;
        set_key(&conn, key.expose())?;
        // Forcing a read of sqlite_schema fails fast if the key is wrong.
        conn.query_row("SELECT count(*) FROM sqlite_schema", [], |r| r.get::<_, i64>(0))
            .map(|_| ())
            .map_err(|e| match e {
                rusqlite::Error::SqliteFailure(err, _) if err.extended_code == 26 => {
                    TransitionError::WrongKey
                }
                other => map_sql(other),
            })
    }

    /// Borrow the underlying connection. Other modules in `transition-core`
    /// build their queries against this; we keep `conn` private so callers can
    /// not accidentally re-open it unencrypted.
    pub(crate) fn conn(&self) -> &Connection {
        &self.conn
    }

    /// Current `user_version` value.
    pub fn schema_version(&self) -> Result<u32, TransitionError> {
        self.conn
            .query_row("PRAGMA user_version", [], |r| r.get::<_, u32>(0))
            .map_err(map_sql)
    }

    fn run_migrations(&mut self) -> Result<(), TransitionError> {
        let tx = self.conn.transaction().map_err(map_sql)?;
        let current: u32 = tx
            .query_row("PRAGMA user_version", [], |r| r.get(0))
            .map_err(map_sql)?;
        for version in (current + 1)..=CURRENT_SCHEMA_VERSION {
            apply_migration(&tx, version)?;
            tx.pragma_update(None, "user_version", version)
                .map_err(map_sql)?;
        }
        tx.commit().map_err(map_sql)?;
        Ok(())
    }
}

fn enable_foreign_keys(conn: &Connection) -> Result<(), TransitionError> {
    // SQLite ships with foreign keys OFF by default — we rely on cascades for
    // dose_events when a medication is deleted, so opt in for every connection.
    conn.execute_batch("PRAGMA foreign_keys = ON;").map_err(map_sql)
}

fn set_key(conn: &Connection, key: &[u8; crate::crypto::KEY_LEN]) -> Result<(), TransitionError> {
    // SQLCipher accepts `x'<hex>'` literals for raw-key mode, which bypasses
    // its own PBKDF2 derivation — we already use Argon2id so a second KDF
    // would only burn time.
    let mut hex = String::with_capacity(2 * key.len());
    for byte in key {
        use std::fmt::Write;
        let _ = write!(hex, "{byte:02x}");
    }
    let pragma = format!("PRAGMA key = \"x'{hex}'\"");
    conn.execute_batch(&pragma).map_err(map_sql)?;
    // Touch the schema once so SQLCipher decrypts the first page now rather
    // than on the first user query — fails fast if the key is wrong.
    conn.query_row("SELECT count(*) FROM sqlite_schema", [], |r| r.get::<_, i64>(0))
        .map(|_| ())
        .map_err(map_sql)
}

fn apply_migration(tx: &rusqlite::Transaction, version: u32) -> Result<(), TransitionError> {
    match version {
        1 => {
            tx.execute_batch(include_str!("migrations/0001_initial.sql"))
                .map_err(map_sql)?;
        }
        2 => {
            tx.execute_batch(include_str!("migrations/0002_medication.sql"))
                .map_err(map_sql)?;
        }
        3 => {
            tx.execute_batch(include_str!("migrations/0003_dose_schedules.sql"))
                .map_err(map_sql)?;
        }
        4 => {
            tx.execute_batch(include_str!("migrations/0004_journal.sql"))
                .map_err(map_sql)?;
        }
        5 => {
            tx.execute_batch(include_str!("migrations/0005_hormones.sql"))
                .map_err(map_sql)?;
        }
        6 => {
            tx.execute_batch(include_str!("migrations/0006_photos.sql"))
                .map_err(map_sql)?;
        }
        7 => {
            tx.execute_batch(include_str!("migrations/0007_voice_clips.sql"))
                .map_err(map_sql)?;
        }
        8 => {
            tx.execute_batch(include_str!("migrations/0008_schedule_days_interval.sql"))
                .map_err(map_sql)?;
        }
        9 => {
            tx.execute_batch(include_str!("migrations/0009_dose_status.sql"))
                .map_err(map_sql)?;
        }
        10 => {
            tx.execute_batch(include_str!("migrations/0010_metrics.sql"))
                .map_err(map_sql)?;
        }
        11 => {
            tx.execute_batch(include_str!("migrations/0011_bleeding.sql"))
                .map_err(map_sql)?;
        }
        12 => {
            tx.execute_batch(include_str!("migrations/0012_treatment_changes.sql"))
                .map_err(map_sql)?;
        }
        13 => {
            tx.execute_batch(include_str!("migrations/0013_appointments.sql"))
                .map_err(map_sql)?;
        }
        14 => {
            tx.execute_batch(include_str!("migrations/0014_schedule_label.sql"))
                .map_err(map_sql)?;
        }
        v => {
            return Err(TransitionError::Migration(format!(
                "no migration registered for version {v}"
            )));
        }
    }
    Ok(())
}

fn map_sql(err: rusqlite::Error) -> TransitionError {
    crate::sanitize_db_err(err)
}

/// Helpers for the (single-row) `users` table.
pub mod user_row {
    use super::*;

    /// Type-safe enum of supported security modes. Persisted as a short tag.
    #[derive(Clone, Copy, Debug, PartialEq, Eq)]
    pub enum SecurityMode {
        KeystoreOnly,
        KeystoreBiometric,
        KeystorePassphrase,
        Paranoid,
    }

    impl SecurityMode {
        pub fn as_tag(self) -> &'static str {
            match self {
                SecurityMode::KeystoreOnly => "keystore",
                SecurityMode::KeystoreBiometric => "keystore_biometric",
                SecurityMode::KeystorePassphrase => "keystore_passphrase",
                SecurityMode::Paranoid => "paranoid",
            }
        }

        pub fn from_tag(tag: &str) -> Result<Self, TransitionError> {
            match tag {
                "keystore" => Ok(SecurityMode::KeystoreOnly),
                "keystore_biometric" => Ok(SecurityMode::KeystoreBiometric),
                "keystore_passphrase" => Ok(SecurityMode::KeystorePassphrase),
                "paranoid" => Ok(SecurityMode::Paranoid),
                other => Err(TransitionError::Database(format!(
                    "unknown security mode tag: {other}"
                ))),
            }
        }
    }

    /// Insert the (single) user row. Idempotent — overwrites any existing row.
    pub fn upsert(
        db: &Database,
        mode: SecurityMode,
        kdf_salt: Option<&[u8]>,
        kdf_m_cost_kib: Option<u32>,
        kdf_t_cost: Option<u32>,
        kdf_p_cost: Option<u32>,
        wrapped_db_key: Option<&[u8]>,
        created_at_ms: i64,
    ) -> Result<(), TransitionError> {
        db.conn()
            .execute(
                "INSERT INTO users (id, security_mode, kdf_salt, kdf_m_cost_kib, kdf_t_cost, kdf_p_cost, wrapped_db_key, created_at_ms)
                 VALUES (1, ?1, ?2, ?3, ?4, ?5, ?6, ?7)
                 ON CONFLICT(id) DO UPDATE SET
                    security_mode = excluded.security_mode,
                    kdf_salt = excluded.kdf_salt,
                    kdf_m_cost_kib = excluded.kdf_m_cost_kib,
                    kdf_t_cost = excluded.kdf_t_cost,
                    kdf_p_cost = excluded.kdf_p_cost,
                    wrapped_db_key = excluded.wrapped_db_key",
                params![
                    mode.as_tag(),
                    kdf_salt,
                    kdf_m_cost_kib,
                    kdf_t_cost,
                    kdf_p_cost,
                    wrapped_db_key,
                    created_at_ms
                ],
            )
            .map_err(map_sql)?;
        Ok(())
    }

    /// Read the single user row. `None` if the vault has not been initialised yet.
    #[allow(clippy::type_complexity)]
    pub fn load(db: &Database) -> Result<Option<UserRow>, TransitionError> {
        db.conn()
            .query_row(
                "SELECT security_mode, kdf_salt, kdf_m_cost_kib, kdf_t_cost, kdf_p_cost, wrapped_db_key, created_at_ms
                 FROM users WHERE id = 1",
                [],
                |r| {
                    Ok(UserRow {
                        security_mode: r.get::<_, String>(0)?,
                        kdf_salt: r.get::<_, Option<Vec<u8>>>(1)?,
                        kdf_m_cost_kib: r.get::<_, Option<u32>>(2)?,
                        kdf_t_cost: r.get::<_, Option<u32>>(3)?,
                        kdf_p_cost: r.get::<_, Option<u32>>(4)?,
                        wrapped_db_key: r.get::<_, Option<Vec<u8>>>(5)?,
                        created_at_ms: r.get::<_, i64>(6)?,
                    })
                },
            )
            .map(Some)
            .or_else(|e| match e {
                rusqlite::Error::QueryReturnedNoRows => Ok(None),
                other => Err(map_sql(other)),
            })
    }

    #[derive(Debug, Clone, PartialEq, Eq)]
    pub struct UserRow {
        pub security_mode: String,
        pub kdf_salt: Option<Vec<u8>>,
        pub kdf_m_cost_kib: Option<u32>,
        pub kdf_t_cost: Option<u32>,
        pub kdf_p_cost: Option<u32>,
        pub wrapped_db_key: Option<Vec<u8>>,
        pub created_at_ms: i64,
    }
}

#[cfg(test)]
mod tests {
    use super::user_row::*;
    use super::*;
    use crate::crypto::MasterKey;
    use tempfile::NamedTempFile;

    fn fresh_db_path() -> std::path::PathBuf {
        // Create then drop the tempfile so we get a unique path that does not
        // yet exist — Database::open will create the SQLCipher file itself.
        let f = NamedTempFile::new().unwrap();
        let p = f.path().to_path_buf();
        drop(f);
        let _ = std::fs::remove_file(&p);
        p
    }

    #[test]
    fn open_creates_database_and_runs_migrations() {
        let path = fresh_db_path();
        let key = MasterKey::generate();
        let db = Database::open(&path, &key).expect("open");
        assert_eq!(db.schema_version().unwrap(), CURRENT_SCHEMA_VERSION);
    }

    #[test]
    fn wrong_key_is_rejected() {
        let path = fresh_db_path();
        let good = MasterKey::generate();
        {
            let _db = Database::open(&path, &good).expect("create");
        }
        let bad = MasterKey::generate();
        let err = Database::verify_key(&path, &bad).unwrap_err();
        matches!(err, TransitionError::WrongKey);
    }

    #[test]
    fn upsert_and_load_user_row() {
        let path = fresh_db_path();
        let key = MasterKey::generate();
        let db = Database::open(&path, &key).unwrap();

        assert!(user_row::load(&db).unwrap().is_none());

        user_row::upsert(
            &db,
            SecurityMode::KeystorePassphrase,
            Some(&[42u8; 16]),
            Some(64 * 1024),
            Some(3),
            Some(4),
            Some(&[7u8; 48]),
            1_700_000_000_000,
        )
        .unwrap();

        let loaded = user_row::load(&db).unwrap().unwrap();
        assert_eq!(loaded.security_mode, "keystore_passphrase");
        assert_eq!(loaded.kdf_salt.as_deref(), Some(&[42u8; 16][..]));
        assert_eq!(loaded.kdf_m_cost_kib, Some(64 * 1024));
        assert_eq!(loaded.wrapped_db_key.as_deref(), Some(&[7u8; 48][..]));
    }
}
