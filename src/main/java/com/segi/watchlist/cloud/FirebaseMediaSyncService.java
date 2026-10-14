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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

@Service
public class FirebaseMediaSyncService {
    private static final Logger log = LoggerFactory.getLogger(FirebaseMediaSyncService.class);
    private final FirebaseProperties properties;
    private final FirebaseIdentityClient identity;
    private final FirestoreRestClient firestore;
    private final AccountService accounts;
    private final WatchlistRepository watchlist;
    private final WatchedRepository watched;
    private final FirebaseCredentialService credentials;

    public FirebaseMediaSyncService(FirebaseProperties properties, FirebaseIdentityClient identity,
                                    FirestoreRestClient firestore, AccountService accounts,
                                    WatchlistRepository watchlist, WatchedRepository watched,
                                    FirebaseCredentialService credentials) {
        this.properties = properties;
        this.identity = identity;
        this.firestore = firestore;
        this.accounts = accounts;
        this.watchlist = watchlist;
        this.watched = watched;
        this.credentials = credentials;
    }

    public boolean sync(Account account, FirebaseSessionPrincipal principal) {
        if (!properties.firestoreConfigured()) {
            log.warn("Firestore sync skipped: FIREBASE_WEB_API_KEY/FIREBASE_PROJECT_ID is not configured; "
                    + "lists are saved to the local database only.");
            return true;
        }
        if (principal == null) {
            log.warn("Firestore sync skipped: the session carries no Firebase principal "
                    + "(signed in before Firebase was configured?).");
            return false;
        }
        String uid = principal.uid();
        try {
            String idToken = identity.freshIdToken(principal);
            boolean initialized = firestore.initialized(uid, idToken);
            List<CloudTitle> remoteWatchlist = firestore.list(uid, "watchlist", idToken);
            List<CloudTitle> remoteWatched = firestore.list(uid, "watched", idToken);
            if (!initialized) {
                mergeFirstSync(account, uid, idToken, remoteWatchlist, remoteWatched);
                firestore.markInitialized(uid, idToken);
                log.info("Firestore first sync completed for uid={}: remoteWatchlist={} remoteWatched={}",
                        uid, remoteWatchlist.size(), remoteWatched.size());
            } else {
                applyRemoteWatchlist(account, remoteWatchlist);
                applyRemoteWatched(account, remoteWatched);
            }
            return true;
        } catch (FirebaseCloudException exception) {
            log.warn("Firestore sync failed for uid={}: {}", uid, exception.getMessage());
            return false;
        } catch (FirebaseIdentityException exception) {
            log.warn("Firestore sync failed for uid={}: Firebase identity/token error (code={})",
                    uid, exception.code());
            return false;
        } catch (RestClientException exception) {
            log.warn("Firestore sync failed for uid={}: {}: {}", uid,
                    exception.getClass().getSimpleName(), redact(exception.getMessage()));
            return false;
        }
    }

    public void requireSynchronized(Account account, FirebaseSessionPrincipal principal) {
        if (properties.firestoreConfigured() && !sync(account, principal)) {
            log.warn("List change refused before saving: cloud sync unavailable (accountId={}, uid={}).",
                    account.getId(), principal == null ? "none" : principal.uid());
            throw new FirebaseCloudException("Cloud sync is unavailable. No change was made; try again when online.");
        }
    }

    public void putWatchlist(FirebaseSessionPrincipal principal, WatchlistEntry entry) {
        if (!properties.firestoreConfigured()) return;
        String idToken = requireToken(principal);
        try {
            firestore.put(principal.uid(), "watchlist", toCloudTitle(entry), idToken);
            log.info("Firestore put ok: collection=watchlist uid={} docId={}", principal.uid(),
                    toCloudTitle(entry).documentId());
        } catch (RestClientException exception) {
            throw cloudUnavailable("write", "watchlist", principal, exception);
        }
    }

    public void deleteWatchlist(FirebaseSessionPrincipal principal, WatchlistEntry entry) {
        if (!properties.firestoreConfigured()) return;
        String idToken = requireToken(principal);
        try {
            firestore.delete(principal.uid(), "watchlist", toCloudTitle(entry), idToken);
            log.info("Firestore delete ok: collection=watchlist uid={} docId={}", principal.uid(),
                    toCloudTitle(entry).documentId());
        } catch (RestClientException exception) {
            throw cloudUnavailable("delete", "watchlist", principal, exception);
        }
    }

    public void putWatched(FirebaseSessionPrincipal principal, WatchedEntry entry) {
        if (!properties.firestoreConfigured()) return;
        String idToken = requireToken(principal);
        try {
            firestore.put(principal.uid(), "watched", toCloudTitle(entry), idToken);
            log.info("Firestore put ok: collection=watched uid={} docId={}", principal.uid(),
                    toCloudTitle(entry).documentId());
        } catch (RestClientException exception) {
            throw cloudUnavailable("write", "watched", principal, exception);
        }
    }

    public void deleteWatched(FirebaseSessionPrincipal principal, WatchedEntry entry) {
        if (!properties.firestoreConfigured()) return;
        String idToken = requireToken(principal);
        try {
            firestore.delete(principal.uid(), "watched", toCloudTitle(entry), idToken);
            log.info("Firestore delete ok: collection=watched uid={} docId={}", principal.uid(),
                    toCloudTitle(entry).documentId());
        } catch (RestClientException exception) {
            throw cloudUnavailable("delete", "watched", principal, exception);
        }
    }

    private String requireToken(FirebaseSessionPrincipal principal) {
        if (principal == null) {
            throw new FirebaseCloudException("Sign out and sign in again to enable cloud sync.");
        }
        try {
            boolean refreshNeeded = principal.tokenNeedsRefresh();
            String idToken = identity.freshIdToken(principal);
            // If the ID token was just renewed, Firebase may have rotated the refresh
            // token too; keep the newest one encrypted on disk for remembered sign-ins.
            if (refreshNeeded) credentials.persistRotatedRefreshToken(principal);
            return idToken;
        } catch (FirebaseIdentityException exception) {
            log.warn("Cloud write blocked: could not refresh the Firebase ID token for uid={} (code={})",
                    principal.uid(), exception.code());
            throw new FirebaseCloudException("Your cloud session expired. Sign out and sign in again.");
        } catch (RestClientException exception) {
            // Preserves the previous behaviour (the exception reaches the controller) but leaves a trace.
            log.warn("Cloud write blocked: token refresh unreachable for uid={}: {}: {}", principal.uid(),
                    exception.getClass().getSimpleName(), redact(exception.getMessage()));
            throw exception;
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

    private FirebaseCloudException cloudUnavailable(String operation, String collection,
                                                    FirebaseSessionPrincipal principal,
                                                    RestClientException exception) {
        log.warn("Firestore {} failed: collection={} uid={} {}: {}", operation, collection,
                principal.uid(), exception.getClass().getSimpleName(), redact(exception.getMessage()));
        return new FirebaseCloudException("Cloud sync is unavailable right now. Your local copy was not changed.");
    }

    /**
     * Removes anything that could leak a credential from an exception message (the refresh-token call
     * carries the web API key in its query string) and bounds the length. Never logs ID tokens,
     * refresh tokens, API keys or passwords.
     */
    private static String redact(String message) {
        if (message == null || message.isBlank()) return "";
        String clean = message
                .replaceAll("(?i)(key=)[^&\\s\"']+", "$1REDACTED")
                .replaceAll("(?i)(Bearer\\s)\\S+", "$1REDACTED")
                .replaceAll("\\s+", " ")
                .trim();
        return clean.length() > 300 ? clean.substring(0, 300) + "..." : clean;
    }
}
