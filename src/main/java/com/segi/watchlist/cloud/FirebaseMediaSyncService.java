package com.segi.watchlist.cloud;

import com.segi.watchlist.account.Account;
import com.segi.watchlist.account.AccountService;
import com.segi.watchlist.watched.WatchedEntry;
import com.segi.watchlist.watched.WatchedRepository;
import com.segi.watchlist.watchlist.WatchlistEntry;
import com.segi.watchlist.watchlist.WatchlistRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

@Service
public class FirebaseMediaSyncService {
    private final FirebaseProperties properties;
    private final FirebaseIdentityClient identity;
    private final FirestoreRestClient firestore;
    private final AccountService accounts;
    private final WatchlistRepository watchlist;
    private final WatchedRepository watched;

    public FirebaseMediaSyncService(FirebaseProperties properties, FirebaseIdentityClient identity,
                                    FirestoreRestClient firestore, AccountService accounts,
                                    WatchlistRepository watchlist, WatchedRepository watched) {
        this.properties = properties;
        this.identity = identity;
        this.firestore = firestore;
        this.accounts = accounts;
        this.watchlist = watchlist;
        this.watched = watched;
    }

    public boolean sync(Account account, FirebaseSessionPrincipal principal) {
        if (!properties.firestoreConfigured()) return true;
        if (principal == null) return false;
        try {
            String idToken = identity.freshIdToken(principal);
            boolean initialized = firestore.initialized(principal.uid(), idToken);
            List<CloudTitle> remoteWatchlist = firestore.list(principal.uid(), "watchlist", idToken);
            List<CloudTitle> remoteWatched = firestore.list(principal.uid(), "watched", idToken);
            if (!initialized) {
                mergeFirstSync(account, principal.uid(), idToken, remoteWatchlist, remoteWatched);
                firestore.markInitialized(principal.uid(), idToken);
            } else {
                applyRemoteWatchlist(account, remoteWatchlist);
                applyRemoteWatched(account, remoteWatched);
            }
            return true;
        } catch (FirebaseCloudException | FirebaseIdentityException | RestClientException exception) {
            return false;
        }
    }

    public void requireSynchronized(Account account, FirebaseSessionPrincipal principal) {
        if (properties.firestoreConfigured() && !sync(account, principal)) {
            throw new FirebaseCloudException("Cloud sync is unavailable. No change was made; try again when online.");
        }
    }

    public void putWatchlist(FirebaseSessionPrincipal principal, WatchlistEntry entry) {
        if (!properties.firestoreConfigured()) return;
        String idToken = requireToken(principal);
        try {
            firestore.put(principal.uid(), "watchlist", toCloudTitle(entry), idToken);
        } catch (RestClientException exception) {
            throw cloudUnavailable();
        }
    }

    public void deleteWatchlist(FirebaseSessionPrincipal principal, WatchlistEntry entry) {
        if (!properties.firestoreConfigured()) return;
        String idToken = requireToken(principal);
        try {
            firestore.delete(principal.uid(), "watchlist", toCloudTitle(entry), idToken);
        } catch (RestClientException exception) {
            throw cloudUnavailable();
        }
    }

    public void putWatched(FirebaseSessionPrincipal principal, WatchedEntry entry) {
        if (!properties.firestoreConfigured()) return;
        String idToken = requireToken(principal);
        try {
            firestore.put(principal.uid(), "watched", toCloudTitle(entry), idToken);
        } catch (RestClientException exception) {
            throw cloudUnavailable();
        }
    }

    public void deleteWatched(FirebaseSessionPrincipal principal, WatchedEntry entry) {
        if (!properties.firestoreConfigured()) return;
        String idToken = requireToken(principal);
        try {
            firestore.delete(principal.uid(), "watched", toCloudTitle(entry), idToken);
        } catch (RestClientException exception) {
            throw cloudUnavailable();
        }
    }

    private String requireToken(FirebaseSessionPrincipal principal) {
        if (principal == null) {
            throw new FirebaseCloudException("Sign out and sign in again to enable cloud sync.");
        }
        try {
            return identity.freshIdToken(principal);
        } catch (FirebaseIdentityException exception) {
            throw new FirebaseCloudException("Your cloud session expired. Sign out and sign in again.");
        }
    }

    private void mergeFirstSync(Account account, String uid, String idToken,
                                List<CloudTitle> remoteWatchlist, List<CloudTitle> remoteWatched) {
        Map<String, CloudTitle> watchTitles = byId(remoteWatchlist);
        Map<String, WatchlistEntry> localWatchTitles = localWatchlist(account);
        applyWatchlistTitles(account, remoteWatchlist, localWatchTitles);
        for (WatchlistEntry local : localWatchTitles.values()) {
            if (!watchTitles.containsKey(key(local.getMediaType(), local.getTmdbId()))) {
                firestore.put(uid, "watchlist", toCloudTitle(local), idToken);
            }
        }

        Map<String, CloudTitle> watchedTitles = byId(remoteWatched);
        Map<String, WatchedEntry> localWatchedTitles = localWatched(account);
        applyWatchedTitles(account, remoteWatched, localWatchedTitles);
        for (WatchedEntry local : localWatchedTitles.values()) {
            if (!watchedTitles.containsKey(key(local.getMediaType(), local.getTmdbId()))) {
                firestore.put(uid, "watched", toCloudTitle(local), idToken);
            }
        }
    }

