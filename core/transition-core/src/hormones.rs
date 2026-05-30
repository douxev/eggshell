//! Hormone lab measurements + unit conversions.

use rusqlite::{params, Row};

use crate::db::Database;
use crate::TransitionError;

#[derive(Clone, Debug, PartialEq)]
pub struct HormoneMeasurement {
    pub id: i64,
    pub at_ms: i64,
    pub hormone: String,
    pub value: f64,
    pub unit: String,
    pub lab_name: Option<String>,
    pub notes: Option<String>,
}

#[derive(Clone, Debug)]
pub struct NewHormoneMeasurement {
    pub at_ms: i64,
    pub hormone: String,
    pub value: f64,
    pub unit: String,
    pub lab_name: Option<String>,
    pub notes: Option<String>,
}

pub fn add(
    db: &Database,
    m: NewHormoneMeasurement,
) -> Result<HormoneMeasurement, TransitionError> {
    db.conn()
        .execute(
            "INSERT INTO hormone_measurements (at_ms, hormone, value, unit, lab_name, notes)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
            params![m.at_ms, m.hormone, m.value, m.unit, m.lab_name, m.notes],
        )
        .map_err(map_sql)?;
    let id = db.conn().last_insert_rowid();
    Ok(HormoneMeasurement {
        id,
        at_ms: m.at_ms,
        hormone: m.hormone,
        value: m.value,
        unit: m.unit,
        lab_name: m.lab_name,
        notes: m.notes,
    })
}

pub fn list_for_hormone(
    db: &Database,
    hormone: String,
    offset: i64,
    limit: i64,
) -> Result<Vec<HormoneMeasurement>, TransitionError> {
    let conn = db.conn();
    let mut stmt = conn
        .prepare(
            "SELECT id, at_ms, hormone, value, unit, lab_name, notes
             FROM hormone_measurements
             WHERE hormone = ?1
             ORDER BY at_ms ASC
             LIMIT ?2 OFFSET ?3",
        )
        .map_err(map_sql)?;
    let rows = stmt
        .query_map(params![hormone, limit, offset], parse)
        .map_err(map_sql)?
        .collect::<Result<Vec<_>, _>>()
        .map_err(map_sql)?;
    Ok(rows)
}

pub fn list_all(db: &Database) -> Result<Vec<HormoneMeasurement>, TransitionError> {
    let conn = db.conn();
    let mut stmt = conn
        .prepare(
            "SELECT id, at_ms, hormone, value, unit, lab_name, notes
             FROM hormone_measurements
             ORDER BY at_ms DESC",
        )
        .map_err(map_sql)?;
    let rows = stmt
        .query_map([], parse)
        .map_err(map_sql)?
        .collect::<Result<Vec<_>, _>>()
        .map_err(map_sql)?;
    Ok(rows)
}

pub fn distinct_hormones(db: &Database) -> Result<Vec<String>, TransitionError> {
    let conn = db.conn();
    let mut stmt = conn
        .prepare("SELECT DISTINCT hormone FROM hormone_measurements ORDER BY hormone ASC")
        .map_err(map_sql)?;
    let rows = stmt
        .query_map([], |r| r.get::<_, String>(0))
        .map_err(map_sql)?
        .collect::<Result<Vec<_>, _>>()
        .map_err(map_sql)?;
    Ok(rows)
}

pub fn delete(db: &Database, id: i64) -> Result<(), TransitionError> {
    db.conn()
        .execute("DELETE FROM hormone_measurements WHERE id = ?1", [id])
        .map_err(map_sql)?;
    Ok(())
}

