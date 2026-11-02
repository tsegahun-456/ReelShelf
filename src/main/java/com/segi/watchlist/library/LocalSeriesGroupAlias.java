package com.segi.watchlist.library;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "local_series_group_alias")
public class LocalSeriesGroupAlias {
    @Id @Column(name = "alias_key", length = 300, nullable = false)
    private String aliasKey;
    @Column(name = "alias_title", length = 300, nullable = false)
    private String aliasTitle;
    @Column(name = "canonical_title", length = 300, nullable = false)
    private String canonicalTitle;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LocalSeriesGroupAlias() { }

    public LocalSeriesGroupAlias(String aliasKey, String aliasTitle, String canonicalTitle) {
        this.aliasKey = aliasKey;
        this.aliasTitle = aliasTitle;
        this.canonicalTitle = canonicalTitle;
    }

    @PrePersist void created() { if (createdAt == null) createdAt = Instant.now(); }
    public String getAliasKey() { return aliasKey; }
    public String getAliasTitle() { return aliasTitle; }
    public String getCanonicalTitle() { return canonicalTitle; }
}
