package com.segi.watchlist.library;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LocalMediaRepository extends JpaRepository<LocalMedia, Long> {
    List<LocalMedia> findAllByOrderByDisplayTitleAsc();
    List<LocalMedia> findByMediaKindOrderByDisplayTitleAsc(String mediaKind);
    List<LocalMedia> findByMediaKindOrderBySeriesTitleAscSeasonNumberAscEpisodeNumberAsc(String mediaKind);
    Optional<LocalMedia> findByFilePath(String filePath);
    long countByFilePathStartingWith(String filePathPrefix);
}
