-- Phase 3: feelings journal.
--
-- A single table for both the "quick check-in" (just the gauges) and the
-- free-form entries (text + tagged side effects). Any combination of fields
-- can be present — the UI decides which screen to show.

CREATE TABLE journal_entries (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    at_ms         INTEGER NOT NULL,
    -- 0-10 gauges. NULL means "not specified" — the journal accepts partial
    -- check-ins (only mood, for example) without making the user fill in all.
    mood          INTEGER CHECK (mood       IS NULL OR (mood       BETWEEN 0 AND 10)),
    dysphoria     INTEGER CHECK (dysphoria  IS NULL OR (dysphoria  BETWEEN 0 AND 10)),
    euphoria      INTEGER CHECK (euphoria   IS NULL OR (euphoria   BETWEEN 0 AND 10)),
    libido        INTEGER CHECK (libido     IS NULL OR (libido     BETWEEN 0 AND 10)),
    energy        INTEGER CHECK (energy     IS NULL OR (energy     BETWEEN 0 AND 10)),
    free_text     TEXT,
    -- Side effects stored as a comma-separated list of identifiers (the UI
    -- maps each to a localised label, like the medication kind/route lists).
    side_effects  TEXT
) STRICT;

CREATE INDEX idx_journal_at ON journal_entries(at_ms DESC);
