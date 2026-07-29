-- Notes module.
--
-- A flat, manually-ordered list rather than a folder tree: nothing else in
-- Eggshell nests, and the interaction being copied is the decoy notes app's
-- drag-to-reorder grid. `sort_order` is the same persisted-manual-order idiom
-- the custom metrics already use, so "move" is a swap of two integers.
--
-- Body is free text. Images are NOT inlined into it: they get rows here and
-- their ciphertext lives in its own on-disk directory, so the note keeps
-- working as plain text and a missing image degrades to a gap rather than to
-- corrupt markup.
CREATE TABLE notes (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    title       TEXT NOT NULL,
    body        TEXT NOT NULL,
    sort_order  INTEGER NOT NULL,
    created_ms  INTEGER NOT NULL,
    updated_ms  INTEGER NOT NULL
) STRICT;

CREATE INDEX idx_notes_order ON notes(sort_order ASC);

-- ON DELETE CASCADE is load-bearing: foreign keys are enabled on every
-- connection, so deleting a note takes its image rows with it. The .bin files
-- on disk are the native side's job — see NotesRepository.cleanupOrphans.
CREATE TABLE note_images (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    note_id    INTEGER NOT NULL REFERENCES notes(id) ON DELETE CASCADE,
    file_path  TEXT NOT NULL,
    position   INTEGER NOT NULL
) STRICT;

CREATE INDEX idx_note_images_note ON note_images(note_id, position ASC);
