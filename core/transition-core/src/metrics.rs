//! Shared customizable-metric model (sliders) used by both the feelings
//! journal and the bleeding/cycle tracker.
//!
//! A [`MetricDefinition`] describes one slider (label, emojis, range, order).
//! Built-in journal gauges are seeded by migration 0010 with `column_name` set
//! — their values keep living in the `journal_entries` columns. Built-in
//! bleeding gauges and every user-defined slider store their per-entry value in
//! `metric_values` (keyed by `(entry_domain, entry_id, metric_id)`).

use rusqlite::{params, Row};

use crate::db::Database;
use crate::TransitionError;

#[derive(Clone, Debug, PartialEq)]
pub struct MetricDefinition {
    pub id: i64,
    pub domain: String,
    pub metric_key: String,
    pub label: String,
    pub emoji_left: Option<String>,
    pub emoji_right: Option<String>,
    pub min_value: u32,
    pub max_value: u32,
    pub sort_order: i64,
    pub builtin: bool,
    pub column_name: Option<String>,
    pub enabled: bool,
    pub archived: bool,
    pub created_at_ms: i64,
}

#[derive(Clone, Debug)]
pub struct NewMetricDefinition {
    pub domain: String,
    pub metric_key: String,
    pub label: String,
    pub emoji_left: Option<String>,
    pub emoji_right: Option<String>,
    pub min_value: u32,
    pub max_value: u32,
    pub sort_order: i64,
    pub created_at_ms: i64,
}

/// Editable fields of a definition. Built-ins accept everything except
/// deletion; the `enabled` flag is how a built-in is "removed" from a screen.
#[derive(Clone, Debug)]
pub struct MetricDefinitionUpdate {
    pub label: String,
    pub emoji_left: Option<String>,
    pub emoji_right: Option<String>,
    pub sort_order: i64,
    pub enabled: bool,
}

/// One stored slider value for an entry. `metric_id` ties it back to a
/// [`MetricDefinition`].
#[derive(Clone, Debug, PartialEq)]
pub struct MetricValue {
    pub metric_id: i64,
    pub value: u32,
}

// -- Definitions ----------------------------------------------------------------

pub fn list_definitions(
    db: &Database,
    domain: String,
    include_archived: bool,
) -> Result<Vec<MetricDefinition>, TransitionError> {
    let sql = if include_archived {
        "SELECT id, domain, metric_key, label, emoji_left, emoji_right, min_value, max_value,
                sort_order, builtin, column_name, enabled, archived, created_at_ms
         FROM metric_definitions WHERE domain = ?1
         ORDER BY sort_order ASC, id ASC"
    } else {
        "SELECT id, domain, metric_key, label, emoji_left, emoji_right, min_value, max_value,
                sort_order, builtin, column_name, enabled, archived, created_at_ms
         FROM metric_definitions WHERE domain = ?1 AND archived = 0
         ORDER BY sort_order ASC, id ASC"
    };
    let conn = db.conn();
    let mut stmt = conn.prepare(sql).map_err(map_sql)?;
    let rows = stmt
        .query_map([domain], parse_definition)
        .map_err(map_sql)?
        .collect::<Result<Vec<_>, _>>()
        .map_err(map_sql)?;
    Ok(rows)
}

pub fn add_definition(
    db: &Database,
    def: NewMetricDefinition,
) -> Result<MetricDefinition, TransitionError> {
    db.conn()
        .execute(
            "INSERT INTO metric_definitions
                (domain, metric_key, label, emoji_left, emoji_right, min_value, max_value,
                 sort_order, builtin, column_name, enabled, archived, created_at_ms)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, 0, NULL, 1, 0, ?9)",
            params![
                def.domain, def.metric_key, def.label, def.emoji_left, def.emoji_right,
                def.min_value, def.max_value, def.sort_order, def.created_at_ms,
            ],
        )
        .map_err(map_sql)?;
    let id = db.conn().last_insert_rowid();
    Ok(MetricDefinition {
        id,
        domain: def.domain,
        metric_key: def.metric_key,
        label: def.label,
        emoji_left: def.emoji_left,
        emoji_right: def.emoji_right,
        min_value: def.min_value,
        max_value: def.max_value,
        sort_order: def.sort_order,
        builtin: false,
        column_name: None,
        enabled: true,
        archived: false,
        created_at_ms: def.created_at_ms,
    })
}

