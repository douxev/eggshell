-- Notes module.
--
-- Folders nest (Obsidian / Outline style), notes do not: a note lives in
-- exactly one folder, or at the root when folder_id is NULL. Within a folder
-- the order is manual — the same persisted sort_order idiom the custom metrics
-- already use — so "move" is a rewrite of integers rather than a tree surgery.
--
-- Body is markdown. Images are NOT inlined as base64: they get rows here and
-- their ciphertext lives in its own on-disk directory, referenced from the body
-- by id. That is what Obsidian, Joplin, Logseq and Zim all do, and it means a
-- missing image degrades to a gap rather than to corrupt markup.
CREATE TABLE note_folders (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    name        TEXT NOT NULL,
    -- Self-reference: deleting a folder takes its subfolders with it.
    parent_id   INTEGER REFERENCES note_folders(id) ON DELETE CASCADE,
    sort_order  INTEGER NOT NULL,
    created_ms  INTEGER NOT NULL
) STRICT;

CREATE INDEX idx_note_folders_parent ON note_folders(parent_id, sort_order ASC);

CREATE TABLE notes (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    folder_id   INTEGER REFERENCES note_folders(id) ON DELETE CASCADE,
    title       TEXT NOT NULL,
    body        TEXT NOT NULL,
    sort_order  INTEGER NOT NULL,
    created_ms  INTEGER NOT NULL,
    updated_ms  INTEGER NOT NULL
) STRICT;

CREATE INDEX idx_notes_folder ON notes(folder_id, sort_order ASC);

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
