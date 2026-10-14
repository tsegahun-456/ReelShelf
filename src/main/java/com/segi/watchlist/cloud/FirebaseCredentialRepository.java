package com.segi.watchlist.cloud;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FirebaseCredentialRepository extends JpaRepository<FirebaseCredential, String> {
}
