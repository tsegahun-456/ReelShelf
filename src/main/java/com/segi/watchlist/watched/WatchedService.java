package com.segi.watchlist.watched;

import com.segi.watchlist.account.Account;
import com.segi.watchlist.account.AccountService;
import com.segi.watchlist.catalog.MovieCard;
import com.segi.watchlist.catalog.MovieCatalogService;
import com.segi.watchlist.cloud.FirebaseMediaSyncService;
import com.segi.watchlist.cloud.FirebaseSessionPrincipal;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WatchedService {
    private final WatchedRepository entries;
    private final AccountService accounts;
    private final MovieCatalogService catalog;
    private final FirebaseMediaSyncService cloudSync;

    public WatchedService(WatchedRepository entries, AccountService accounts,
                         MovieCatalogService catalog, FirebaseMediaSyncService cloudSync) {
        this.entries = entries;
        this.accounts = accounts;
        this.catalog = catalog;
        this.cloudSync = cloudSync;
    }

    @Transactional(readOnly = true)
    public List<WatchedEntry> list(String email) {
        Account account = accounts.getRequiredByEmail(email);
        return entries.findByAccountIdOrderByWatchedAtDesc(account.getId());
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
        WatchedEntry entry = new WatchedEntry(account, movie.id(), type, movie.title(), movie.posterPath(),
                movie.backdropPath(), movie.releaseDate(), movie.rating());
        cloudSync.putWatched(principal, entry);
        entries.save(entry);
    }

    public void rate(String email, long entryId, int rating, FirebaseSessionPrincipal principal) {
        if (rating < 1 || rating > 5) throw new IllegalArgumentException("Rating must be between 1 and 5.");
        Account account = accounts.getRequiredByEmail(email);
        cloudSync.requireSynchronized(account, principal);
        entries.findByIdAndAccountId(entryId, account.getId()).ifPresent(entry -> {
            entry.setUserRating(rating);
            cloudSync.putWatched(principal, entry);
            entries.save(entry);
        });
    }

    public void remove(String email, long entryId, FirebaseSessionPrincipal principal) {
        Account account = accounts.getRequiredByEmail(email);
        cloudSync.requireSynchronized(account, principal);
        entries.findByIdAndAccountId(entryId, account.getId()).ifPresent(entry -> {
            cloudSync.deleteWatched(principal, entry);
            entries.delete(entry);
        });
    }
}
