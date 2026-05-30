//! Voice clip metadata, stored in the encrypted vault.
//!
//! Same shape as `photos`: only metadata + path lives in the DB. The audio
//! bytes are AES-GCM encrypted via `Vault::encrypt_blob` on a HKDF-derived
//! file sub-key and written to the app sandbox.

use rusqlite::params;

use crate::TransitionError;
use crate::db::Database;

#[derive(Clone, Debug, PartialEq)]
pub struct VoiceClip {
    pub id: String,
    pub at_ms: i64,
    pub duration_ms: i64,
    pub file_path: String,
    pub pitch_hz: Option<i32>,
}

#[derive(Clone, Debug, PartialEq)]
pub struct NewVoiceClip {
    pub id: String,
    pub at_ms: i64,
    pub duration_ms: i64,
    pub file_path: String,
    pub pitch_hz: Option<i32>,
}

pub fn add(db: &Database, clip: NewVoiceClip) -> Result<VoiceClip, TransitionError> {
    db.conn()
        .execute(
            "INSERT INTO voice_clips (id, at_ms, duration_ms, file_path, pitch_hz)
             VALUES (?1, ?2, ?3, ?4, ?5)",
            params![clip.id, clip.at_ms, clip.duration_ms, clip.file_path, clip.pitch_hz],
        )
        .map_err(map_sql)?;
    Ok(VoiceClip {
        id: clip.id,
        at_ms: clip.at_ms,
        duration_ms: clip.duration_ms,
        file_path: clip.file_path,
        pitch_hz: clip.pitch_hz,
    })
}

pub fn list(db: &Database, offset: i64, limit: i64) -> Result<Vec<VoiceClip>, TransitionError> {
    let mut stmt = db
        .conn()
        .prepare(
            "SELECT id, at_ms, duration_ms, file_path, pitch_hz
             FROM voice_clips
             ORDER BY at_ms DESC
             LIMIT ?1 OFFSET ?2",
        )
        .map_err(map_sql)?;
    let rows = stmt
        .query_map(params![limit, offset], |r| {
            Ok(VoiceClip {
                id: r.get(0)?,
                at_ms: r.get(1)?,
                duration_ms: r.get(2)?,
                file_path: r.get(3)?,
                pitch_hz: r.get(4)?,
            })
        })
        .map_err(map_sql)?;
    let mut out = Vec::new();
    for r in rows {
        out.push(r.map_err(map_sql)?);
    }
    Ok(out)
}

pub fn delete(db: &Database, id: String) -> Result<(), TransitionError> {
    db.conn()
        .execute("DELETE FROM voice_clips WHERE id = ?1", params![id])
        .map_err(map_sql)?;
    Ok(())
}

fn map_sql(err: rusqlite::Error) -> TransitionError {
    crate::sanitize_db_err(err)
}
