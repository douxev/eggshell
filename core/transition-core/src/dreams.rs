//! Dream journal: entries filed by the night they belong to, grouped by tag,
//! with encrypted voice notes attached.
//!
//! **Why this is not a note.** A note has one timestamp — when it was written.
//! A dream has two, and they are never the same: you write on Tuesday morning
//! about Monday night. Filing by writing time would put a 03:00
//! wake-and-scribble and a 09:00 recall of the same night on different days,
//! and every correlation drawn against dose timing would inherit that smear.
//! `night_ms` is therefore the only field a timeline ever reads.
//!
//! Sleep sliders are not here at all: they ride `metric_definitions` on the
//! `dreams` domain, which is what lets the existing metric editor reorder,
//! rename and hide them, and what lets a user-defined slider work on day one.
//!
//! Audio bytes never reach this module. Rows point at ciphertext the native
//! side writes, exactly like photos, voice clips and note images.

use rusqlite::{params, Row};

use crate::db::Database;
use crate::TransitionError;

#[derive(Clone, Debug, PartialEq)]
pub struct Dream {
    pub id: i64,
    /// Local midnight of the night this dream belongs to.
    pub night_ms: i64,
    pub title: String,
    pub body: String,
    pub lucid: bool,
    pub created_ms: i64,
    pub updated_ms: i64,
}

#[derive(Clone, Debug)]
pub struct NewDream {
    pub night_ms: i64,
    pub title: String,
    pub body: String,
    pub lucid: bool,
    pub created_ms: i64,
    pub updated_ms: i64,
}

#[derive(Clone, Debug, PartialEq)]
pub struct DreamTag {
    pub id: i64,
    pub label: String,
    pub color: Option<i64>,
    pub created_ms: i64,
    /// How many dreams carry this tag. The point of a dream journal is
    /// recurrence, so the count is what makes a tag list worth reading.
    pub dream_count: i64,
}

#[derive(Clone, Debug, PartialEq)]
pub struct DreamAudio {
    pub id: i64,
    pub dream_id: i64,
    pub file_path: String,
    pub duration_ms: i64,
    /// On-device transcription, when one was asked for and produced.
    pub transcript: Option<String>,
    pub position: i64,
    pub created_ms: i64,
}

#[derive(Clone, Debug)]
pub struct NewDreamAudio {
    pub dream_id: i64,
    pub file_path: String,
    pub duration_ms: i64,
    pub transcript: Option<String>,
    pub created_ms: i64,
}

// ---------------------------------------------------------------------------
// Dreams
// ---------------------------------------------------------------------------

pub fn add(db: &Database, d: NewDream) -> Result<Dream, TransitionError> {
    db.conn()
        .execute(
            "INSERT INTO dreams (night_ms, title, body, lucid, created_ms, updated_ms)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
            params![d.night_ms, d.title, d.body, d.lucid as i64, d.created_ms, d.updated_ms],
        )
        .map_err(map_sql)?;
    let id = db.conn().last_insert_rowid();
    get(db, id)?.ok_or_else(|| TransitionError::Database("dream vanished after insert".into()))
}

pub fn get(db: &Database, id: i64) -> Result<Option<Dream>, TransitionError> {
    db.conn()
        .query_row(
            "SELECT id, night_ms, title, body, lucid, created_ms, updated_ms
             FROM dreams WHERE id = ?1",
            params![id],
            map_dream,
        )
        .map(Some)
        .or_else(|e| match e {
            rusqlite::Error::QueryReturnedNoRows => Ok(None),
            other => Err(map_sql(other)),
        })
}

