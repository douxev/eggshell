//! Reminder schedules. Storage-only — actual alarm wake-up and TZ math live
//! on the native side.

use rusqlite::{params, Row};

use crate::db::Database;
use crate::TransitionError;

#[derive(Clone, Debug, PartialEq)]
pub struct DoseSchedule {
    pub id: i64,
    pub medication_id: i64,
    pub kind: String,
    pub interval_minutes: Option<u32>,
    pub daily_hour: Option<u32>,
    pub daily_minute: Option<u32>,
    pub next_due_at_ms: i64,
    pub active: bool,
    pub created_at_ms: i64,
}

#[derive(Clone, Debug)]
pub struct NewDoseSchedule {
    pub medication_id: i64,
    pub kind: String,
    pub interval_minutes: Option<u32>,
    pub daily_hour: Option<u32>,
    pub daily_minute: Option<u32>,
    pub next_due_at_ms: i64,
}

pub fn add(
    db: &Database,
    s: NewDoseSchedule,
    now_ms: i64,
) -> Result<DoseSchedule, TransitionError> {
    validate(&s)?;
    db.conn()
        .execute(
            "INSERT INTO dose_schedules
                (medication_id, kind, interval_minutes, daily_hour, daily_minute,
                 next_due_at_ms, active, created_at_ms)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, 1, ?7)",
            params![
                s.medication_id, s.kind, s.interval_minutes,
                s.daily_hour, s.daily_minute, s.next_due_at_ms, now_ms,
            ],
        )
        .map_err(map_sql)?;
    let id = db.conn().last_insert_rowid();
    Ok(DoseSchedule {
        id,
        medication_id: s.medication_id,
        kind: s.kind,
        interval_minutes: s.interval_minutes,
        daily_hour: s.daily_hour,
        daily_minute: s.daily_minute,
        next_due_at_ms: s.next_due_at_ms,
        active: true,
        created_at_ms: now_ms,
    })
}

pub fn list_active(db: &Database) -> Result<Vec<DoseSchedule>, TransitionError> {
    let conn = db.conn();
    let mut stmt = conn
        .prepare(
            "SELECT id, medication_id, kind, interval_minutes, daily_hour, daily_minute,
                    next_due_at_ms, active, created_at_ms
             FROM dose_schedules
             WHERE active = 1
             ORDER BY next_due_at_ms ASC",
        )
        .map_err(map_sql)?;
    let rows = stmt
        .query_map([], parse)
        .map_err(map_sql)?
        .collect::<Result<Vec<_>, _>>()
        .map_err(map_sql)?;
    Ok(rows)
}

pub fn list_for_medication(
    db: &Database,
    medication_id: i64,
    include_inactive: bool,
) -> Result<Vec<DoseSchedule>, TransitionError> {
    let sql = if include_inactive {
        "SELECT id, medication_id, kind, interval_minutes, daily_hour, daily_minute,
                next_due_at_ms, active, created_at_ms
         FROM dose_schedules
         WHERE medication_id = ?1
         ORDER BY active DESC, next_due_at_ms ASC"
    } else {
        "SELECT id, medication_id, kind, interval_minutes, daily_hour, daily_minute,
                next_due_at_ms, active, created_at_ms
         FROM dose_schedules
         WHERE medication_id = ?1 AND active = 1
         ORDER BY next_due_at_ms ASC"
    };
    let conn = db.conn();
    let mut stmt = conn.prepare(sql).map_err(map_sql)?;
    let rows = stmt
        .query_map([medication_id], parse)
        .map_err(map_sql)?
        .collect::<Result<Vec<_>, _>>()
        .map_err(map_sql)?;
    Ok(rows)
}

