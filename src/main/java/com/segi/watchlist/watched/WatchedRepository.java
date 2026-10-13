package com.segi.watchlist.watched;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WatchedRepository extends JpaRepository<WatchedEntry, Long> {
    List<WatchedEntry> findByAccountIdOrderByWatchedAtDesc(Long accountId);
    boolean existsByAccountIdAndTmdbIdAndMediaType(Long accountId, long tmdbId, String mediaType);
    long countByAccountId(Long accountId);
    Optional<WatchedEntry> findByIdAndAccountId(Long id, Long accountId);
}
