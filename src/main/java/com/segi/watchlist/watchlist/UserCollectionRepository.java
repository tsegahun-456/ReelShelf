package com.segi.watchlist.watchlist;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserCollectionRepository extends JpaRepository<UserCollection, Long> {
    List<UserCollection> findByAccountIdOrderByNameAsc(Long accountId);
    Optional<UserCollection> findByIdAndAccountId(Long id, Long accountId);
    boolean existsByAccountIdAndNameIgnoreCase(Long accountId, String name);
}