/// Newest night first. `tag_id` narrows to one recurring theme.
pub fn list(
    db: &Database,
    tag_id: Option<i64>,
    limit: i64,
    offset: i64,
) -> Result<Vec<Dream>, TransitionError> {
    let conn = db.conn();
    // Two statements rather than one with a conditional join: SQLite cannot
    // bind a JOIN in or out, and a `(?1 IS NULL OR ...)` over a link table
    // silently multiplies rows for a dream carrying several tags.
    let mut stmt = match tag_id {
        Some(_) => conn
            .prepare(
                "SELECT d.id, d.night_ms, d.title, d.body, d.lucid, d.created_ms, d.updated_ms
                 FROM dreams d
                 JOIN dream_tag_links l ON l.dream_id = d.id
                 WHERE l.tag_id = ?1
                 ORDER BY d.night_ms DESC, d.id DESC
                 LIMIT ?2 OFFSET ?3",
            )
            .map_err(map_sql)?,
        None => conn
            .prepare(
                "SELECT id, night_ms, title, body, lucid, created_ms, updated_ms
                 FROM dreams
                 ORDER BY night_ms DESC, id DESC
                 LIMIT ?1 OFFSET ?2",
            )
            .map_err(map_sql)?,
    };
    let rows = match tag_id {
        Some(t) => stmt.query_map(params![t, limit, offset], map_dream),
        None => stmt.query_map(params![limit, offset], map_dream),
    }
    .map_err(map_sql)?;
    rows.collect::<Result<Vec<_>, _>>().map_err(map_sql)
}

/// Every dream whose night falls in `[from_ms, to_ms]`, oldest first.
///
/// This is what the correlation timeline reads. Ordered ascending because a
/// timeline is drawn left to right, and sorting it again in the UI is one more
/// place for the two to disagree.
pub fn list_between(db: &Database, from_ms: i64, to_ms: i64) -> Result<Vec<Dream>, TransitionError> {
    let conn = db.conn();
    let mut stmt = conn
        .prepare(
            "SELECT id, night_ms, title, body, lucid, created_ms, updated_ms
             FROM dreams WHERE night_ms >= ?1 AND night_ms <= ?2
             ORDER BY night_ms ASC, id ASC",
        )
        .map_err(map_sql)?;
    let rows = stmt.query_map(params![from_ms, to_ms], map_dream).map_err(map_sql)?;
    rows.collect::<Result<Vec<_>, _>>().map_err(map_sql)
}

pub fn update(
    db: &Database,
    id: i64,
    night_ms: i64,
    title: String,
    body: String,
    lucid: bool,
    updated_ms: i64,
) -> Result<Dream, TransitionError> {
    let n = db
        .conn()
        .execute(
            "UPDATE dreams SET night_ms = ?2, title = ?3, body = ?4, lucid = ?5, updated_ms = ?6
             WHERE id = ?1",
            params![id, night_ms, title, body, lucid as i64, updated_ms],
        )
        .map_err(map_sql)?;
    if n == 0 {
        // An in-place update is what keeps the id stable, and a stable id is
        // what tags and audio hang off. Silently succeeding on a missing row
        // would let a caller believe an edit landed when its attachments have
        // already been cascaded away.
        return Err(TransitionError::Database(format!("no dream with id {id}")));
    }
    get(db, id)?.ok_or_else(|| TransitionError::Database("dream vanished after update".into()))
}

pub fn delete(db: &Database, id: i64) -> Result<(), TransitionError> {
    db.conn()
        .execute("DELETE FROM dreams WHERE id = ?1", params![id])
        .map_err(map_sql)?;
    Ok(())
}

// ---------------------------------------------------------------------------
// Tags
// ---------------------------------------------------------------------------

/// Create a tag, or return the existing one with that label.
///
/// Get-or-create rather than insert: the label is uniquely indexed NOCASE so
/// two spellings of one recurring theme cannot split the grouping the tag
/// exists to make, and a caller typing a tag they already have should land on
/// it rather than get an error.
pub fn add_tag(
    db: &Database,
    label: String,
    color: Option<i64>,
    created_ms: i64,
) -> Result<DreamTag, TransitionError> {
    let trimmed = label.trim().to_string();
    if trimmed.is_empty() {
        return Err(TransitionError::Database("a tag needs a label".into()));
    }
    if let Some(existing) = tag_by_label(db, &trimmed)? {
        return Ok(existing);
    }
    db.conn()
        .execute(
            "INSERT INTO dream_tags (label, color, created_ms) VALUES (?1, ?2, ?3)",
            params![trimmed, color, created_ms],
        )
        .map_err(map_sql)?;
    let id = db.conn().last_insert_rowid();
    tag_by_id(db, id)?.ok_or_else(|| TransitionError::Database("tag vanished after insert".into()))
}

