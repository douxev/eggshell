-- Phase 6: treatment-change audit log.
--
-- A `medications` row is otherwise an immutable snapshot. When the user edits a
-- dose/route/unit we append a timestamped change row so the correlation
-- timeline can show "dose 2mg -> 4mg on date X" as a vertical marker. Cascades
-- with its medication so deleting a med leaves no orphan history.
CREATE TABLE treatment_changes (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    medication_id INTEGER NOT NULL REFERENCES medications(id) ON DELETE CASCADE,
    at_ms         INTEGER NOT NULL,
    -- What changed: "dose" | "route" | "unit" | "note" (free-form).
    field         TEXT    NOT NULL,
    old_value     TEXT,
    new_value     TEXT,
    note          TEXT
) STRICT;

CREATE INDEX idx_treatment_changes_time ON treatment_changes(at_ms DESC);
CREATE INDEX idx_treatment_changes_med ON treatment_changes(medication_id, at_ms DESC);
