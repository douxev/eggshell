-- Phase 5: progress photos.
--
-- Only metadata lives in the DB. The actual image bytes are AES-GCM encrypted
-- and stored as opaque blobs on disk, with `file_path` pointing to them.

CREATE TABLE photo_records (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    at_ms         INTEGER NOT NULL,
    -- Free-form classification ("front", "side", "face", …) — the UI shapes it.
    category      TEXT,
    -- Absolute path to the encrypted blob in the app's private storage.
    file_path     TEXT    NOT NULL,
    notes         TEXT
) STRICT;

CREATE INDEX idx_photo_at ON photo_records(at_ms DESC);
