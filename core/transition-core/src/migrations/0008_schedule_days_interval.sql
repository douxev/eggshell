-- Phase 2 (continued): a third schedule flavour for medications.
--
-- `days_interval` : every N days at HH:MM local time, starting from an anchor
--                   date (the first `next_due_at_ms` encodes the start day).
--
-- Reuses `daily_hour` / `daily_minute` for the time of day and adds
-- `interval_days` for the N-day cadence. Local-time / DST math stays in the
-- Android layer (java.time), same as the `daily` flavour — Rust only stores
-- the spec. Nullable column so existing rows (interval / daily) are unaffected.
ALTER TABLE dose_schedules ADD COLUMN interval_days INTEGER;