/// Convert a measurement between the common hormone-lab units. Returns `None`
/// when no known conversion exists (e.g. between unrelated hormones).
///
/// Conversion factors (mass ↔ molar) follow standard endocrinology references:
/// - Estradiol     : 1 pg/mL = 3.671 pmol/L
/// - Testosterone  : 1 ng/dL = 0.0347 nmol/L
/// - Progesterone  : 1 ng/mL = 3.18 nmol/L
///
/// Mass-only and molar-only intra-conversions are exact.
pub fn convert(value: f64, from: &str, to: &str, hormone: &str) -> Option<f64> {
    if from == to {
        return Some(value);
    }
    // Mass conversion (within same kind: pg/mL ↔ ng/dL is 1 ng/dL = 10 pg/mL)
    if let Some(v) = same_kind_mass(value, from, to) {
        return Some(v);
    }
    if let Some(v) = same_kind_molar(value, from, to) {
        return Some(v);
    }
    // Cross-kind: mass ↔ molar, hormone-specific factor
    let factor = factor_mass_to_molar(hormone)?;
    match (from, to) {
        ("pg/mL", "pmol/L") => Some(value * factor),
        ("pmol/L", "pg/mL") => Some(value / factor),
        ("ng/dL", "nmol/L") => Some(value * factor / 100.0),
        ("nmol/L", "ng/dL") => Some(value * 100.0 / factor),
        ("ng/mL", "nmol/L") => Some(value * factor),
        ("nmol/L", "ng/mL") => Some(value / factor),
        ("pg/mL", "nmol/L") => Some(value * factor / 1000.0),
        ("nmol/L", "pg/mL") => Some(value * 1000.0 / factor),
        ("ng/dL", "pmol/L") => Some(value * factor * 10.0),
        ("pmol/L", "ng/dL") => Some(value / (factor * 10.0)),
        _ => None,
    }
}

fn same_kind_mass(value: f64, from: &str, to: &str) -> Option<f64> {
    // pg/mL = 1 / pg per mL ; ng/dL = 10 pg/mL ; ng/mL = 1000 pg/mL
    let to_pg_per_ml = |unit: &str| match unit {
        "pg/mL" => Some(1.0),
        "ng/dL" => Some(10.0),
        "ng/mL" => Some(1000.0),
        _ => None,
    };
    let f = to_pg_per_ml(from)?;
    let t = to_pg_per_ml(to)?;
    Some(value * f / t)
}

fn same_kind_molar(value: f64, from: &str, to: &str) -> Option<f64> {
    // pmol/L = 1 ; nmol/L = 1000 pmol/L
    let to_pmol = |unit: &str| match unit {
        "pmol/L" => Some(1.0),
        "nmol/L" => Some(1000.0),
        _ => None,
    };
    let f = to_pmol(from)?;
    let t = to_pmol(to)?;
    Some(value * f / t)
}

fn factor_mass_to_molar(hormone: &str) -> Option<f64> {
    // Returns pmol/L per pg/mL for the given hormone.
    match hormone.to_ascii_lowercase().as_str() {
        "estradiol" => Some(3.671),
        "testosterone" => Some(3.467),
        "progesterone" => Some(3.18),
        _ => None,
    }
}

fn parse(row: &Row) -> rusqlite::Result<HormoneMeasurement> {
    Ok(HormoneMeasurement {
        id: row.get(0)?,
        at_ms: row.get(1)?,
        hormone: row.get(2)?,
        value: row.get(3)?,
        unit: row.get(4)?,
        lab_name: row.get(5)?,
        notes: row.get(6)?,
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
    fn estradiol_pgml_to_pmoll() {
        let v = convert(100.0, "pg/mL", "pmol/L", "estradiol").unwrap();
        assert!((v - 367.1).abs() < 0.01);
    }

    #[test]
    fn testosterone_ngdl_to_nmoll() {
        let v = convert(500.0, "ng/dL", "nmol/L", "testosterone").unwrap();
        assert!((v - 17.335).abs() < 0.01, "got {v}");
    }

    #[test]
    fn same_unit_is_identity() {
        assert_eq!(convert(42.0, "pg/mL", "pg/mL", "estradiol"), Some(42.0));
    }

    #[test]
    fn unknown_hormone_returns_none_across_kinds() {
        assert!(convert(1.0, "pg/mL", "pmol/L", "wibble").is_none());
    }

    #[test]
    fn mass_to_mass_works_without_hormone() {
        let v = convert(1.0, "ng/mL", "pg/mL", "anything").unwrap();
        assert!((v - 1000.0).abs() < 0.001);
    }

    #[test]
    fn list_and_distinct_hormones() {
        let (_k, db) = fresh_db();
        for (t, h) in [(1, "estradiol"), (2, "estradiol"), (3, "testosterone")] {
            add(
                &db,
                NewHormoneMeasurement {
                    at_ms: t,
                    hormone: h.into(),
                    value: 100.0,
                    unit: "pg/mL".into(),
                    lab_name: None,
                    notes: None,
                },
            )
            .unwrap();
        }
        let e = list_for_hormone(&db, "estradiol".into(), 0, 10).unwrap();
        assert_eq!(e.len(), 2);
        assert_eq!(distinct_hormones(&db).unwrap(), vec!["estradiol", "testosterone"]);
    }
}
