-- Persistent sign-in ("Keep me signed in"): Spring's remember-me token store
-- plus the encrypted Firebase refresh token used to restore the session.
CREATE TABLE persistent_logins (
    username VARCHAR(320) NOT NULL,
    series VARCHAR(64) PRIMARY KEY,
    token VARCHAR(64) NOT NULL,
    last_used TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX persistent_logins_username_idx ON persistent_logins (username);

CREATE TABLE firebase_credential (
    email VARCHAR(254) PRIMARY KEY,
    firebase_uid VARCHAR(128) NOT NULL,
    display_name VARCHAR(80) NOT NULL DEFAULT '',
    refresh_token_encrypted VARCHAR(1024) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
