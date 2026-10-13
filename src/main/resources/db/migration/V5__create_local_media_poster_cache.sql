CREATE TABLE local_media_poster_cache (
    tmdb_media_type VARCHAR(16) NOT NULL,
    tmdb_id BIGINT NOT NULL,
    poster_path VARCHAR(300) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    poster_data BYTEA NOT NULL,
    cached_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tmdb_media_type, tmdb_id)
);
