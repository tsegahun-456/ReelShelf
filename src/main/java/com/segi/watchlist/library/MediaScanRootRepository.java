package com.segi.watchlist.library;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MediaScanRootRepository extends JpaRepository<MediaScanRoot, String> {
    List<MediaScanRoot> findAllByOrderByPathAsc();
}
