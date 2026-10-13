ALTER TABLE app_user ADD COLUMN firebase_uid VARCHAR(128);
CREATE UNIQUE INDEX app_user_firebase_uid_unique
    ON app_user (firebase_uid)
    WHERE firebase_uid IS NOT NULL;
