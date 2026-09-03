//! Sport: physical-activity sessions, an editable activity catalogue, and
//! daily step totals.
//!
//! Three ideas, deliberately kept apart:
//!
//! - An **activity** is a kind of thing you do. The catalogue is the user's,
//!   not the app's — walking to the shops and a powerlifting set are both sport
//!   to the person doing them.
//! - A **session** is one occurrence: when, how long, and whatever they want to
//!   say about it. It survives the deletion of its activity type (the foreign
//!   key is `ON DELETE SET NULL`), because months of someone's training history
//!   is not something a tidy-up of a dropdown gets to erase.
//! - A **step day** is one local calendar day's total. Keyed by `YYYY-MM-DD`
//!   rather than by a timestamp, because that is the unit both the user and a
//!   calendar think in, and because a timestamp lands in the wrong bucket the
//!   first time a timezone changes.
//!
//! Sliders (effort, sensation, anything the user invents) are not here. They
//! live in `metric_values` with `entry_domain = 'sport'`, the idiom the
//! bleeding module already uses, so a new one costs no schema.

use rusqlite::{params, Row};

use crate::db::Database;
use crate::TransitionError;

#[derive(Clone, Debug, PartialEq)]
pub struct SportActivity {
    pub id: i64,
    pub name: String,
    /// "cardio" | "strength" | "mobility" | "other". Not constrained in SQL —
    /// see the migration for why a closed vocabulary would be a liability.
    pub kind: String,
    pub color: Option<i64>,
    pub archived: bool,
    pub created_ms: i64,
}

#[derive(Clone, Debug)]
pub struct NewSportActivity {
    pub name: String,
    pub kind: String,
    pub color: Option<i64>,
}

#[derive(Clone, Debug, PartialEq)]
pub struct SportSession {
    pub id: i64,
    /// None once the activity type it referred to has been deleted. The session
    /// itself is never deleted by that.
    pub activity_id: Option<i64>,
    pub started_ms: i64,
    pub duration_s: i64,
    pub free_text: Option<String>,
    /// Metres. None for anything that has no distance — a strength set, a
    /// session typed in by hand.
    pub distance_m: Option<f64>,
    /// Average and peak heart rate in bpm, when a sensor was connected for the
    /// session. Null everywhere else, which is most rows.
    pub avg_hr: Option<i64>,
    pub max_hr: Option<i64>,
    /// "manual" | "pedometer" | "watch".
    pub source: String,
}

#[derive(Clone, Debug)]
pub struct NewSportSession {
    pub activity_id: Option<i64>,
    pub started_ms: i64,
    pub duration_s: i64,
    pub free_text: Option<String>,
    pub distance_m: Option<f64>,
    pub avg_hr: Option<i64>,
    pub max_hr: Option<i64>,
    pub source: String,
}

/// One local calendar day's step total.
#[derive(Clone, Debug, PartialEq)]
pub struct StepDay {
    /// `YYYY-MM-DD`, in the device's local time. Built by the native layer,
    /// which is the only side that knows the user's timezone.
    pub day_key: String,
    pub steps: i64,
    pub updated_ms: i64,
}

// -- Activities ---------------------------------------------------------------

pub fn add_activity(
    db: &Database,
    a: NewSportActivity,
    now_ms: i64,
) -> Result<SportActivity, TransitionError> {
    db.conn()
        .execute(
            "INSERT INTO sport_activities (name, kind, color, archived, created_ms)
             VALUES (?1, ?2, ?3, 0, ?4)",
            params![a.name, a.kind, a.color, now_ms],
        )
        .map_err(map_sql)?;
    Ok(SportActivity {
        id: db.conn().last_insert_rowid(),
        name: a.name,
        kind: a.kind,
        color: a.color,
        archived: false,
        created_ms: now_ms,
    })
}

pub fn list_activities(
    db: &Database,
    include_archived: bool,
) -> Result<Vec<SportActivity>, TransitionError> {
    let conn = db.conn();
    let sql = if include_archived {
        "SELECT id, name, kind, color, archived, created_ms FROM sport_activities
         ORDER BY archived ASC, name COLLATE NOCASE ASC"
    } else {
        "SELECT id, name, kind, color, archived, created_ms FROM sport_activities
         WHERE archived = 0 ORDER BY name COLLATE NOCASE ASC"
    };
    let mut stmt = conn.prepare(sql).map_err(map_sql)?;
    let rows = stmt.query_map([], parse_activity).map_err(map_sql)?;
    rows.collect::<Result<_, _>>().map_err(map_sql)
}

