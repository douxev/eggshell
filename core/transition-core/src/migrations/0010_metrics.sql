-- Phase 3/4/5: user-customizable metric sliders, shared by the feelings
-- journal and the bleeding/cycle tracker.
--
-- `metric_definitions` is the catalog of sliders. Built-in journal gauges
-- (mood/dysphoria/...) are seeded here so the editor can reorder/disable them;
-- their per-entry VALUES keep living in the journal_entries columns
-- (`column_name` points at the column). Built-in bleeding gauges and ALL
-- user-defined sliders store their per-entry value in `metric_values` instead.

CREATE TABLE metric_definitions (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    -- "journal" | "bleeding".
    domain        TEXT    NOT NULL,
    -- Stable identifier within a domain. Built-ins use a fixed key
    -- (mood/flow/...); custom ones get an app-assigned "custom_<n>" key.
    metric_key    TEXT    NOT NULL,
    -- User-authored label for custom metrics. Built-ins keep an empty label and
    -- the UI resolves a localized string by metric_key.
    label         TEXT    NOT NULL DEFAULT '',
    emoji_left    TEXT,
    emoji_right   TEXT,
    min_value     INTEGER NOT NULL DEFAULT 0,
    max_value     INTEGER NOT NULL DEFAULT 10,
    sort_order    INTEGER NOT NULL DEFAULT 0,
    -- 1 = shipped with the app: can be reordered/disabled but never deleted.
    builtin       INTEGER NOT NULL DEFAULT 0 CHECK (builtin IN (0, 1)),
    -- For built-in journal gauges only: the journal_entries column whose value
    -- backs this slider. NULL = value stored in metric_values.
    column_name   TEXT,
    enabled       INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)),
    -- Soft delete for custom metrics: archived rows are hidden from editors and
    -- new entries but kept so historical values still resolve a label.
    archived      INTEGER NOT NULL DEFAULT 0 CHECK (archived IN (0, 1)),
    created_at_ms INTEGER NOT NULL DEFAULT 0
) STRICT;

CREATE UNIQUE INDEX idx_metric_def_key ON metric_definitions(domain, metric_key);
CREATE INDEX idx_metric_def_domain ON metric_definitions(domain, archived, sort_order);

CREATE TABLE metric_values (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    -- "journal" | "bleeding" — which entry table entry_id points into.
    entry_domain TEXT    NOT NULL,
    entry_id     INTEGER NOT NULL,
    metric_id    INTEGER NOT NULL REFERENCES metric_definitions(id) ON DELETE CASCADE,
    value        INTEGER NOT NULL
) STRICT;

CREATE UNIQUE INDEX idx_metric_values_unique
    ON metric_values(entry_domain, entry_id, metric_id);
CREATE INDEX idx_metric_values_entry ON metric_values(entry_domain, entry_id);

-- Seed built-in journal gauges (values stay in the journal_entries columns).
INSERT INTO metric_definitions (domain, metric_key, sort_order, builtin, column_name) VALUES
    ('journal', 'mood',      0, 1, 'mood'),
    ('journal', 'dysphoria', 1, 1, 'dysphoria'),
    ('journal', 'euphoria',  2, 1, 'euphoria'),
    ('journal', 'libido',    3, 1, 'libido'),
    ('journal', 'energy',    4, 1, 'energy');

-- Seed built-in bleeding gauges (values live in metric_values).
INSERT INTO metric_definitions (domain, metric_key, sort_order, builtin) VALUES
    ('bleeding', 'flow',   0, 1),
    ('bleeding', 'pain',   1, 1),
    ('bleeding', 'cramps', 2, 1);
