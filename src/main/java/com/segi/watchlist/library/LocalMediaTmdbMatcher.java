package com.segi.watchlist.library;

import com.segi.watchlist.catalog.MovieCard;
import com.segi.watchlist.catalog.MovieCatalogService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

/** Adds optional TMDB artwork to local files without making local browsing depend on TMDB. */
@Service
public class LocalMediaTmdbMatcher {
    private static final Logger log = LoggerFactory.getLogger(LocalMediaTmdbMatcher.class);
    private final LocalMediaRepository repository;
    private final MovieCatalogService catalog;
    private final LocalSeriesGroupAliasService seriesAliases;
    private final int matchBudget;

    public LocalMediaTmdbMatcher(LocalMediaRepository repository, MovieCatalogService catalog,
                                 LocalSeriesGroupAliasService seriesAliases,
                                 @org.springframework.beans.factory.annotation.Value("${library.tmdb-match-budget:40}") int matchBudget) {
        this.repository = repository;
        this.catalog = catalog;
        this.seriesAliases = seriesAliases;
        this.matchBudget = matchBudget;
    }

    @Scheduled(initialDelayString = "${library.tmdb-match-initial-delay-ms:30000}",
            fixedDelayString = "${library.tmdb-match-interval-ms:900000}")
    public void matchUnlinkedMedia() {
        if (!catalog.isConfigured()) return;
        List<LocalMedia> localRows = repository.findAllByOrderByDisplayTitleAsc();
        Map<String, MatchTarget> targets = new LinkedHashMap<>();
        for (LocalMedia row : localRows) {
            if (row.getTmdbId() != null) continue;
            boolean episode = "EPISODE".equals(row.getMediaKind());
            String title = episode ? seriesAliases.canonicalTitle(row.getSeriesTitle()) : row.getDisplayTitle();
            if (title == null || title.isBlank()) continue;
            String type = episode ? "TV" : "MOVIE";
            // Keep remakes with the same normalized name in separate targets. Otherwise the
            // first row's release year and TMDB result would be applied to every remake.
            String key = type + ":" + normalize(title) + ":" + releaseYear(title);
            MatchTarget target = targets.computeIfAbsent(key, ignored -> new MatchTarget(title, type));
            if (row.getId() != null) target.ids().add(row.getId());
        }

        log.info("Checking {} unmatched local titles for optional TMDB artwork.", targets.size());
        int linkedTitles = 0;
        int linkedFiles = 0;
        int searched = 0;
        for (MatchTarget target : targets.values()) {
            // Cap the burst so matcher passes stay small and never crowd out page loads competing
            // for the same TMDB rate limit; the rest is picked up on the next scheduled pass.
            if (searched >= matchBudget) {
                log.info("Match budget of {} titles reached; remaining titles wait for the next pass.", matchBudget);
                break;
            }
            searched++;
            try {
                String searchTitle = withoutReleaseYear(target.title());
                String year = releaseYear(target.title());
                Integer yearFilter = year == null ? null : Integer.valueOf(year);
                List<MovieCard> choices = catalog.search(searchTitle, target.mediaType(), null, yearFilter, null);
                if (choices.isEmpty() && yearFilter != null) {
                    choices = catalog.search(searchTitle, target.mediaType(), null, null, null);
                }
                List<MovieCard> candidates = choices.stream().filter(this::hasPoster).toList();
                List<MovieCard> sameYear = year == null ? List.of() : candidates.stream()
                        .filter(card -> year.equals(releaseYear(card.releaseDate()))).toList();
                List<MovieCard> eligible = year == null ? candidates : sameYear;
                MovieCard best = confidentMatch(searchTitle, eligible);
                if (best != null) {
                    int updated = repository.updateUnmatchedTmdbForIds(target.ids(), best.id(), best.mediaType(),
                            best.title(), best.overview(), best.posterPath(), best.releaseDate());
                    if (updated > 0) {
                        linkedTitles++;
                        linkedFiles += updated;
                    }
                } else {
                    MovieCard closest = choices.stream()
                            .max(java.util.Comparator.comparingDouble(card -> similarity(searchTitle, card.title())))
                            .orElse(null);
                    log.debug("No confident poster match for local title '{}' ({} catalog results; closest title: '{}').",
                            target.title(), choices.size(), closest == null ? "none" : closest.title());
                }
            } catch (MovieCatalogService.TmdbNotConfiguredException exception) {
                return;
            } catch (RestClientException | IllegalStateException exception) {
                // An offline/erroring catalog must not interfere with local playback. Retry on the next scheduled pass.
                log.warn("Optional TMDB matching paused after a catalog error; local media remains available.");
                return;
            }
        }
        log.info("TMDB artwork/details linked for {} titles across {} local videos.", linkedTitles, linkedFiles);
    }

