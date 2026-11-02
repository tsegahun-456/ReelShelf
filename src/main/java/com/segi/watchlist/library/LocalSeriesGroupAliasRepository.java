package com.segi.watchlist.library;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LocalSeriesGroupAliasRepository extends JpaRepository<LocalSeriesGroupAlias, String> {
    List<LocalSeriesGroupAlias> findAllByOrderByCanonicalTitleAscAliasTitleAsc();
}
