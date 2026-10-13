package com.segi.watchlist.account;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<Account, Long> {
    Optional<Account> findByEmailIgnoreCase(String email);
    Optional<Account> findByFirebaseUid(String firebaseUid);
    boolean existsByEmailIgnoreCase(String email);
}
