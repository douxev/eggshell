//! transition-core — domain logic shared between Android and iOS.

pub mod crypto;
pub mod db;
pub mod dose_schedule;
pub mod hormones;
pub mod journal;
pub mod medication;
pub mod photos;
pub mod vault;
pub mod voice;

#[derive(Debug, thiserror::Error)]
pub enum TransitionError {
    #[error("unimplemented: {0}")]
    Unimplemented(String),
    #[error("crypto: {0}")]
    Crypto(String),
    #[error("database: {0}")]
    Database(String),
    #[error("migration: {0}")]
    Migration(String),
    #[error("wrong key")]
    WrongKey,
    #[error("vault busy")]
    VaultBusy,
}

/// Sanitize a rusqlite error before it crosses the FFI boundary.
///
/// `rusqlite::Error::SqliteFailure` and `FromSqlConversionFailure` can embed
/// row contents inside their `Display` impl — journal free-text, hormone
/// values, etc. We don't want those bubbling up into Kotlin log lines /
/// crash reports, so we keep the SQLite primary error code as a tag and
/// drop the message entirely.
pub(crate) fn sanitize_db_err(e: rusqlite::Error) -> TransitionError {
    use rusqlite::Error::*;
    let tag = match &e {
        SqliteFailure(err, _) => format!("sqlite code {}", err.extended_code),
        QueryReturnedNoRows => "no rows".to_string(),
        InvalidColumnIndex(_) => "invalid column index".to_string(),
        InvalidColumnName(_) => "invalid column name".to_string(),
        InvalidColumnType(_, _, _) => "invalid column type".to_string(),
        StatementChangedRows(_) => "unexpected changed-row count".to_string(),
        ToSqlConversionFailure(_) => "to-sql conversion failed".to_string(),
        FromSqlConversionFailure(_, _, _) => "from-sql conversion failed".to_string(),
        // Everything else: report the variant name only, never the wrapped
        // string (which can contain row contents on some variants).
        _ => "other".to_string(),
    };
    TransitionError::Database(tag)
}

/// Smoke-test entry point. Returned to the UI to prove the Rust ↔ Kotlin/Swift
/// bridge is wired up correctly. Will be replaced by real APIs in Phase 1+.
pub fn hello(name: String) -> String {
    let trimmed = name.trim();
    if trimmed.is_empty() {
        "Bonjour depuis Rust".to_string()
    } else {
        format!("Bonjour {trimmed} depuis Rust")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hello_with_name() {
        assert_eq!(hello("Maelle".to_string()), "Bonjour Maelle depuis Rust");
    }

    #[test]
    fn hello_empty_defaults_to_generic_greeting() {
        assert_eq!(hello("".to_string()), "Bonjour depuis Rust");
        assert_eq!(hello("   ".to_string()), "Bonjour depuis Rust");
    }
}