    private boolean hasPoster(MovieCard card) {
        return card.posterPath() != null && !card.posterPath().isBlank();
    }

    /** Never auto-links on a substring hit. Ambiguous or year-mismatched results require review. */
    private MovieCard confidentMatch(String title, List<MovieCard> candidates) {
        if (candidates.isEmpty()) return null;
        String wanted = normalize(title);
        List<MovieCard> exact = candidates.stream().filter(card -> normalize(card.title()).equals(wanted)).toList();
        if (exact.size() == 1) return exact.get(0);
        if (exact.size() > 1) return null;
        List<MovieCard> ranked = candidates.stream().filter(card -> similarity(title, card.title()) >= 0.92)
                .sorted(java.util.Comparator.comparingDouble((MovieCard card) -> similarity(title, card.title())).reversed())
                .toList();
        if (ranked.isEmpty()) return null;
        double top = similarity(title, ranked.get(0).title());
        double runnerUp = ranked.size() > 1 ? similarity(title, ranked.get(1).title()) : 0;
        return top >= 0.94 && (ranked.size() == 1 || top - runnerUp >= 0.10) ? ranked.get(0) : null;
    }

    static double similarity(String left, String right) {
        String a = normalize(left);
        String b = normalize(right);
        if (a.isBlank() || b.isBlank()) return 0;
        int[] previous = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            int[] current = new int[b.length() + 1];
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1),
                        previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            }
            previous = current;
        }
        double characterSimilarity = 1.0 - (double) previous[b.length()] / Math.max(a.length(), b.length());
        String[] sourceWords = a.split(" ");
        if (sourceWords.length < 3) return characterSimilarity;
        java.util.Set<String> catalogWords = new java.util.HashSet<>(List.of(b.split(" ")));
        long coveredWords = java.util.Arrays.stream(sourceWords).distinct().filter(catalogWords::contains).count();
        double wordCoverage = (double) coveredWords / java.util.Arrays.stream(sourceWords).distinct().count();
        return Math.max(characterSimilarity, wordCoverage);
    }

    private static String normalize(String title) {
        return title == null ? "" : title.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ").trim().replaceAll("\\s+", " ");
    }

    private static String releaseYear(String value) {
        if (value == null) return null;
        java.util.regex.Matcher date = java.util.regex.Pattern.compile("^((?:19|20)\\d{2})-\\d{2}-\\d{2}$").matcher(value);
        if (date.matches()) return date.group(1);
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("^.*[\\s._-]+\\(?((?:19|20)\\d{2})\\)?\\s*$")
                .matcher(value);
        return matcher.matches() ? matcher.group(1) : null;
    }

    private static String withoutReleaseYear(String value) {
        return value == null ? "" : value
                .replaceAll("\\s+\\(?\\b(?:19|20)\\d{2}\\b\\)?\\s*$", "")
                .trim();
    }

    private record MatchTarget(String title, String mediaType, List<Long> ids) {
        private MatchTarget(String title, String mediaType) { this(title, mediaType, new ArrayList<>()); }
    }
}
