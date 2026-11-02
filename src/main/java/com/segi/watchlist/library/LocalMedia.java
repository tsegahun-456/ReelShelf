package com.segi.watchlist.library;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "local_media")
public class LocalMedia {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "file_path", nullable = false, unique = true, columnDefinition = "text")
    private String filePath;
    @Column(name = "file_name", nullable = false, length = 600)
    private String fileName;
    @Column(name = "display_title", nullable = false, length = 400)
    private String displayTitle;
    @Column(name = "media_kind", nullable = false, length = 16)
    private String mediaKind;
    @Column(name = "series_title", length = 300)
    private String seriesTitle;
    @Column(name = "season_number")
    private Integer seasonNumber;
    @Column(name = "episode_number")
    private Integer episodeNumber;
    @Column(name = "episode_title", length = 300)
    private String episodeTitle;
    @Column(name = "subtitle_path", columnDefinition = "text")
    private String subtitlePath;
    @Column(name = "file_size", nullable = false)
    private long fileSize;
    @Column(name = "modified_at", nullable = false)
    private Instant modifiedAt;
    @Column(name = "title_override", length = 400)
    private String titleOverride;
    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;
    @Column(name = "tmdb_id") private Long tmdbId;
    @Column(name = "tmdb_media_type", length = 16) private String tmdbMediaType;
    @Column(name = "tmdb_title", length = 300) private String tmdbTitle;
    @Column(name = "tmdb_overview", columnDefinition = "text") private String tmdbOverview;
    @Column(name = "tmdb_poster_path", length = 300) private String tmdbPosterPath;
    @Column(name = "tmdb_release_date", length = 32) private String tmdbReleaseDate;
    @Column(name = "tmdb_match_reviewed", nullable = false) private boolean tmdbMatchReviewed;
    @Column(name = "parser_version", nullable = false) private int parserVersion;

    protected LocalMedia() {}

    /** Applies a freshly scanned file; returns true only when a field actually changed. */
    boolean updateFrom(MediaScanner.MediaFile file) {
        boolean changed = id == null
                || !file.filePath().equals(filePath)
                || !java.util.Objects.equals(file.fileName(), fileName)
                || (titleOverride == null || titleOverride.isBlank())
                        && !java.util.Objects.equals(file.displayTitle(), displayTitle)
                || !java.util.Objects.equals(file.mediaKind(), mediaKind)
                || !java.util.Objects.equals(file.seriesTitle(), seriesTitle)
                || !java.util.Objects.equals(file.seasonNumber(), seasonNumber)
                || !java.util.Objects.equals(file.episodeNumber(), episodeNumber)
                || !java.util.Objects.equals(file.episodeTitle(), episodeTitle)
                || !java.util.Objects.equals(file.subtitlePath(), subtitlePath)
                || parserVersion != MediaScanner.PARSER_VERSION
                || file.fileSize() != fileSize
                || !java.util.Objects.equals(file.modifiedAt(), modifiedAt);
        if (id == null || !file.filePath().equals(filePath)) filePath = file.filePath();
        fileName = file.fileName();
        if (titleOverride == null || titleOverride.isBlank()) displayTitle = file.displayTitle();
        mediaKind = file.mediaKind();
        seriesTitle = file.seriesTitle();
        seasonNumber = file.seasonNumber();
        episodeNumber = file.episodeNumber();
        episodeTitle = file.episodeTitle();
        subtitlePath = file.subtitlePath();
        parserVersion = MediaScanner.PARSER_VERSION;
        fileSize = file.fileSize();
        modifiedAt = file.modifiedAt();
        if (changed) lastSeenAt = Instant.now();
        return changed;
    }

    public void rename(String title) {
        if (title == null || title.isBlank()) throw new IllegalArgumentException("A title is required.");
        titleOverride = title.trim().substring(0, Math.min(title.trim().length(), 400));
        displayTitle = titleOverride;
    }

    public void attachTmdb(com.segi.watchlist.catalog.MovieCard match) {
        tmdbId = match.id();
        tmdbMediaType = match.mediaType();
        tmdbTitle = match.title();
        tmdbOverview = match.overview();
        tmdbPosterPath = match.posterPath();
        tmdbReleaseDate = match.releaseDate();
    }

    public Long getId() { return id; }
    public String getFilePath() { return filePath; }
    public String getFileName() { return fileName; }
    public String getDisplayTitle() { return displayTitle; }
    public String getMediaKind() { return mediaKind; }
    public String getSeriesTitle() { return seriesTitle; }
    public Integer getSeasonNumber() { return seasonNumber; }
    public Integer getEpisodeNumber() { return episodeNumber; }
    public String getEpisodeTitle() { return episodeTitle; }
    public String getSubtitlePath() { return subtitlePath; }
    public long getFileSize() { return fileSize; }
    public Instant getModifiedAt() { return modifiedAt; }
    public Long getTmdbId() { return tmdbId; }
    public String getTmdbMediaType() { return tmdbMediaType; }
    public String getTmdbTitle() { return tmdbTitle; }
    public String getTmdbOverview() { return tmdbOverview; }
    public String getTmdbPosterPath() { return tmdbPosterPath; }
    public String getTmdbReleaseDate() { return tmdbReleaseDate; }
    public boolean isTmdbMatchReviewed() { return tmdbMatchReviewed; }
    public int getParserVersion() { return parserVersion; }
    public String getTmdbDetailsPath() { return "TV".equals(tmdbMediaType) ? "/tv/" + tmdbId : "/movie/" + tmdbId; }
}
