ALTER TABLE local_media
    ADD COLUMN tmdb_id BIGINT,
    ADD COLUMN tmdb_media_type VARCHAR(16),
    ADD COLUMN tmdb_title VARCHAR(300),
    ADD COLUMN tmdb_overview TEXT,
    ADD COLUMN tmdb_poster_path VARCHAR(300),
    ADD COLUMN tmdb_release_date VARCHAR(32);
