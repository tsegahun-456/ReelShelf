-- Folders the local media scanner walks; editable from the Settings page.
CREATE TABLE media_scan_root (
    path VARCHAR(1000) PRIMARY KEY,
    added_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