/// Most-used first: a dream journal is kept to spot recurrence, so the tags
/// that recur are the ones worth putting at the top of a filter row.
pub fn list_tags(db: &Database) -> Result<Vec<DreamTag>, TransitionError> {
    let conn = db.conn();
    let mut stmt = conn
        .prepare(
            "SELECT t.id, t.label, t.color, t.created_ms,
                    (SELECT COUNT(*) FROM dream_tag_links l WHERE l.tag_id = t.id)
             FROM dream_tags t
             ORDER BY 5 DESC, t.label COLLATE NOCASE ASC",
        )
        .map_err(map_sql)?;
    let rows = stmt.query_map([], map_tag).map_err(map_sql)?;
    rows.collect::<Result<Vec<_>, _>>().map_err(map_sql)
}

pub fn rename_tag(db: &Database, id: i64, label: String) -> Result<(), TransitionError> {
    let trimmed = label.trim().to_string();
    if trimmed.is_empty() {
        return Err(TransitionError::Database("a tag needs a label".into()));
    }
    let n = db
        .conn()
        .execute(
            "UPDATE dream_tags SET label = ?2 WHERE id = ?1",
            params![id, trimmed],
        )
        .map_err(map_sql)?;
    if n == 0 {
        return Err(TransitionError::Database(format!("no tag with id {id}")));
    }
    Ok(())
}

/// Delete a tag. The links cascade, so the dreams themselves are untouched —
/// only the grouping goes.
pub fn delete_tag(db: &Database, id: i64) -> Result<(), TransitionError> {
    db.conn()
        .execute("DELETE FROM dream_tags WHERE id = ?1", params![id])
        .map_err(map_sql)?;
    Ok(())
}

/// Idempotent: tagging a dream twice is a no-op, not an error. The UI toggles.
pub fn tag_dream(db: &Database, dream_id: i64, tag_id: i64) -> Result<(), TransitionError> {
    db.conn()
        .execute(
            "INSERT OR IGNORE INTO dream_tag_links (dream_id, tag_id) VALUES (?1, ?2)",
            params![dream_id, tag_id],
        )
        .map_err(map_sql)?;
    Ok(())
}

pub fn untag_dream(db: &Database, dream_id: i64, tag_id: i64) -> Result<(), TransitionError> {
    db.conn()
        .execute(
            "DELETE FROM dream_tag_links WHERE dream_id = ?1 AND tag_id = ?2",
            params![dream_id, tag_id],
        )
        .map_err(map_sql)?;
    Ok(())
}

pub fn tags_for_dream(db: &Database, dream_id: i64) -> Result<Vec<DreamTag>, TransitionError> {
    let conn = db.conn();
    let mut stmt = conn
        .prepare(
            "SELECT t.id, t.label, t.color, t.created_ms,
                    (SELECT COUNT(*) FROM dream_tag_links l2 WHERE l2.tag_id = t.id)
             FROM dream_tags t
             JOIN dream_tag_links l ON l.tag_id = t.id
             WHERE l.dream_id = ?1
             ORDER BY t.label COLLATE NOCASE ASC",
        )
        .map_err(map_sql)?;
    let rows = stmt.query_map(params![dream_id], map_tag).map_err(map_sql)?;
    rows.collect::<Result<Vec<_>, _>>().map_err(map_sql)
}

// ---------------------------------------------------------------------------
// Voice notes
// ---------------------------------------------------------------------------

pub fn add_audio(db: &Database, a: NewDreamAudio) -> Result<DreamAudio, TransitionError> {
    let next: i64 = db
        .conn()
        .query_row(
            "SELECT COALESCE(MAX(position), -1) + 1 FROM dream_audio WHERE dream_id = ?1",
            params![a.dream_id],
            |r| r.get(0),
        )
        .map_err(map_sql)?;
    db.conn()
        .execute(
            "INSERT INTO dream_audio (dream_id, file_path, duration_ms, transcript, position, created_ms)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
            params![a.dream_id, a.file_path, a.duration_ms, a.transcript, next, a.created_ms],
        )
        .map_err(map_sql)?;
    let id = db.conn().last_insert_rowid();
    audio_by_id(db, id)?.ok_or_else(|| TransitionError::Database("audio vanished after insert".into()))
}

