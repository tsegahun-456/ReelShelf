ALTER TABLE local_media ADD COLUMN tmdb_match_reviewed BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE local_series_group_alias (
    alias_key VARCHAR(300) PRIMARY KEY,
    alias_title VARCHAR(300) NOT NULL,
    canonical_title VARCHAR(300) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