pub fn update_definition(
    db: &Database,
    id: i64,
    upd: MetricDefinitionUpdate,
) -> Result<(), TransitionError> {
    let n = db
        .conn()
        .execute(
            "UPDATE metric_definitions
             SET label = ?1, emoji_left = ?2, emoji_right = ?3, sort_order = ?4, enabled = ?5
             WHERE id = ?6",
            params![
                upd.label, upd.emoji_left, upd.emoji_right, upd.sort_order,
                upd.enabled as i64, id,
            ],
        )
        .map_err(map_sql)?;
    if n == 0 {
        return Err(TransitionError::Database(format!(
            "no metric definition with id {id}"
        )));
    }
    Ok(())
}

/// Soft delete: only custom (non-builtin) definitions can be archived. Built-in
/// gauges are protected (disable them via `update_definition` instead) so the
/// hardcoded downstream readers (Today trend, PDF) keep working.
pub fn archive_definition(db: &Database, id: i64) -> Result<(), TransitionError> {
    let n = db
        .conn()
        .execute(
            "UPDATE metric_definitions SET archived = 1 WHERE id = ?1 AND builtin = 0",
            [id],
        )
        .map_err(map_sql)?;
    if n == 0 {
        return Err(TransitionError::Database(
            "metric definition not found or is built-in (cannot delete)".into(),
        ));
    }
    Ok(())
}

// -- Values ---------------------------------------------------------------------

pub fn list_values(
    db: &Database,
    entry_domain: String,
    entry_id: i64,
) -> Result<Vec<MetricValue>, TransitionError> {
    let conn = db.conn();
    let mut stmt = conn
        .prepare(
            "SELECT metric_id, value FROM metric_values
             WHERE entry_domain = ?1 AND entry_id = ?2",
        )
        .map_err(map_sql)?;
    let rows = stmt
        .query_map(params![entry_domain, entry_id], |row| {
            Ok(MetricValue {
                metric_id: row.get(0)?,
                value: row.get(1)?,
            })
        })
        .map_err(map_sql)?
        .collect::<Result<Vec<_>, _>>()
        .map_err(map_sql)?;
    Ok(rows)
}

/// Replace every stored value for an entry in one shot. Mirrors the
/// delete-then-insert edit model used elsewhere, but keeps the parent entry id
/// stable (the parent is updated in place via its own `update`).
pub fn replace_values(
    db: &Database,
    entry_domain: String,
    entry_id: i64,
    values: Vec<MetricValue>,
) -> Result<(), TransitionError> {
    let tx = db.conn().unchecked_transaction().map_err(map_sql)?;
    tx.execute(
        "DELETE FROM metric_values WHERE entry_domain = ?1 AND entry_id = ?2",
        params![entry_domain, entry_id],
    )
    .map_err(map_sql)?;
    {
        let mut stmt = tx
            .prepare(
                "INSERT INTO metric_values (entry_domain, entry_id, metric_id, value)
                 VALUES (?1, ?2, ?3, ?4)",
            )
            .map_err(map_sql)?;
        for v in &values {
            stmt.execute(params![entry_domain, entry_id, v.metric_id, v.value])
                .map_err(map_sql)?;
        }
    }
    tx.commit().map_err(map_sql)?;
    Ok(())
}

