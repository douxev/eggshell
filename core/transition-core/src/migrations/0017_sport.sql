-- Phase 8: sport / physical activity.
--
-- Purely additive, like every migration here: three new tables, nothing
-- dropped, nothing altered. A vault migrated by this build is refused by an
-- older one (db.rs says so out loud), which is the designed behaviour — but it
-- means the step is one-way, so it must not need to be taken back.
--
-- Slider values (effort, sensation, and anything the user defines) do NOT live
-- here. They go in `metric_values` with entry_domain = 'sport', the idiom the
-- bleeding module already uses — so a custom metric costs no schema at all.

-- The activity catalogue. Editable, because "what counts as a session" is not
-- something an app gets to decide for someone: walking to the shops and a
-- powerlifting set are both sport to the person doing them.
CREATE TABLE sport_activities (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    name        TEXT    NOT NULL,
    -- 'cardio' | 'strength' | 'mobility' | 'other'. No CHECK, deliberately:
    -- dose_schedules documents the same choice — constraining a vocabulary
    -- means a migration every time it grows.
    kind        TEXT    NOT NULL,
    color       INTEGER,
    archived    INTEGER NOT NULL DEFAULT 0 CHECK (archived IN (0, 1)),
    created_ms  INTEGER NOT NULL
) STRICT;

CREATE TABLE sport_sessions (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    -- SET NULL, not CASCADE. Deleting an activity type must not delete the
    -- history of having done it — that history is months of someone's life and
    -- is not reconstructible. An orphaned session keeps its duration, its date
    -- and its notes, and the UI shows it under a neutral label.
    activity_id INTEGER REFERENCES sport_activities(id) ON DELETE SET NULL,
    started_ms  INTEGER NOT NULL,
    duration_s  INTEGER NOT NULL CHECK (duration_s >= 0),
    free_text   TEXT,
    -- Metres. Nullable because most sessions have no distance to speak of —
    -- a strength set does not — and because a watch import is where it comes
    -- from. A run without its distance is half a record, which is why this is
    -- a column rather than a sentence smuggled into free_text.
    distance_m  REAL    CHECK (distance_m IS NULL OR distance_m >= 0),
    -- Beats per minute, from a Bluetooth heart-rate sensor watched live during
    -- the session. Nullable, and expected to stay null for most rows: a session
    -- typed in afterwards has no heart rate, and neither does one imported from
    -- a file that did not carry it.
    avg_hr      INTEGER CHECK (avg_hr IS NULL OR (avg_hr BETWEEN 20 AND 300)),
    max_hr      INTEGER CHECK (max_hr IS NULL OR (max_hr BETWEEN 20 AND 300)),
    -- 'manual' | 'pedometer' | 'watch'. Recorded so a later import can tell
    -- what it may overwrite: a hand-entered session is the user's word and must
    -- never be silently replaced by a sensor's.
    source      TEXT    NOT NULL DEFAULT 'manual'
) STRICT;

CREATE INDEX idx_sport_sessions_started ON sport_sessions(started_ms DESC);
CREATE INDEX idx_sport_sessions_activity ON sport_sessions(activity_id, started_ms DESC);

-- Daily step totals, one row per local calendar day.
--
-- Keyed by the local day rather than by a timestamp because that is the unit
-- the user thinks in ("how much did I walk on Tuesday") and the only one a
-- calendar can render. A timestamp would have to be re-bucketed on every read,
-- and would land in the wrong bucket after a timezone change.
CREATE TABLE sport_step_days (
    day_key     TEXT    PRIMARY KEY,   -- 'YYYY-MM-DD', local
    steps       INTEGER NOT NULL CHECK (steps >= 0),
    updated_ms  INTEGER NOT NULL
) STRICT;
