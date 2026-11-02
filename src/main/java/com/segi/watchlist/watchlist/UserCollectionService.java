package com.segi.watchlist.watchlist;

import com.segi.watchlist.account.Account;
import com.segi.watchlist.account.AccountService;
import com.segi.watchlist.catalog.MovieCard;
import com.segi.watchlist.catalog.MovieCatalogService;
import com.segi.watchlist.catalog.MovieDetails;
import java.util.List;
import java.util.NoSuchElementException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserCollectionService {
    private final UserCollectionRepository collections;
    private final UserCollectionItemRepository items;
    private final AccountService accounts;
    private final MovieCatalogService catalog;

    public UserCollectionService(UserCollectionRepository collections, UserCollectionItemRepository items,
                                 AccountService accounts, MovieCatalogService catalog) {
        this.collections = collections;
        this.items = items;
        this.accounts = accounts;
        this.catalog = catalog;
    }

    @Transactional(readOnly = true)
    public List<CollectionCard> list(String email) {
        Account account = accounts.getRequiredByEmail(email);
        return collections.findByAccountIdOrderByNameAsc(account.getId()).stream()
                .map(collection -> new CollectionCard(collection.getId(), collection.getName(),
                        items.countByCollectionId(collection.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public UserCollection requireOwned(long id, String email) {
        Account account = accounts.getRequiredByEmail(email);
        return collections.findByIdAndAccountId(id, account.getId()).orElseThrow(NoSuchElementException::new);
    }

    @Transactional(readOnly = true)
    public List<UserCollectionItem> items(long id, String email) {
        requireOwned(id, email);
        return items.findByCollectionIdOrderByAddedAtDesc(id);
    }

    @Transactional
    public UserCollection create(String email, String rawName) {
        String name = rawName == null ? "" : rawName.trim().replaceAll("\\s+", " ");
        if (name.isBlank() || name.length() > 80) throw new IllegalArgumentException("Collection names must be 1–80 characters.");
        Account account = accounts.getRequiredByEmail(email);
        if (collections.existsByAccountIdAndNameIgnoreCase(account.getId(), name)) {
            throw new IllegalArgumentException("You already have a collection with that name.");
        }
        return collections.save(new UserCollection(account, name));
    }

    @Transactional
    public void delete(String email, long id) {
        UserCollection collection = requireOwned(id, email);
        collections.delete(collection);
    }

    @Transactional
    public void addTitle(String email, long collectionId, long tmdbId, String mediaType) {
        UserCollection collection = requireOwned(collectionId, email);
        MovieDetails details = catalog.details(tmdbId, mediaType);
        MovieCard movie = details.movie();
        if (items.existsByCollectionIdAndTmdbIdAndMediaType(collectionId, movie.id(), movie.mediaType())) return;
        String genres = String.join("|", details.genres());
        items.save(new UserCollectionItem(collection, movie.id(), movie.mediaType(), movie.title(),
                movie.posterPath(), movie.releaseDate(), movie.rating(), genres));
    }

    @Transactional
    public void removeItem(String email, long collectionId, long itemId) {
        requireOwned(collectionId, email);
        items.findByIdAndCollectionId(itemId, collectionId).ifPresent(items::delete);
    }

    public record CollectionCard(long id, String name, long itemCount) { }
}
