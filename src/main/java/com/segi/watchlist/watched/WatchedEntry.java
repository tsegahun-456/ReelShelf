package com.segi.watchlist.watched;

import com.segi.watchlist.account.Account;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "watched_entry", uniqueConstraints = @UniqueConstraint(
        name = "watched_entry_unique_media",
        columnNames = {"user_id", "tmdb_id", "media_type"}))
public class WatchedEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private Account account;

    @Column(name = "tmdb_id", nullable = false)
    private long tmdbId;

    @Column(name = "media_type", nullable = false, length = 16)
    private String mediaType;

    @Column(nullable = false, length = 300)
    private String title;

    @Column(name = "poster_path", length = 300)
    private String posterPath;

    @Column(name = "backdrop_path", length = 300)
    private String backdropPath;

    @Column(name = "release_date", length = 32)
    private String releaseDate;

    @Column(name = "vote_average", precision = 4, scale = 2)
    private BigDecimal voteAverage;

    @Column(name = "user_rating")
    private Integer userRating;

    @Column(name = "watched_at", nullable = false)
    private Instant watchedAt;

    protected WatchedEntry() {
    }

    public WatchedEntry(Account account, long tmdbId, String mediaType, String title, String posterPath,
                        String backdropPath, String releaseDate, BigDecimal voteAverage) {
        this(account, tmdbId, mediaType, title, posterPath, backdropPath, releaseDate, voteAverage,
                Instant.now(), null);
    }

    public WatchedEntry(Account account, long tmdbId, String mediaType, String title, String posterPath,
                        String backdropPath, String releaseDate, BigDecimal voteAverage,
                        Instant watchedAt, Integer userRating) {
        this.account = account;
        this.tmdbId = tmdbId;
        this.mediaType = mediaType;
        this.title = title;
        this.posterPath = posterPath;
        this.backdropPath = backdropPath;
        this.releaseDate = releaseDate;
        this.voteAverage = voteAverage;
        this.watchedAt = watchedAt == null ? Instant.now() : watchedAt;
        this.userRating = userRating;
    }

    public Long getId() { return id; }
    public long getTmdbId() { return tmdbId; }
    public String getMediaType() { return mediaType; }
    public String getTitle() { return title; }
    public String getPosterPath() { return posterPath; }
    public String getBackdropPath() { return backdropPath; }
    public String getReleaseDate() { return releaseDate; }
    public BigDecimal getVoteAverage() { return voteAverage; }
    public Integer getUserRating() { return userRating; }
    public Instant getWatchedAt() { return watchedAt; }
    public String getDetailsPath() { return "TV".equals(mediaType) ? "/tv/" + tmdbId : "/movie/" + tmdbId; }
    public void setUserRating(Integer userRating) { this.userRating = userRating; }
    public void applyCloudValues(String title, String posterPath, String backdropPath, String releaseDate,
                                 BigDecimal voteAverage, Instant watchedAt, Integer userRating) {
        this.title = title;
        this.posterPath = posterPath;
        this.backdropPath = backdropPath;
        this.releaseDate = releaseDate;
        this.voteAverage = voteAverage;
        if (watchedAt != null) this.watchedAt = watchedAt;
        this.userRating = userRating;
    }
}
