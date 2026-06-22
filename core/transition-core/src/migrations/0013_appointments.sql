-- Phase 7: appointments / notes ("RDV").
--
-- A fully-encrypted record for medical appointments and the notes attached to
-- them. `at_ms` is when the appointment happens; `reminder_at_ms` is an
-- optional one-shot notification time the native side arms. The "à faire /
-- fait" checklist is kept as a single free-text column (one item per line) to
-- match how other domains store free-form text — no extra table.
CREATE TABLE appointments (
    id                 INTEGER PRIMARY KEY AUTOINCREMENT,
    at_ms              INTEGER NOT NULL,
    place              TEXT,
    professional_name  TEXT,
    professional_role  TEXT,
    notes              TEXT,
    todo               TEXT,
    reminder_at_ms     INTEGER
) STRICT;

CREATE INDEX idx_appointments_at ON appointments(at_ms DESC);