pub fn update_activity(
    db: &Database,
    id: i64,
    a: NewSportActivity,
) -> Result<(), TransitionError> {
    let n = db
        .conn()
        .execute(
            "UPDATE sport_activities SET name = ?1, kind = ?2, color = ?3 WHERE id = ?4",
            params![a.name, a.kind, a.color, id],
        )
        .map_err(map_sql)?;
    // A no-op UPDATE is otherwise indistinguishable from success across the FFI.
    if n == 0 {
        return Err(TransitionError::Database(format!("no activity with id {id}")));
    }
    Ok(())
}

/// Hide an activity without touching a single session.
///
/// Archiving rather than deleting is the default the UI should offer: the
/// sessions keep their type, and the type can come back. [`delete_activity`]
/// exists for the case where the user genuinely wants the name gone.
pub fn set_activity_archived(
    db: &Database,
    id: i64,
    archived: bool,
) -> Result<(), TransitionError> {
    db.conn()
        .execute(
            "UPDATE sport_activities SET archived = ?1 WHERE id = ?2",
            params![if archived { 1 } else { 0 }, id],
        )
        .map_err(map_sql)?;
    Ok(())
}

/// Delete an activity type. Its sessions survive with `activity_id = NULL`.
pub fn delete_activity(db: &Database, id: i64) -> Result<(), TransitionError> {
    db.conn()
        .execute("DELETE FROM sport_activities WHERE id = ?1", params![id])
        .map_err(map_sql)?;
    Ok(())
}

// -- Sessions -----------------------------------------------------------------

pub fn add_session(
    db: &Database,
    s: NewSportSession,
) -> Result<SportSession, TransitionError> {
    db.conn()
        .execute(
            "INSERT INTO sport_sessions
                 (activity_id, started_ms, duration_s, free_text, distance_m,
                  avg_hr, max_hr, source)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)",
            params![
                s.activity_id, s.started_ms, s.duration_s, s.free_text, s.distance_m,
                s.avg_hr, s.max_hr, s.source
            ],
        )
        .map_err(map_sql)?;
    Ok(SportSession {
        id: db.conn().last_insert_rowid(),
        activity_id: s.activity_id,
        started_ms: s.started_ms,
        duration_s: s.duration_s,
        free_text: s.free_text,
        distance_m: s.distance_m,
        avg_hr: s.avg_hr,
        max_hr: s.max_hr,
        source: s.source,
    })
}

/// Sessions in `[from_ms, to_ms)`, most recent first.
///
/// Half-open on purpose: the dashboard asks for "this week" and "last week"
/// back to back, and a closed interval would count the boundary session twice.
pub fn list_sessions_between(
    db: &Database,
    from_ms: i64,
    to_ms: i64,
) -> Result<Vec<SportSession>, TransitionError> {
    let conn = db.conn();
    let mut stmt = conn
        .prepare(
            "SELECT id, activity_id, started_ms, duration_s, free_text, distance_m,
                    avg_hr, max_hr, source
             FROM sport_sessions WHERE started_ms >= ?1 AND started_ms < ?2
             ORDER BY started_ms DESC",
        )
        .map_err(map_sql)?;
    let rows = stmt.query_map(params![from_ms, to_ms], parse_session).map_err(map_sql)?;
    rows.collect::<Result<_, _>>().map_err(map_sql)
}

pub fn list_sessions(
    db: &Database,
    offset: i64,
    limit: i64,
) -> Result<Vec<SportSession>, TransitionError> {
    let conn = db.conn();
    let mut stmt = conn
        .prepare(
            "SELECT id, activity_id, started_ms, duration_s, free_text, distance_m,
                    avg_hr, max_hr, source
             FROM sport_sessions ORDER BY started_ms DESC LIMIT ?1 OFFSET ?2",
        )
        .map_err(map_sql)?;
    let rows = stmt.query_map(params![limit, offset], parse_session).map_err(map_sql)?;
    rows.collect::<Result<_, _>>().map_err(map_sql)
}