pub fn audio_for_dream(db: &Database, dream_id: i64) -> Result<Vec<DreamAudio>, TransitionError> {
    let conn = db.conn();
    let mut stmt = conn
        .prepare(
            "SELECT id, dream_id, file_path, duration_ms, transcript, position, created_ms
             FROM dream_audio WHERE dream_id = ?1 ORDER BY position ASC",
        )
        .map_err(map_sql)?;
    let rows = stmt.query_map(params![dream_id], map_audio).map_err(map_sql)?;
    rows.collect::<Result<Vec<_>, _>>().map_err(map_sql)
}

/// Attach a transcript after the fact — transcription is asked for from the
/// player, long after the clip was recorded.
pub fn set_transcript(
    db: &Database,
    audio_id: i64,
    transcript: Option<String>,
) -> Result<(), TransitionError> {
    let n = db
        .conn()
        .execute(
            "UPDATE dream_audio SET transcript = ?2 WHERE id = ?1",
            params![audio_id, transcript],
        )
        .map_err(map_sql)?;
    if n == 0 {
        return Err(TransitionError::Database(format!("no dream audio with id {audio_id}")));
    }
    Ok(())
}

pub fn delete_audio(db: &Database, audio_id: i64) -> Result<(), TransitionError> {
    db.conn()
        .execute("DELETE FROM dream_audio WHERE id = ?1", params![audio_id])
        .map_err(map_sql)?;
    Ok(())
}

/// Every audio path the vault still references.
///
/// The native side sweeps its own directory against this: a row deleted by
/// cascade takes no `.bin` with it, and an orphaned ciphertext file would
/// otherwise outlive the dream it belonged to indefinitely.
pub fn all_audio_paths(db: &Database) -> Result<Vec<String>, TransitionError> {
    let conn = db.conn();
    let mut stmt = conn.prepare("SELECT file_path FROM dream_audio").map_err(map_sql)?;
    let rows = stmt.query_map([], |r| r.get::<_, String>(0)).map_err(map_sql)?;
    rows.collect::<Result<Vec<_>, _>>().map_err(map_sql)
}

// ---------------------------------------------------------------------------

fn tag_by_id(db: &Database, id: i64) -> Result<Option<DreamTag>, TransitionError> {
    db.conn()
        .query_row(
            "SELECT t.id, t.label, t.color, t.created_ms,
                    (SELECT COUNT(*) FROM dream_tag_links l WHERE l.tag_id = t.id)
             FROM dream_tags t WHERE t.id = ?1",
            params![id],
            map_tag,
        )
        .map(Some)
        .or_else(|e| match e {
            rusqlite::Error::QueryReturnedNoRows => Ok(None),
            other => Err(map_sql(other)),
        })
}

fn tag_by_label(db: &Database, label: &str) -> Result<Option<DreamTag>, TransitionError> {
    db.conn()
        .query_row(
            "SELECT t.id, t.label, t.color, t.created_ms,
                    (SELECT COUNT(*) FROM dream_tag_links l WHERE l.tag_id = t.id)
             FROM dream_tags t WHERE t.label = ?1 COLLATE NOCASE",
            params![label],
            map_tag,
        )
        .map(Some)
        .or_else(|e| match e {
            rusqlite::Error::QueryReturnedNoRows => Ok(None),
            other => Err(map_sql(other)),
        })
}

fn audio_by_id(db: &Database, id: i64) -> Result<Option<DreamAudio>, TransitionError> {
    db.conn()
        .query_row(
            "SELECT id, dream_id, file_path, duration_ms, transcript, position, created_ms
             FROM dream_audio WHERE id = ?1",
            params![id],
            map_audio,
        )
        .map(Some)
        .or_else(|e| match e {
            rusqlite::Error::QueryReturnedNoRows => Ok(None),
            other => Err(map_sql(other)),
        })
}

