package com.segi.watchlist.library;

import java.util.List;
import java.util.Optional;
import java.util.Collection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface LocalMediaRepository extends JpaRepository<LocalMedia, Long> {
    List<LocalMedia> findAllByOrderByDisplayTitleAsc();
    List<LocalMedia> findByMediaKindOrderByDisplayTitleAsc(String mediaKind);
    List<LocalMedia> findByMediaKindOrderBySeriesTitleAscSeasonNumberAscEpisodeNumberAsc(String mediaKind);
    Optional<LocalMedia> findByFilePath(String filePath);
    long countByFilePathStartingWith(String filePathPrefix);

    /** Refreshes scanner-owned fields without merging an old entity over TMDB match fields. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update LocalMedia media
               set media.fileName = :fileName,
                   media.displayTitle = case
                       when media.titleOverride is null or trim(media.titleOverride) = '' then :displayTitle
                       else media.displayTitle
                   end,
                   media.mediaKind = :mediaKind,
                   media.seriesTitle = :seriesTitle,
                   media.seasonNumber = :seasonNumber,
                   media.episodeNumber = :episodeNumber,
                   media.episodeTitle = :episodeTitle,
                   media.subtitlePath = :subtitlePath,
                   media.fileSize = :fileSize,
                   media.modifiedAt = :modifiedAt,
                   media.lastSeenAt = :lastSeenAt
             where media.filePath = :filePath
            """)
    int updateScannedMetadata(@Param("filePath") String filePath,
                              @Param("fileName") String fileName,
                              @Param("displayTitle") String displayTitle,
                              @Param("mediaKind") String mediaKind,
                              @Param("seriesTitle") String seriesTitle,
                              @Param("seasonNumber") Integer seasonNumber,
                              @Param("episodeNumber") Integer episodeNumber,
                              @Param("episodeTitle") String episodeTitle,
                              @Param("subtitlePath") String subtitlePath,
                              @Param("fileSize") long fileSize,
                              @Param("modifiedAt") java.time.Instant modifiedAt,
                              @Param("lastSeenAt") java.time.Instant lastSeenAt);

    /** Applies an explicit user-selected match to every episode in a series. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update LocalMedia media
               set media.tmdbId = :tmdbId,
                   media.tmdbMediaType = :tmdbMediaType,
                   media.tmdbTitle = :tmdbTitle,
                   media.tmdbOverview = :tmdbOverview,
                   media.tmdbPosterPath = :tmdbPosterPath,
                   media.tmdbReleaseDate = :tmdbReleaseDate
             where media.id in :ids
            """)
    int updateTmdbMatchForIds(@Param("ids") Collection<Long> ids,
                              @Param("tmdbId") long tmdbId,
                              @Param("tmdbMediaType") String tmdbMediaType,
                              @Param("tmdbTitle") String tmdbTitle,
                              @Param("tmdbOverview") String tmdbOverview,
                              @Param("tmdbPosterPath") String tmdbPosterPath,
                              @Param("tmdbReleaseDate") String tmdbReleaseDate);

    /** Background matches may fill an empty link, but must never replace a user's manual choice. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update LocalMedia media
               set media.tmdbId = :tmdbId,
                   media.tmdbMediaType = :tmdbMediaType,
                   media.tmdbTitle = :tmdbTitle,
                   media.tmdbOverview = :tmdbOverview,
                   media.tmdbPosterPath = :tmdbPosterPath,
                   media.tmdbReleaseDate = :tmdbReleaseDate
             where media.id in :ids
               and media.tmdbId is null
            """)
    int updateUnmatchedTmdbForIds(@Param("ids") Collection<Long> ids,
                                  @Param("tmdbId") long tmdbId,
                                  @Param("tmdbMediaType") String tmdbMediaType,
                                  @Param("tmdbTitle") String tmdbTitle,
                                  @Param("tmdbOverview") String tmdbOverview,
                                  @Param("tmdbPosterPath") String tmdbPosterPath,
                                  @Param("tmdbReleaseDate") String tmdbReleaseDate);
}
