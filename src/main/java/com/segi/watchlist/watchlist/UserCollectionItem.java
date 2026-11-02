package com.segi.watchlist.watchlist;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "user_collection_item", uniqueConstraints = @UniqueConstraint(
        name = "user_collection_item_title_unique", columnNames = {"collection_id", "tmdb_id", "media_type"}))
public class UserCollectionItem {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "collection_id", nullable = false)
    private UserCollection collection;

    @Column(name = "tmdb_id", nullable = false)
    private long tmdbId;
    @Column(name = "media_type", nullable = false, length = 16)
    private String mediaType;
    @Column(nullable = false, length = 300)
    private String title;
    @Column(name = "poster_path", length = 300)
    private String posterPath;
    @Column(name = "release_date", length = 32)
    private String releaseDate;
    @Column(name = "vote_average", precision = 4, scale = 2)
    private BigDecimal voteAverage;
    @Column(name = "genre_names", length = 1000)
    private String genreNames;
    @Column(name = "added_at", nullable = false)
    private Instant addedAt;

    protected UserCollectionItem() { }

    public UserCollectionItem(UserCollection collection, long tmdbId, String mediaType, String title,
                              String posterPath, String releaseDate, BigDecimal voteAverage, String genreNames) {
        this.collection = collection;
        this.tmdbId = tmdbId;
        this.mediaType = mediaType;
        this.title = title;
        this.posterPath = posterPath;
        this.releaseDate = releaseDate;
        this.voteAverage = voteAverage;
        this.genreNames = genreNames;
        this.addedAt = Instant.now();
    }

    public Long getId() { return id; }
    public long getTmdbId() { return tmdbId; }
    public String getMediaType() { return mediaType; }
    public String getTitle() { return title; }
    public String getPosterPath() { return posterPath; }
    public String getReleaseDate() { return releaseDate; }
    public BigDecimal getVoteAverage() { return voteAverage; }
    public String getGenreNames() { return genreNames == null ? "" : genreNames; }
    public String getYear() { return releaseDate != null && releaseDate.length() >= 4 ? releaseDate.substring(0, 4) : ""; }
    public String getDetailsPath() { return "TV".equals(mediaType) ? "/tv/" + tmdbId : "/movie/" + tmdbId; }
}
