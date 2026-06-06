-- Phase 6: explicit dose status + scheduled time for adherence correlation.
--
-- Until now a dose_event only ever meant "taken". To correlate missed/delayed
-- doses with mood we record an explicit status, the originally-scheduled time
-- (so "late by N" is real, not inferred) and which schedule the event belongs
-- to. All additive: `status` defaults to 'taken' so every existing row keeps
-- its meaning, the other two are nullable.
ALTER TABLE dose_events ADD COLUMN status TEXT NOT NULL DEFAULT 'taken';
ALTER TABLE dose_events ADD COLUMN scheduled_at_ms INTEGER;
ALTER TABLE dose_events ADD COLUMN schedule_id INTEGER;

-- Cross-medication time-window scan for the correlation timeline.
CREATE INDEX idx_dose_events_time ON dose_events(taken_at_ms DESC);
