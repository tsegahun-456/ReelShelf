package com.segi.watchlist.watchlist;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WatchlistRepository extends JpaRepository<WatchlistEntry, Long> {
    List<WatchlistEntry> findByAccountIdOrderByCreatedAtDesc(Long accountId);
    boolean existsByAccountIdAndTmdbIdAndMediaType(Long accountId, long tmdbId, String mediaType);
    long countByAccountId(Long accountId);
    Optional<WatchlistEntry> findByIdAndAccountId(Long id, Long accountId);
}