fn map_dream(r: &Row<'_>) -> rusqlite::Result<Dream> {
    Ok(Dream {
        id: r.get(0)?,
        night_ms: r.get(1)?,
        title: r.get(2)?,
        body: r.get(3)?,
        lucid: r.get::<_, i64>(4)? != 0,
        created_ms: r.get(5)?,
        updated_ms: r.get(6)?,
    })
}

fn map_tag(r: &Row<'_>) -> rusqlite::Result<DreamTag> {
    Ok(DreamTag {
        id: r.get(0)?,
        label: r.get(1)?,
        color: r.get(2)?,
        created_ms: r.get(3)?,
        dream_count: r.get(4)?,
    })
}

fn map_audio(r: &Row<'_>) -> rusqlite::Result<DreamAudio> {
    Ok(DreamAudio {
        id: r.get(0)?,
        dream_id: r.get(1)?,
        file_path: r.get(2)?,
        duration_ms: r.get(3)?,
        transcript: r.get(4)?,
        position: r.get(5)?,
        created_ms: r.get(6)?,
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

    fn dream(night: i64, title: &str) -> NewDream {
        NewDream {
            night_ms: night,
            title: title.into(),
            body: String::new(),
            lucid: false,
            created_ms: night + 30_000,
            updated_ms: night + 30_000,
        }
    }

    #[test]
    fn dreams_list_by_night_not_by_writing_time() {
        let (db, _f) = fresh_db();
        // Written in one order, dreamt in another: an entry typed today about
        // last week must not jump to the top.
        let old_night = add(&db, NewDream { created_ms: 9_000, updated_ms: 9_000, ..dream(1_000, "ancien") }).unwrap();
        let new_night = add(&db, NewDream { created_ms: 2_000, updated_ms: 2_000, ..dream(8_000, "récent") }).unwrap();

        let all = list(&db, None, 50, 0).unwrap();
        assert_eq!(
            all.iter().map(|d| d.id).collect::<Vec<_>>(),
            vec![new_night.id, old_night.id],
            "ordering must follow night_ms, never created_ms",
        );
    }

    #[test]
    fn a_tag_is_case_insensitively_unique_so_recurrence_cannot_split() {
        let (db, _f) = fresh_db();
        let a = add_tag(&db, "Chute".into(), None, 1).unwrap();
        let b = add_tag(&db, "chute".into(), None, 2).unwrap();
        assert_eq!(a.id, b.id, "two spellings would split the grouping");
        assert_eq!(list_tags(&db).unwrap().len(), 1);
    }

    #[test]
    fn tag_counts_drive_the_ordering_and_survive_untagging() {
        let (db, _f) = fresh_db();
        let d1 = add(&db, dream(1_000, "a")).unwrap();
        let d2 = add(&db, dream(2_000, "b")).unwrap();
        let common = add_tag(&db, "eau".into(), None, 1).unwrap();
        let rare = add_tag(&db, "vol".into(), None, 1).unwrap();
        tag_dream(&db, d1.id, common.id).unwrap();
        tag_dream(&db, d2.id, common.id).unwrap();
        tag_dream(&db, d2.id, rare.id).unwrap();

        let tags = list_tags(&db).unwrap();
        assert_eq!(tags[0].label, "eau");
        assert_eq!(tags[0].dream_count, 2);
        assert_eq!(tags[1].dream_count, 1);

        untag_dream(&db, d2.id, common.id).unwrap();
        assert_eq!(list_tags(&db).unwrap()[0].dream_count, 1);
    }

    #[test]
    fn tagging_twice_is_idempotent() {
        let (db, _f) = fresh_db();
        let d = add(&db, dream(1_000, "a")).unwrap();
        let t = add_tag(&db, "eau".into(), None, 1).unwrap();
        tag_dream(&db, d.id, t.id).unwrap();
        tag_dream(&db, d.id, t.id).unwrap();
        assert_eq!(tags_for_dream(&db, d.id).unwrap().len(), 1);
    }

    #[test]
    fn filtering_by_tag_returns_each_dream_once() {
        let (db, _f) = fresh_db();
        let d = add(&db, dream(1_000, "a")).unwrap();
        let t1 = add_tag(&db, "eau".into(), None, 1).unwrap();
        let t2 = add_tag(&db, "vol".into(), None, 1).unwrap();
        tag_dream(&db, d.id, t1.id).unwrap();
        tag_dream(&db, d.id, t2.id).unwrap();
        assert_eq!(list(&db, Some(t1.id), 50, 0).unwrap().len(), 1);
    }

    #[test]
    fn deleting_a_tag_keeps_the_dreams() {
        let (db, _f) = fresh_db();
        let d = add(&db, dream(1_000, "a")).unwrap();
        let t = add_tag(&db, "eau".into(), None, 1).unwrap();
        tag_dream(&db, d.id, t.id).unwrap();

        delete_tag(&db, t.id).unwrap();
        assert!(get(&db, d.id).unwrap().is_some(), "only the grouping should go");
        assert!(tags_for_dream(&db, d.id).unwrap().is_empty());
    }

    #[test]
    fn deleting_a_dream_cascades_its_tags_and_audio() {
        let (db, _f) = fresh_db();
        let d = add(&db, dream(1_000, "a")).unwrap();
        let t = add_tag(&db, "eau".into(), None, 1).unwrap();
        tag_dream(&db, d.id, t.id).unwrap();
        add_audio(&db, NewDreamAudio {
            dream_id: d.id, file_path: "/x/1.bin".into(),
            duration_ms: 5_000, transcript: None, created_ms: 1,
        }).unwrap();

        delete(&db, d.id).unwrap();
        assert!(audio_for_dream(&db, d.id).unwrap().is_empty());
        assert!(
            all_audio_paths(&db).unwrap().is_empty(),
            "orphan rows would keep their .bin files alive forever",
        );
        // The tag itself survives — it may group other dreams.
        assert_eq!(list_tags(&db).unwrap().len(), 1);
    }

    #[test]
    fn list_between_reads_the_night_and_is_ascending() {
        let (db, _f) = fresh_db();
        add(&db, dream(1_000, "hors")).unwrap();
        let a = add(&db, dream(5_000, "a")).unwrap();
        let b = add(&db, dream(7_000, "b")).unwrap();
        add(&db, dream(9_000, "hors")).unwrap();

        let window = list_between(&db, 4_000, 8_000).unwrap();
        assert_eq!(window.iter().map(|d| d.id).collect::<Vec<_>>(), vec![a.id, b.id]);
    }

    #[test]
    fn transcripts_attach_after_the_fact() {
        let (db, _f) = fresh_db();
        let d = add(&db, dream(1_000, "a")).unwrap();
        let clip = add_audio(&db, NewDreamAudio {
            dream_id: d.id, file_path: "/x/1.bin".into(),
            duration_ms: 5_000, transcript: None, created_ms: 1,
        }).unwrap();
        assert!(clip.transcript.is_none());

        set_transcript(&db, clip.id, Some("je tombais".into())).unwrap();
        assert_eq!(
            audio_for_dream(&db, d.id).unwrap()[0].transcript.as_deref(),
            Some("je tombais"),
        );
    }

    #[test]
    fn updating_a_missing_dream_is_an_error_not_a_silent_no_op() {
        let (db, _f) = fresh_db();
        assert!(update(&db, 999, 1, "t".into(), "b".into(), false, 1).is_err());
    }

    #[test]
    fn the_sleep_sliders_are_seeded_on_their_own_domain() {
        let (db, _f) = fresh_db();
        let keys: Vec<String> = {
            let conn = db.conn();
            let mut stmt = conn
                .prepare("SELECT metric_key FROM metric_definitions WHERE domain = 'dreams' ORDER BY sort_order")
                .unwrap();
            let rows = stmt.query_map([], |r| r.get::<_, String>(0)).unwrap();
            rows.map(|r| r.unwrap()).collect()
        };
        assert_eq!(keys, vec!["sleep_quality", "recall", "vividness", "emotional_tone"]);
    }
}
