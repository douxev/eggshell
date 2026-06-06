-- Phase 4: bleeding / cycle tracking.
--
-- Neutral framing on purpose — this tracks bleeding and symptoms, not an
-- assumed menstrual cycle (testosterone suppresses menses; estrogen regimens
-- cause spotting / withdrawal bleeding, not true menses). Slider values
-- (flow/pain/cramps + any user-defined slider) live in `metric_values` with
-- entry_domain = 'bleeding'; this table holds the per-entry scalar fields.
CREATE TABLE bleeding_entries (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    at_ms        INTEGER NOT NULL,
    -- 1 = spotting / breakthrough; 0 = a full bleed; NULL = unspecified.
    is_spotting  INTEGER CHECK (is_spotting IS NULL OR is_spotting IN (0, 1)),
    free_text    TEXT
) STRICT;

CREATE INDEX idx_bleeding_at ON bleeding_entries(at_ms DESC);
