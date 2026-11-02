package com.segi.watchlist.catalog;

import com.segi.watchlist.watchlist.WatchlistEntry;
import com.segi.watchlist.watchlist.WatchlistService;
import com.segi.watchlist.watchlist.UserCollectionService;
import com.segi.watchlist.cloud.FirebaseSessionPrincipal;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.time.Year;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.client.RestClientException;

@Controller
public class CatalogController {
    private static final java.time.Duration RAIL_TTL = java.time.Duration.ofMinutes(5);
    private static final java.time.Duration RAIL_MISS_TTL = java.time.Duration.ofSeconds(30);

    private final MovieCatalogService catalog;
    private final WatchlistService watchlist;
    private final UserCollectionService collections;
    private final Map<String, CachedRail> railCache = new java.util.concurrent.ConcurrentHashMap<>();

    public CatalogController(MovieCatalogService catalog, WatchlistService watchlist,
                             UserCollectionService collections) {
        this.catalog = catalog;
        this.watchlist = watchlist;
        this.collections = collections;
    }

    @GetMapping("/")
    public String home(Model model, Authentication authentication) {
        model.addAttribute("activePage", "home");
        addAccount(model, authentication);
        List<MovieCard> trending = catalogList(model, () -> cachedRail("trending", catalog::trendingMovies));
        model.addAttribute("trending", trending);
        List<MediaRail> rails = new ArrayList<>();
        if (isSignedIn(authentication)) {
            watchlist.synchronize(authentication.getName(), principal(authentication));
            addRecommendations(rails, model, watchlist.list(authentication.getName()));
        }
        addRail(rails, model, "Popular movies", catalog::popularMovies);
        addRail(rails, model, "Newly released", catalog::recentMovies);
        addRail(rails, model, "Top rated movies", catalog::topRatedMovies);
        addRail(rails, model, "Coming soon", catalog::upcomingMovies);
        addRail(rails, model, "Popular series", catalog::popularSeries);
        addRail(rails, model, "On the air", catalog::onTheAirSeries);
        addRail(rails, model, "Top rated series", catalog::topRatedSeries);
        model.addAttribute("rails", rails);
        model.addAttribute("tmdbConfigured", catalog.isConfigured());
        return "catalog/home";
    }

    @GetMapping("/search")
    public String search(@RequestParam(defaultValue = "") String q,
                         @RequestParam(defaultValue = "MOVIE") String type,
                         @RequestParam(required = false) Long genre,
                         @RequestParam(required = false) Integer year,
                         @RequestParam(required = false) BigDecimal rating,
                         Model model,
                         Authentication authentication) {
        String mediaType = "TV".equalsIgnoreCase(type) ? "TV" : "MOVIE";
        model.addAttribute("activePage", mediaType.equals("TV") ? "series" : "search");
        addAccount(model, authentication);
        model.addAttribute("query", q);
        model.addAttribute("type", mediaType);
        model.addAttribute("selectedGenre", genre);
        model.addAttribute("selectedYear", year);
        model.addAttribute("selectedRating", rating == null ? "" : rating.stripTrailingZeros().toPlainString());
        model.addAttribute("genres", catalogValue(model, () -> catalog.genres(mediaType), List.of()));
        int currentYear = Year.now().getValue();
        model.addAttribute("years", java.util.stream.IntStream.rangeClosed(1970, currentYear)
                .map(i -> currentYear - (i - 1970)).boxed().toList());
        model.addAttribute("results", catalogValue(model,
                () -> catalog.search(q, mediaType, genre, year, rating), List.of()));
        return "catalog/search";
    }

    @GetMapping("/movie/{id}")
    public String movie(@PathVariable long id, Model model, Authentication authentication) {
        return details(id, "MOVIE", model, authentication);
    }

    @GetMapping("/tv/{id}")
    public String series(@PathVariable long id, Model model, Authentication authentication) {
        return details(id, "TV", model, authentication);
    }

