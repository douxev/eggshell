-- Phase 2: medication tracking.
-- Adds the `medications` catalog and the `dose_events` log. Schedules and
-- reminders come in a follow-up migration.

CREATE TABLE medications (
    id                 INTEGER PRIMARY KEY AUTOINCREMENT,
    name               TEXT    NOT NULL,
    -- Free-form discriminator (estrogen, anti_androgen, progesterone, …). We
    -- intentionally don't constrain with CHECK because the realistic set grows
    -- over time and depends on the user's prescription.
    kind               TEXT    NOT NULL,
    -- Route of administration: oral, sublingual, topical, transdermal,
    -- injection_im, injection_sc, suppository, other.
    route              TEXT    NOT NULL,
    default_dose       REAL,
    default_dose_unit  TEXT,
    -- Packed ARGB int (0xAARRGGBB) used by the UI to colour-code the med.
    color              INTEGER,
    notes              TEXT,
    archived           INTEGER NOT NULL DEFAULT 0 CHECK (archived IN (0, 1)),
    created_at_ms      INTEGER NOT NULL
) STRICT;

CREATE INDEX idx_medications_active ON medications(archived, name);

CREATE TABLE dose_events (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    medication_id   INTEGER NOT NULL REFERENCES medications(id) ON DELETE CASCADE,
    taken_at_ms     INTEGER NOT NULL,
    dose            REAL,
    dose_unit       TEXT,
    -- Route can override the medication's default route for this specific dose
    -- (rare but happens: same product, different administration).
    route           TEXT,
    -- Free-form site identifier (e.g. "thigh_left", "abdomen_right_upper").
    -- The standard set lives in medication::injection::STANDARD_SITES.
    injection_site  TEXT,
    notes           TEXT
) STRICT;

CREATE INDEX idx_dose_events_med_time ON dose_events(medication_id, taken_at_ms DESC);