fn parse_definition(row: &Row) -> rusqlite::Result<MetricDefinition> {
    Ok(MetricDefinition {
        id: row.get(0)?,
        domain: row.get(1)?,
        metric_key: row.get(2)?,
        label: row.get(3)?,
        emoji_left: row.get(4)?,
        emoji_right: row.get(5)?,
        min_value: row.get(6)?,
        max_value: row.get(7)?,
        sort_order: row.get(8)?,
        builtin: row.get::<_, i64>(9)? != 0,
        column_name: row.get(10)?,
        enabled: row.get::<_, i64>(11)? != 0,
        archived: row.get::<_, i64>(12)? != 0,
        created_at_ms: row.get(13)?,
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
    fn builtins_are_seeded() {
        let (_k, db) = fresh_db();
        let journal = list_definitions(&db, "journal".into(), false).unwrap();
        assert_eq!(journal.len(), 5);
        assert_eq!(journal[0].metric_key, "mood");
        assert!(journal[0].builtin);
        assert_eq!(journal[0].column_name.as_deref(), Some("mood"));
        let bleeding = list_definitions(&db, "bleeding".into(), false).unwrap();
        assert_eq!(bleeding.len(), 3);
        assert_eq!(bleeding[0].metric_key, "flow");
        assert!(bleeding[0].column_name.is_none());
    }

    #[test]
    fn add_update_archive_custom_definition() {
        let (_k, db) = fresh_db();
        let def = add_definition(
            &db,
            NewMetricDefinition {
                domain: "journal".into(),
                metric_key: "custom_1".into(),
                label: "Sleep".into(),
                emoji_left: Some("😴".into()),
                emoji_right: Some("⚡".into()),
                min_value: 0,
                max_value: 10,
                sort_order: 5,
                created_at_ms: 1_000,
            },
        )
        .unwrap();
        assert!(!def.builtin);
        update_definition(
            &db,
            def.id,
            MetricDefinitionUpdate {
                label: "Sleep quality".into(),
                emoji_left: Some("😴".into()),
                emoji_right: Some("⚡".into()),
                sort_order: 6,
                enabled: true,
            },
        )
        .unwrap();
        let got = list_definitions(&db, "journal".into(), false)
            .unwrap()
            .into_iter()
            .find(|d| d.id == def.id)
            .unwrap();
        assert_eq!(got.label, "Sleep quality");
        assert_eq!(got.sort_order, 6);
        archive_definition(&db, def.id).unwrap();
        assert!(list_definitions(&db, "journal".into(), false)
            .unwrap()
            .iter()
            .all(|d| d.id != def.id));
        assert!(list_definitions(&db, "journal".into(), true)
            .unwrap()
            .iter()
            .any(|d| d.id == def.id));
    }

    #[test]
    fn builtin_cannot_be_archived() {
        let (_k, db) = fresh_db();
        let mood = list_definitions(&db, "journal".into(), false).unwrap()[0].id;
        assert!(archive_definition(&db, mood).is_err());
    }

    #[test]
    fn replace_and_list_values() {
        let (_k, db) = fresh_db();
        let def = add_definition(
            &db,
            NewMetricDefinition {
                domain: "bleeding".into(),
                metric_key: "custom_1".into(),
                label: "Bloating".into(),
                emoji_left: None,
                emoji_right: None,
                min_value: 0,
                max_value: 10,
                sort_order: 5,
                created_at_ms: 1_000,
            },
        )
        .unwrap();
        replace_values(
            &db,
            "bleeding".into(),
            42,
            vec![MetricValue { metric_id: def.id, value: 7 }],
        )
        .unwrap();
        let vals = list_values(&db, "bleeding".into(), 42).unwrap();
        assert_eq!(vals.len(), 1);
        assert_eq!(vals[0].value, 7);
        // Replace overwrites.
        replace_values(
            &db,
            "bleeding".into(),
            42,
            vec![MetricValue { metric_id: def.id, value: 3 }],
        )
        .unwrap();
        assert_eq!(list_values(&db, "bleeding".into(), 42).unwrap()[0].value, 3);
        // Empty clears.
        replace_values(&db, "bleeding".into(), 42, vec![]).unwrap();
        assert!(list_values(&db, "bleeding".into(), 42).unwrap().is_empty());
    }
}
