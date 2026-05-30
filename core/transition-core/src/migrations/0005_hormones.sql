-- Phase 4: hormone lab measurements.

CREATE TABLE hormone_measurements (
    id        INTEGER PRIMARY KEY AUTOINCREMENT,
    at_ms     INTEGER NOT NULL,
    -- Stable identifier (estradiol, testosterone, lh, fsh, progesterone, …)
    hormone   TEXT    NOT NULL,
    value     REAL    NOT NULL,
    -- Original unit chosen by the user at entry time
    -- (pg/mL, pmol/L, ng/dL, nmol/L, mIU/mL, …).
    unit      TEXT    NOT NULL,
    lab_name  TEXT,
    notes     TEXT
) STRICT;

CREATE INDEX idx_hormone_h_at ON hormone_measurements(hormone, at_ms DESC);
