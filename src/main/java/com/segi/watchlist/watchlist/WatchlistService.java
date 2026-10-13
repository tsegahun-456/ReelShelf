package com.segi.watchlist.watchlist;

import com.segi.watchlist.account.Account;
import com.segi.watchlist.account.AccountService;
import com.segi.watchlist.catalog.MovieCard;
import com.segi.watchlist.catalog.MovieCatalogService;
import com.segi.watchlist.cloud.FirebaseCloudException;
import com.segi.watchlist.cloud.FirebaseMediaSyncService;
import com.segi.watchlist.cloud.FirebaseSessionPrincipal;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WatchlistService {
    private final WatchlistRepository entries;
    private final AccountService accounts;
    private final MovieCatalogService catalog;
    private final FirebaseMediaSyncService cloudSync;

    public WatchlistService(WatchlistRepository entries, AccountService accounts,
                            MovieCatalogService catalog, FirebaseMediaSyncService cloudSync) {
        this.entries = entries;
        this.accounts = accounts;
        this.catalog = catalog;
        this.cloudSync = cloudSync;
    }

    @Transactional(readOnly = true)
    public List<WatchlistEntry> list(String email) {
        Account account = accounts.getRequiredByEmail(email);
        return entries.findByAccountIdOrderByCreatedAtDesc(account.getId());
    }

    public boolean synchronize(String email, FirebaseSessionPrincipal principal) {
        Account account = accounts.getRequiredByEmail(email);
        return cloudSync.sync(account, principal);
    }

    public void addMedia(String email, long tmdbId, String mediaType, FirebaseSessionPrincipal principal) {
        MovieCard movie = catalog.details(tmdbId, mediaType).movie();
        String type = movie.mediaType();
        Account account = accounts.getRequiredByEmail(email);
        cloudSync.requireSynchronized(account, principal);
        if (entries.existsByAccountIdAndTmdbIdAndMediaType(account.getId(), tmdbId, type)) {
            return;
        }
        WatchlistEntry entry = new WatchlistEntry(account, movie.id(), type, movie.title(), movie.posterPath(),
                movie.backdropPath(), movie.releaseDate(), movie.rating());
        cloudSync.putWatchlist(principal, entry);
        entries.save(entry);
    }

    public void remove(String email, long entryId, FirebaseSessionPrincipal principal) {
        Account account = accounts.getRequiredByEmail(email);
        cloudSync.requireSynchronized(account, principal);
        entries.findByIdAndAccountId(entryId, account.getId()).ifPresent(entry -> {
            cloudSync.deleteWatchlist(principal, entry);
            entries.delete(entry);
        });
    }
}
