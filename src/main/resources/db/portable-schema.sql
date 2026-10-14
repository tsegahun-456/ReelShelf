CREATE TABLE IF NOT EXISTS local_media_poster_cache (
    tmdb_media_type VARCHAR(16) NOT NULL,
    tmdb_id BIGINT NOT NULL,
    poster_path VARCHAR(300) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    poster_data BYTEA NOT NULL,
    cached_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tmdb_media_type, tmdb_id)
);

-- Persistent sign-in ("Keep me signed in"); the entity tables are also created
-- by JPA (ddl-auto=update) but are listed here to keep the portable schema explicit.
CREATE TABLE IF NOT EXISTS persistent_logins (
    username VARCHAR(320) NOT NULL,
    series VARCHAR(64) PRIMARY KEY,
    token VARCHAR(64) NOT NULL,
    last_used TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE IF NOT EXISTS firebase_credential (
    email VARCHAR(254) PRIMARY KEY,
    firebase_uid VARCHAR(128) NOT NULL,
    display_name VARCHAR(80) NOT NULL DEFAULT '',
    refresh_token_encrypted VARCHAR(1024) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Folders the local media scanner walks (Settings page).
CREATE TABLE IF NOT EXISTS media_scan_root (
    path VARCHAR(1000) PRIMARY KEY,
    added_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