pub fn get_session(db: &Database, id: i64) -> Result<Option<SportSession>, TransitionError> {
    let conn = db.conn();
    match conn.query_row(
        "SELECT id, activity_id, started_ms, duration_s, free_text, distance_m,
                    avg_hr, max_hr, source
         FROM sport_sessions WHERE id = ?1",
        params![id],
        parse_session,
    ) {
        Ok(s) => Ok(Some(s)),
        Err(rusqlite::Error::QueryReturnedNoRows) => Ok(None),
        Err(e) => Err(map_sql(e)),
    }
}

/// Overwrite every editable field. The native side reads, lets the user edit,
/// and passes the whole record back — so this is a full overwrite, like
/// medications, not a patch.
pub fn update_session(
    db: &Database,
    id: i64,
    s: NewSportSession,
) -> Result<SportSession, TransitionError> {
    let n = db
        .conn()
        .execute(
            "UPDATE sport_sessions
             SET activity_id = ?1, started_ms = ?2, duration_s = ?3, free_text = ?4,
                 distance_m = ?5, avg_hr = ?6, max_hr = ?7, source = ?8
             WHERE id = ?9",
            params![
                s.activity_id, s.started_ms, s.duration_s, s.free_text, s.distance_m,
                s.avg_hr, s.max_hr, s.source, id
            ],
        )
        .map_err(map_sql)?;
    if n == 0 {
        return Err(TransitionError::Database(format!("no session with id {id}")));
    }
    get_session(db, id)?
        .ok_or_else(|| TransitionError::Database(format!("no session with id {id}")))
}

pub fn delete_session(db: &Database, id: i64) -> Result<(), TransitionError> {
    db.conn()
        .execute("DELETE FROM sport_sessions WHERE id = ?1", params![id])
        .map_err(map_sql)?;
    Ok(())
}

// -- Steps --------------------------------------------------------------------

/// Record a day's step total, replacing whatever was there.
///
/// The counter is cumulative and read repeatedly through the day, so this is
/// called many times per day with a growing number. It takes the **larger** of
/// the stored and incoming values rather than blindly overwriting: a device
/// reboot resets the hardware counter, and the native layer's first read after
/// one is a small number that would otherwise wipe the day's total.
pub fn record_steps(
    db: &Database,
    day_key: &str,
    steps: i64,
    now_ms: i64,
) -> Result<(), TransitionError> {
    db.conn()
        .execute(
            "INSERT INTO sport_step_days (day_key, steps, updated_ms)
             VALUES (?1, ?2, ?3)
             ON CONFLICT(day_key) DO UPDATE SET
                 steps = MAX(steps, excluded.steps),
                 updated_ms = excluded.updated_ms",
            params![day_key, steps, now_ms],
        )
        .map_err(map_sql)?;
    Ok(())
}

/// Overwrite a day's total outright, ignoring what is stored.
///
/// The escape hatch [`record_steps`] deliberately does not give: a user
/// correcting a day upward is served by the MAX, a user correcting one *down*
/// (the phone spent an afternoon in a tumble dryer) is not, and would otherwise
/// have no way to fix it.
pub fn set_steps(
    db: &Database,
    day_key: &str,
    steps: i64,
    now_ms: i64,
) -> Result<(), TransitionError> {
    db.conn()
        .execute(
            "INSERT INTO sport_step_days (day_key, steps, updated_ms)
             VALUES (?1, ?2, ?3)
             ON CONFLICT(day_key) DO UPDATE SET
                 steps = excluded.steps,
                 updated_ms = excluded.updated_ms",
            params![day_key, steps, now_ms],
        )
        .map_err(map_sql)?;
    Ok(())
}

/// Step days in `[from_key, to_key]`, oldest first.
///
/// Inclusive both ends, unlike the session range: these are day labels the
/// caller already computed, not instants, and "Monday to Sunday" means both.
/// Lexicographic comparison is chronological for `YYYY-MM-DD`, which is why
/// that format and not a friendlier one.
pub fn list_step_days(
    db: &Database,
    from_key: &str,
    to_key: &str,
) -> Result<Vec<StepDay>, TransitionError> {
    let conn = db.conn();
    let mut stmt = conn
        .prepare(
            "SELECT day_key, steps, updated_ms FROM sport_step_days
             WHERE day_key >= ?1 AND day_key <= ?2 ORDER BY day_key ASC",
        )
        .map_err(map_sql)?;
    let rows = stmt.query_map(params![from_key, to_key], parse_step_day).map_err(map_sql)?;
    rows.collect::<Result<_, _>>().map_err(map_sql)
}