pub fn get(db: &Database, id: i64) -> Result<Option<DoseSchedule>, TransitionError> {
    db.conn()
        .query_row(
            "SELECT id, medication_id, kind, interval_minutes, daily_hour, daily_minute,
                    next_due_at_ms, active, created_at_ms
             FROM dose_schedules WHERE id = ?1",
            [id],
            parse,
        )
        .map(Some)
        .or_else(|e| match e {
            rusqlite::Error::QueryReturnedNoRows => Ok(None),
            other => Err(map_sql(other)),
        })
}

pub fn set_active(db: &Database, id: i64, active: bool) -> Result<(), TransitionError> {
    db.conn()
        .execute(
            "UPDATE dose_schedules SET active = ?1 WHERE id = ?2",
            params![active as i64, id],
        )
        .map_err(map_sql)?;
    Ok(())
}

pub fn set_next_due(db: &Database, id: i64, next_due_at_ms: i64) -> Result<(), TransitionError> {
    db.conn()
        .execute(
            "UPDATE dose_schedules SET next_due_at_ms = ?1 WHERE id = ?2",
            params![next_due_at_ms, id],
        )
        .map_err(map_sql)?;
    Ok(())
}

fn validate(s: &NewDoseSchedule) -> Result<(), TransitionError> {
    match s.kind.as_str() {
        "interval" => {
            if s.interval_minutes.is_none() || s.interval_minutes == Some(0) {
                return Err(TransitionError::Database(
                    "interval schedules require a positive interval_minutes".into(),
                ));
            }
        }
        "daily" => {
            let h = s.daily_hour.ok_or_else(|| {
                TransitionError::Database("daily schedules require daily_hour".into())
            })?;
            let m = s.daily_minute.ok_or_else(|| {
                TransitionError::Database("daily schedules require daily_minute".into())
            })?;
            if h > 23 || m > 59 {
                return Err(TransitionError::Database(
                    "daily_hour must be 0-23 and daily_minute 0-59".into(),
                ));
            }
        }
        other => {
            return Err(TransitionError::Database(format!(
                "unknown schedule kind: {other}"
            )));
        }
    }
    Ok(())
}

fn parse(row: &Row) -> rusqlite::Result<DoseSchedule> {
    Ok(DoseSchedule {
        id: row.get(0)?,
        medication_id: row.get(1)?,
        kind: row.get(2)?,
        interval_minutes: row.get::<_, Option<u32>>(3)?,
        daily_hour: row.get::<_, Option<u32>>(4)?,
        daily_minute: row.get::<_, Option<u32>>(5)?,
        next_due_at_ms: row.get(6)?,
        active: row.get::<_, i64>(7)? != 0,
        created_at_ms: row.get(8)?,
    })
}

