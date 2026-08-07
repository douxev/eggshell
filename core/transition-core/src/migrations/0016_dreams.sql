-- Dream journal.
--
-- A dream is not a note, and the difference that forces its own table is the
-- date. A note has one timestamp — when it was written. A dream has two, and
-- they are never the same: you write on Tuesday morning about Monday night.
-- Filing dreams by writing time would put a 03:00 wake-and-scribble and a
-- 09:00 recall of the same night on different days, and every correlation
-- drawn against dose timing would inherit that smear.
--
-- So `night_ms` is the night the dream belongs to — local midnight of the
-- evening it started — and `created_ms` is when the entry was typed. Only the
-- first is ever used to place a dream on a timeline.
CREATE TABLE dreams (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    -- Local midnight of the night the dream belongs to. The native side
    -- computes it, because only it knows the device's zone.
    night_ms   INTEGER NOT NULL,
    title      TEXT    NOT NULL DEFAULT '',
    body       TEXT    NOT NULL DEFAULT '',
    -- Called out rather than left to a slider: lucidity is the one attribute
    -- people filter a dream journal by, and a yes/no filters exactly.
    lucid      INTEGER NOT NULL DEFAULT 0 CHECK (lucid IN (0, 1)),
    created_ms INTEGER NOT NULL,
    updated_ms INTEGER NOT NULL
) STRICT;

CREATE INDEX idx_dreams_night ON dreams(night_ms DESC);

-- Tags exist to answer "have I dreamt this before?", which is the question a
-- dream journal is kept to answer at all. They are their own rows rather than
-- a comma-joined string on the dream so that renaming one renames it
-- everywhere, and so a tag can be counted.
CREATE TABLE dream_tags (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    label      TEXT    NOT NULL,
    -- ARGB, chosen by the user. NULL takes the palette's default.
    color      INTEGER,
    created_ms INTEGER NOT NULL
) STRICT;

-- NOCASE so « Chute » and « chute » cannot both exist: two spellings of one
-- recurring theme would split the very grouping the tag is there to make.
CREATE UNIQUE INDEX idx_dream_tags_label ON dream_tags(label COLLATE NOCASE);

CREATE TABLE dream_tag_links (
    dream_id INTEGER NOT NULL REFERENCES dreams(id) ON DELETE CASCADE,
    tag_id   INTEGER NOT NULL REFERENCES dream_tags(id) ON DELETE CASCADE,
    PRIMARY KEY (dream_id, tag_id)
) STRICT;

CREATE INDEX idx_dream_tag_links_tag ON dream_tag_links(tag_id);

-- Voice notes. Rows here, ciphertext on disk, exactly like photos, voice clips
-- and note images — the core never sees audio bytes.
--
-- `transcript` is NULL until the user asks for one, and stays NULL when the
-- device cannot produce one on its own. Transcription is on-device only: the
-- platform default ships audio to Google or Apple, and a dream is the last
-- thing in this vault that should leave the phone.
CREATE TABLE dream_audio (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    dream_id    INTEGER NOT NULL REFERENCES dreams(id) ON DELETE CASCADE,
    file_path   TEXT    NOT NULL,
    duration_ms INTEGER NOT NULL,
    transcript  TEXT,
    position    INTEGER NOT NULL,
    created_ms  INTEGER NOT NULL
) STRICT;

CREATE INDEX idx_dream_audio_dream ON dream_audio(dream_id, position ASC);

-- Sleep sliders ride the shared metric model, so they arrive with the editor
-- that already reorders, renames and hides the journal and bleeding gauges —
-- and so a user-defined slider works here on day one. `enabled = 0` is what
-- "masquable dans les paramètres" means; nothing new is needed for it.
INSERT INTO metric_definitions (domain, metric_key, sort_order, builtin) VALUES
    ('dreams', 'sleep_quality',  0, 1),
    ('dreams', 'recall',         1, 1),
    ('dreams', 'vividness',      2, 1),
    ('dreams', 'emotional_tone', 3, 1);