    private String details(long id, String mediaType, Model model, Authentication authentication) {
        addAccount(model, authentication);
        if (isSignedIn(authentication)) model.addAttribute("userCollections", collections.list(authentication.getName()));
        model.addAttribute("mediaType", mediaType);
        model.addAttribute("details", null);
        try {
            model.addAttribute("details", catalog.details(id, mediaType));
        } catch (MovieCatalogService.TmdbNotConfiguredException exception) {
            model.addAttribute("pageMessage", "Add your TMDB API key to load this title.");
        } catch (RestClientException exception) {
            model.addAttribute("pageMessage", "This title is temporarily unavailable.");
        }
        return "catalog/details";
    }

    private void addRail(List<MediaRail> rails, Model model, String title,
                         Supplier<List<MovieCard>> titles) {
        List<MovieCard> result = catalogList(model, () -> cachedRail(title, titles));
        if (!result.isEmpty()) rails.add(new MediaRail(title, result));
    }

    /**
     * Serves home rails from a short-lived cache so the landing page is not eight serial TMDB calls
     * on every hit; a failing rail is cached briefly as empty so an outage cannot stall repeat loads.
     */
    private List<MovieCard> cachedRail(String key, Supplier<List<MovieCard>> loader) {
        java.time.Instant now = java.time.Instant.now();
        CachedRail hit = railCache.get(key);
        if (hit != null && now.isBefore(hit.expiresAt())) return hit.titles();
        try {
            List<MovieCard> titles = loader.get();
            railCache.put(key, new CachedRail(titles, now.plus(RAIL_TTL)));
            return titles;
        } catch (RuntimeException exception) {
            railCache.put(key, new CachedRail(List.of(), now.plus(RAIL_MISS_TTL)));
            throw exception;
        }
    }

    private record CachedRail(List<MovieCard> titles, java.time.Instant expiresAt) {}

    private void addRecommendations(List<MediaRail> rails, Model model, List<WatchlistEntry> savedTitles) {
        Map<String, MovieCard> recommendations = new LinkedHashMap<>();
        for (WatchlistEntry saved : savedTitles.stream().limit(3).toList()) {
            List<MovieCard> related = catalogValue(model,
                    () -> cachedRail("rec-" + saved.getMediaType() + "-" + saved.getTmdbId(),
                            () -> catalog.recommendations(saved.getMediaType(), saved.getTmdbId())), List.of());
            for (MovieCard title : related) {
                String key = title.mediaType() + ":" + title.id();
                boolean alreadySaved = savedTitles.stream().anyMatch(item ->
                        item.getMediaType().equals(title.mediaType()) && item.getTmdbId() == title.id());
                if (!alreadySaved) recommendations.putIfAbsent(key, title);
                if (recommendations.size() == 14) break;
            }
            if (recommendations.size() == 14) break;
        }
        if (!recommendations.isEmpty()) {
            rails.add(new MediaRail("Picked for your watchlist", List.copyOf(recommendations.values())));
        }
    }

    private List<MovieCard> catalogList(Model model, Supplier<List<MovieCard>> query) {
        return catalogValue(model, query, List.of());
    }

    private <T> T catalogValue(Model model, Supplier<T> query, T fallback) {
        try {
            return query.get();
        } catch (MovieCatalogService.TmdbNotConfiguredException exception) {
            model.addAttribute("catalogMessage", "Add your TMDB API key to load live movie and series listings.");
        } catch (RestClientException exception) {
            model.addAttribute("catalogMessage", "The catalog is unavailable right now. Please try again shortly.");
        }
        return fallback;
    }

    private void addAccount(Model model, Authentication authentication) {
        boolean signedIn = isSignedIn(authentication);
        model.addAttribute("signedIn", signedIn);
        if (signedIn) model.addAttribute("currentEmail", authentication.getName());
    }

    private boolean isSignedIn(Authentication authentication) {
        return authentication != null && authentication.isAuthenticated()
                && !"anonymousUser".equals(authentication.getPrincipal());
    }

    private FirebaseSessionPrincipal principal(Authentication authentication) {
        return authentication != null && authentication.getPrincipal() instanceof FirebaseSessionPrincipal firebase
                ? firebase : null;
    }
}