fn map_sql(err: rusqlite::Error) -> TransitionError {
    crate::sanitize_db_err(err)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::crypto::MasterKey;
    use crate::medication::{add as add_med, NewMedication};
    use tempfile::NamedTempFile;

    fn fresh_db() -> (tempfile::NamedTempFile, Database) {
        let f = NamedTempFile::new().unwrap();
        let path = f.path().to_path_buf();
        drop(f);
        let _ = std::fs::remove_file(&path);
        let db = Database::open(&path, &MasterKey::generate()).unwrap();
        let owner = NamedTempFile::new_in(path.parent().unwrap()).unwrap();
        (owner, db)
    }

    fn sample_med(db: &Database) -> i64 {
        add_med(
            db,
            NewMedication {
                name: "Estradiol gel".into(),
                kind: "estrogen".into(),
                route: "transdermal".into(),
                default_dose: Some(0.1),
                default_dose_unit: Some("mg".into()),
                color: None,
                notes: None,
            },
            1_000,
        )
        .unwrap()
        .id
    }

    #[test]
    fn add_interval_schedule() {
        let (_k, db) = fresh_db();
        let med_id = sample_med(&db);
        let s = add(
            &db,
            NewDoseSchedule {
                medication_id: med_id,
                kind: "interval".into(),
                interval_minutes: Some(720),
                daily_hour: None,
                daily_minute: None,
                next_due_at_ms: 1_000_000,
            },
            500,
        )
        .unwrap();
        assert!(s.id > 0);
        assert!(s.active);
        let active = list_active(&db).unwrap();
        assert_eq!(active.len(), 1);
        assert_eq!(active[0].interval_minutes, Some(720));
    }

    #[test]
    fn add_daily_schedule() {
        let (_k, db) = fresh_db();
        let med_id = sample_med(&db);
        add(
            &db,
            NewDoseSchedule {
                medication_id: med_id,
                kind: "daily".into(),
                interval_minutes: None,
                daily_hour: Some(8),
                daily_minute: Some(0),
                next_due_at_ms: 1_500_000,
            },
            500,
        )
        .unwrap();
        let list = list_for_medication(&db, med_id, false).unwrap();
        assert_eq!(list.len(), 1);
        assert_eq!(list[0].daily_hour, Some(8));
        assert_eq!(list[0].daily_minute, Some(0));
    }

    #[test]
    fn validate_rejects_missing_fields() {
        let (_k, db) = fresh_db();
        let med_id = sample_med(&db);
        let err = add(
            &db,
            NewDoseSchedule {
                medication_id: med_id,
                kind: "interval".into(),
                interval_minutes: None,
                daily_hour: None,
                daily_minute: None,
                next_due_at_ms: 1_000_000,
            },
            500,
        );
        assert!(err.is_err());
    }

    #[test]
    fn validate_rejects_out_of_range_hours() {
        let (_k, db) = fresh_db();
        let med_id = sample_med(&db);
        let err = add(
            &db,
            NewDoseSchedule {
                medication_id: med_id,
                kind: "daily".into(),
                interval_minutes: None,
                daily_hour: Some(25),
                daily_minute: Some(0),
                next_due_at_ms: 1_500_000,
            },
            500,
        );
        assert!(err.is_err());
    }

    #[test]
    fn set_active_and_set_next_due() {
        let (_k, db) = fresh_db();
        let med_id = sample_med(&db);
        let s = add(
            &db,
            NewDoseSchedule {
                medication_id: med_id,
                kind: "interval".into(),
                interval_minutes: Some(60),
                daily_hour: None,
                daily_minute: None,
                next_due_at_ms: 10_000,
            },
            500,
        )
        .unwrap();

        set_next_due(&db, s.id, 20_000).unwrap();
        assert_eq!(get(&db, s.id).unwrap().unwrap().next_due_at_ms, 20_000);

        set_active(&db, s.id, false).unwrap();
        assert!(list_active(&db).unwrap().is_empty());
        assert_eq!(list_for_medication(&db, med_id, true).unwrap().len(), 1);
    }

    #[test]
    fn cascade_on_medication_delete() {
        let (_k, db) = fresh_db();
        let med_id = sample_med(&db);
        add(
            &db,
            NewDoseSchedule {
                medication_id: med_id,
                kind: "interval".into(),
                interval_minutes: Some(60),
                daily_hour: None,
                daily_minute: None,
                next_due_at_ms: 10_000,
            },
            500,
        )
        .unwrap();
        db.conn()
            .execute("DELETE FROM medications WHERE id = ?1", [med_id])
            .unwrap();
        assert!(list_active(&db).unwrap().is_empty());
    }

    #[test]
    fn list_active_orders_by_next_due() {
        let (_k, db) = fresh_db();
        let med_id = sample_med(&db);
        for ms in [30_000, 10_000, 20_000] {
            add(
                &db,
                NewDoseSchedule {
                    medication_id: med_id,
                    kind: "interval".into(),
                    interval_minutes: Some(60),
                    daily_hour: None,
                    daily_minute: None,
                    next_due_at_ms: ms,
                },
                500,
            )
            .unwrap();
        }
        let xs = list_active(&db).unwrap();
        let due: Vec<_> = xs.iter().map(|s| s.next_due_at_ms).collect();
        assert_eq!(due, vec![10_000, 20_000, 30_000]);
    }
}
