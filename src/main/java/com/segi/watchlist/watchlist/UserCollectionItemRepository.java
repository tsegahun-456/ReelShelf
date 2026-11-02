package com.segi.watchlist.watchlist;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserCollectionItemRepository extends JpaRepository<UserCollectionItem, Long> {
    List<UserCollectionItem> findByCollectionIdOrderByAddedAtDesc(Long collectionId);
    Optional<UserCollectionItem> findByIdAndCollectionId(Long id, Long collectionId);
    boolean existsByCollectionIdAndTmdbIdAndMediaType(Long collectionId, long tmdbId, String mediaType);
    long countByCollectionId(Long collectionId);
}
