package com.segi.watchlist.library;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/** A folder the local media scanner walks; managed from the Settings page. */
@Entity
@Table(name = "media_scan_root")
public class MediaScanRoot {
    @Id
    @Column(name = "path", nullable = false, length = 1000)
    private String path;

    @Column(name = "added_at", nullable = false)
    private Instant addedAt;

    protected MediaScanRoot() {
    }

    public MediaScanRoot(String path) {
        this.path = path;
    }

    @PrePersist
    void onCreate() {
        if (addedAt == null) addedAt = Instant.now();
    }

    public String getPath() { return path; }
    public Instant getAddedAt() { return addedAt; }
}