pub fn get_step_day(db: &Database, day_key: &str) -> Result<Option<StepDay>, TransitionError> {
    let conn = db.conn();
    match conn.query_row(
        "SELECT day_key, steps, updated_ms FROM sport_step_days WHERE day_key = ?1",
        params![day_key],
        parse_step_day,
    ) {
        Ok(d) => Ok(Some(d)),
        Err(rusqlite::Error::QueryReturnedNoRows) => Ok(None),
        Err(e) => Err(map_sql(e)),
    }
}

// -- Row parsing --------------------------------------------------------------

fn parse_activity(r: &Row<'_>) -> rusqlite::Result<SportActivity> {
    Ok(SportActivity {
        id: r.get(0)?,
        name: r.get(1)?,
        kind: r.get(2)?,
        color: r.get(3)?,
        archived: r.get::<_, i64>(4)? != 0,
        created_ms: r.get(5)?,
    })
}

fn parse_session(r: &Row<'_>) -> rusqlite::Result<SportSession> {
    Ok(SportSession {
        id: r.get(0)?,
        activity_id: r.get(1)?,
        started_ms: r.get(2)?,
        duration_s: r.get(3)?,
        free_text: r.get(4)?,
        distance_m: r.get(5)?,
        avg_hr: r.get(6)?,
        max_hr: r.get(7)?,
        source: r.get(8)?,
    })
}

fn parse_step_day(r: &Row<'_>) -> rusqlite::Result<StepDay> {
    Ok(StepDay {
        day_key: r.get(0)?,
        steps: r.get(1)?,
        updated_ms: r.get(2)?,
    })
}

