-- Phase 1 initial schema.
-- Carries only what's needed for security onboarding: one user row + app_settings.
-- Domain tables (medications, journal, hormones, photos, audios, reminders, …)
-- are added by later migrations, each in its own file.

CREATE TABLE users (
    id              INTEGER PRIMARY KEY CHECK (id = 1),
    security_mode   TEXT    NOT NULL,
    kdf_salt        BLOB,                       -- NULL unless mode involves a passphrase
    kdf_m_cost_kib  INTEGER,                    -- Argon2id memory cost
    kdf_t_cost      INTEGER,                    -- Argon2id iterations
    kdf_p_cost      INTEGER,                    -- Argon2id lanes
    wrapped_db_key  BLOB,                       -- NULL in paranoid mode
    created_at_ms   INTEGER NOT NULL
) STRICT;

CREATE TABLE app_settings (
    key   TEXT PRIMARY KEY,
    value BLOB NOT NULL
) STRICT;