    private void applyRemoteWatchlist(Account account, List<CloudTitle> remote) {
        Map<String, CloudTitle> titles = byId(remote);
        Map<String, WatchlistEntry> local = localWatchlist(account);
        local.entrySet().removeIf(entry -> {
            if (titles.containsKey(entry.getKey())) return false;
            watchlist.delete(entry.getValue());
            return true;
        });
        applyWatchlistTitles(account, remote, local);
    }

    private void applyRemoteWatched(Account account, List<CloudTitle> remote) {
        Map<String, CloudTitle> titles = byId(remote);
        Map<String, WatchedEntry> local = localWatched(account);
        local.entrySet().removeIf(entry -> {
            if (titles.containsKey(entry.getKey())) return false;
            watched.delete(entry.getValue());
            return true;
        });
        applyWatchedTitles(account, remote, local);
    }

    private void applyWatchlistTitles(Account account, List<CloudTitle> remote,
                                     Map<String, WatchlistEntry> local) {
        for (CloudTitle title : remote) {
            String key = key(title.mediaType(), title.tmdbId());
            WatchlistEntry entry = local.get(key);
            if (entry == null) {
                entry = new WatchlistEntry(account, title.tmdbId(), title.mediaType(), title.title(),
                        title.posterPath(), title.backdropPath(), title.releaseDate(), title.voteAverage(),
                        title.occurredAt());
            } else {
                entry.applyCloudValues(title.title(), title.posterPath(), title.backdropPath(),
                        title.releaseDate(), title.voteAverage(), title.occurredAt());
            }
            watchlist.save(entry);
            local.put(key, entry);
        }
    }

    private void applyWatchedTitles(Account account, List<CloudTitle> remote,
                                    Map<String, WatchedEntry> local) {
        for (CloudTitle title : remote) {
            String key = key(title.mediaType(), title.tmdbId());
            WatchedEntry entry = local.get(key);
            if (entry == null) {
                entry = new WatchedEntry(account, title.tmdbId(), title.mediaType(), title.title(),
                        title.posterPath(), title.backdropPath(), title.releaseDate(), title.voteAverage(),
                        title.occurredAt(), title.userRating());
            } else {
                entry.applyCloudValues(title.title(), title.posterPath(), title.backdropPath(),
                        title.releaseDate(), title.voteAverage(), title.occurredAt(), title.userRating());
            }
            watched.save(entry);
            local.put(key, entry);
        }
    }

    private Map<String, WatchlistEntry> localWatchlist(Account account) {
        Map<String, WatchlistEntry> result = new HashMap<>();
        for (WatchlistEntry entry : watchlist.findByAccountIdOrderByCreatedAtDesc(account.getId())) {
            result.put(key(entry.getMediaType(), entry.getTmdbId()), entry);
        }
        return result;
    }

    private Map<String, WatchedEntry> localWatched(Account account) {
        Map<String, WatchedEntry> result = new HashMap<>();
        for (WatchedEntry entry : watched.findByAccountIdOrderByWatchedAtDesc(account.getId())) {
            result.put(key(entry.getMediaType(), entry.getTmdbId()), entry);
        }
        return result;
    }

    private CloudTitle toCloudTitle(WatchlistEntry entry) {
        return new CloudTitle(entry.getTmdbId(), entry.getMediaType(), entry.getTitle(), entry.getPosterPath(),
                entry.getBackdropPath(), entry.getReleaseDate(), entry.getVoteAverage(), null, entry.getCreatedAt());
    }

    private CloudTitle toCloudTitle(WatchedEntry entry) {
        return new CloudTitle(entry.getTmdbId(), entry.getMediaType(), entry.getTitle(), entry.getPosterPath(),
                entry.getBackdropPath(), entry.getReleaseDate(), entry.getVoteAverage(), entry.getUserRating(),
                entry.getWatchedAt());
    }

    private Map<String, CloudTitle> byId(List<CloudTitle> titles) {
        Map<String, CloudTitle> result = new HashMap<>();
        for (CloudTitle title : titles) result.put(key(title.mediaType(), title.tmdbId()), title);
        return result;
    }

    private String key(String mediaType, long tmdbId) { return mediaType + ":" + tmdbId; }

    private FirebaseCloudException cloudUnavailable() {
        return new FirebaseCloudException("Cloud sync is unavailable right now. Your local copy was not changed.");
    }
}