fn map_sql(e: rusqlite::Error) -> TransitionError {
    crate::sanitize_db_err(e)
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

    fn activity(db: &Database, name: &str) -> SportActivity {
        add_activity(
            db,
            NewSportActivity { name: name.into(), kind: "cardio".into(), color: None },
            1_700_000_000_000,
        )
        .unwrap()
    }

    fn session(db: &Database, activity_id: Option<i64>, started_ms: i64) -> SportSession {
        add_session(
            db,
            NewSportSession {
                activity_id,
                started_ms,
                duration_s: 1800,
                free_text: None,
                distance_m: None,
                avg_hr: None,
                max_hr: None,
                source: "manual".into(),
            },
        )
        .unwrap()
    }

    /// The rule the whole table shape exists for: tidying up the activity
    /// dropdown must not erase months of training history.
    #[test]
    fn deleting_an_activity_keeps_its_sessions() {
        let (db, _f) = fresh_db();
        let a = activity(&db, "Course");
        session(&db, Some(a.id), 1_700_000_000_000);
        session(&db, Some(a.id), 1_700_086_400_000);

        delete_activity(&db, a.id).unwrap();

        let kept = list_sessions(&db, 0, 10).unwrap();
        assert_eq!(kept.len(), 2, "sessions must outlive their activity type");
        assert!(
            kept.iter().all(|s| s.activity_id.is_none()),
            "an orphaned session points at nothing rather than at a dead id",
        );
    }

    #[test]
    fn archiving_hides_an_activity_without_touching_anything() {
        let (db, _f) = fresh_db();
        let a = activity(&db, "Vélo");
        session(&db, Some(a.id), 1_700_000_000_000);

        set_activity_archived(&db, a.id, true).unwrap();

        assert!(list_activities(&db, false).unwrap().is_empty());
        assert_eq!(list_activities(&db, true).unwrap().len(), 1);
        assert_eq!(
            list_sessions(&db, 0, 10).unwrap()[0].activity_id,
            Some(a.id),
            "archiving is not deleting: the link survives",
        );
    }

    /// Half-open, so "this week" and "last week" asked back to back do not both
    /// count the session sitting on the boundary.
    #[test]
    fn the_session_range_is_half_open() {
        let (db, _f) = fresh_db();
        session(&db, None, 1_000);
        session(&db, None, 2_000);
        session(&db, None, 3_000);

        let first = list_sessions_between(&db, 1_000, 2_000).unwrap();
        let second = list_sessions_between(&db, 2_000, 3_000).unwrap();
        assert_eq!(first.len(), 1);
        assert_eq!(second.len(), 1);
        assert_ne!(first[0].id, second[0].id, "no session in two adjacent windows");
    }

    /// The hardware step counter resets to zero on reboot, so the first read
    /// afterwards is a small number. Letting it overwrite would silently erase
    /// the day's walking — which is exactly the invisible kind of loss.
    #[test]
    fn a_step_total_is_never_lowered_by_a_counter_reset() {
        let (db, _f) = fresh_db();
        record_steps(&db, "2026-09-03", 8_000, 1).unwrap();
        record_steps(&db, "2026-09-03", 120, 2).unwrap();

        assert_eq!(get_step_day(&db, "2026-09-03").unwrap().unwrap().steps, 8_000);
    }

    #[test]
    fn steps_still_climb_through_the_day() {
        let (db, _f) = fresh_db();
        record_steps(&db, "2026-09-03", 1_000, 1).unwrap();
        record_steps(&db, "2026-09-03", 4_500, 2).unwrap();

        let day = get_step_day(&db, "2026-09-03").unwrap().unwrap();
        assert_eq!(day.steps, 4_500);
        assert_eq!(day.updated_ms, 2);
    }

    /// The escape hatch the MAX rule would otherwise take away: a day the phone
    /// spent in a bag being shaken can only be corrected downward.
    #[test]
    fn a_day_can_still_be_corrected_downward_on_purpose() {
        let (db, _f) = fresh_db();
        record_steps(&db, "2026-09-03", 40_000, 1).unwrap();
        set_steps(&db, "2026-09-03", 2_000, 2).unwrap();

        assert_eq!(get_step_day(&db, "2026-09-03").unwrap().unwrap().steps, 2_000);
    }

    /// `YYYY-MM-DD` is chosen so that string order is date order; the range
    /// query depends on it, including across a year boundary.
    #[test]
    fn step_days_read_back_in_date_order_across_a_year_boundary() {
        let (db, _f) = fresh_db();
        for key in ["2026-12-30", "2027-01-02", "2026-12-31", "2027-01-01"] {
            record_steps(&db, key, 100, 1).unwrap();
        }

        let days = list_step_days(&db, "2026-12-31", "2027-01-01").unwrap();
        assert_eq!(
            days.iter().map(|d| d.day_key.as_str()).collect::<Vec<_>>(),
            vec!["2026-12-31", "2027-01-01"],
            "inclusive both ends, and chronological",
        );
    }

    #[test]
    fn updating_a_missing_session_is_an_error_not_a_silent_no_op() {
        let (db, _f) = fresh_db();
        let err = update_session(
            &db,
            999,
            NewSportSession {
                activity_id: None,
                started_ms: 1,
                duration_s: 1,
                free_text: None,
                distance_m: None,
                avg_hr: None,
                max_hr: None,
                source: "manual".into(),
            },
        );
        assert!(err.is_err());
    }

    #[test]
    fn a_session_round_trips_through_an_update() {
        let (db, _f) = fresh_db();
        let a = activity(&db, "Natation");
        let s = session(&db, None, 1_700_000_000_000);

        let updated = update_session(
            &db,
            s.id,
            NewSportSession {
                activity_id: Some(a.id),
                started_ms: 1_700_000_600_000,
                duration_s: 3600,
                free_text: Some("bassin de 50 m".into()),
                distance_m: Some(1_500.0),
                avg_hr: Some(142),
                max_hr: Some(171),
                source: "manual".into(),
            },
        )
        .unwrap();

        assert_eq!(updated.activity_id, Some(a.id));
        assert_eq!(updated.duration_s, 3600);
        assert_eq!(updated.free_text.as_deref(), Some("bassin de 50 m"));
        assert_eq!(updated.distance_m, Some(1_500.0));
        assert_eq!(updated.avg_hr, Some(142));
        assert_eq!(updated.max_hr, Some(171));
        assert_eq!(get_sport_session_for_test(&db, s.id), updated);
    }

    fn get_sport_session_for_test(db: &Database, id: i64) -> SportSession {
        get_session(db, id).unwrap().unwrap()
    }
}
