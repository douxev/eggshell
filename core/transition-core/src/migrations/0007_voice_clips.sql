-- Phase 5.1: voice clip metadata moves from plain SharedPreferences into the
-- encrypted vault. The audio bytes themselves are AES-GCM encrypted on disk
-- (same pattern as photos) — only file_path + timestamps + pitch live here.

CREATE TABLE voice_clips (
    id           TEXT    PRIMARY KEY,
    at_ms        INTEGER NOT NULL,
    duration_ms  INTEGER NOT NULL,
    file_path    TEXT    NOT NULL,
    pitch_hz     INTEGER
) STRICT;

CREATE INDEX idx_voice_at ON voice_clips(at_ms DESC);
