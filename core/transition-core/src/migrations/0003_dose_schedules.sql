-- Phase 2 (continued): reminder schedules.
--
-- A `dose_schedule` describes WHEN doses of a medication should be taken.
-- The native layer reads these to set Android exact alarms (or iOS local
-- notifications later) and bumps `next_due_at_ms` after each occurrence.
--
-- Two flavours are supported at the data layer:
-- - `interval`  : every N minutes starting from a known anchor
-- - `daily`     : at HH:MM local time, every day
--
-- Local-time semantics for the daily flavour live in the Android layer so
-- DST + timezone changes are handled by java.time. Rust just stores the spec.

CREATE TABLE dose_schedules (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    medication_id     INTEGER NOT NULL REFERENCES medications(id) ON DELETE CASCADE,
    -- "interval" | "daily" (extensible — we don't constrain with CHECK to
    -- avoid migrations every time we add a kind).
    kind              TEXT    NOT NULL,
    -- Set when kind = "interval".
    interval_minutes  INTEGER,
    -- Set when kind = "daily".
    daily_hour        INTEGER CHECK (daily_hour IS NULL OR (daily_hour BETWEEN 0 AND 23)),
    daily_minute      INTEGER CHECK (daily_minute IS NULL OR (daily_minute BETWEEN 0 AND 59)),
    -- When the next reminder should fire, in epoch milliseconds (UTC).
    -- The native scheduler bumps this after each occurrence.
    next_due_at_ms    INTEGER NOT NULL,
    active            INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0, 1)),
    created_at_ms     INTEGER NOT NULL
) STRICT;

-- Hot query: "give me all the active schedules due in the next horizon".
CREATE INDEX idx_dose_schedules_active_due
    ON dose_schedules(active, next_due_at_ms);
