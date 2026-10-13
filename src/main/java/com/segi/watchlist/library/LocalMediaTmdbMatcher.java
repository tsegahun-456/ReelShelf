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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;

/** Adds optional TMDB artwork to local files without making local browsing depend on TMDB. */
@Service
public class LocalMediaTmdbMatcher {
    private static final Logger log = LoggerFactory.getLogger(LocalMediaTmdbMatcher.class);
    private final LocalMediaRepository repository;
    private final MovieCatalogService catalog;

    public LocalMediaTmdbMatcher(LocalMediaRepository repository, MovieCatalogService catalog) {
        this.repository = repository;
        this.catalog = catalog;
    }

    @Scheduled(initialDelayString = "${library.tmdb-match-initial-delay-ms:30000}",
            fixedDelayString = "${library.tmdb-match-interval-ms:900000}")
    @Transactional
    public void matchUnlinkedMedia() {
        if (!catalog.isConfigured()) return;
        List<LocalMedia> localRows = repository.findAllByOrderByDisplayTitleAsc();
        Map<String, MatchTarget> targets = new LinkedHashMap<>();
        for (LocalMedia row : localRows) {
            if (row.getTmdbId() != null) continue;
            boolean episode = "EPISODE".equals(row.getMediaKind());
            String title = episode ? row.getSeriesTitle() : row.getDisplayTitle();
            if (title == null || title.isBlank()) continue;
            String type = episode ? "TV" : "MOVIE";
            String key = type + ":" + normalize(title);
            MatchTarget target = targets.computeIfAbsent(key, ignored -> new MatchTarget(title, type));
            target.rows().add(row);
        }

        log.info("Checking {} unmatched local titles for optional TMDB artwork.", targets.size());
        int linkedTitles = 0;
        int linkedFiles = 0;
        for (MatchTarget target : targets.values()) {
            try {
                String searchTitle = withoutReleaseYear(target.title());
                String year = releaseYear(target.title());
                Integer yearFilter = year == null ? null : Integer.valueOf(year);
                List<MovieCard> choices = catalog.search(searchTitle, target.mediaType(), null, yearFilter, null);
                if (choices.isEmpty() && yearFilter != null) {
                    choices = catalog.search(searchTitle, target.mediaType(), null, null, null);
                }
                List<MovieCard> closeMatches = choices.stream()
                        .filter(card -> !card.posterPath().isBlank())
                        .filter(card -> similarity(searchTitle, card.title()) >= 0.78)
                        .toList();
                String wantedYear = year;
                List<MovieCard> sameYear = wantedYear == null ? List.of() : closeMatches.stream()
                        .filter(card -> wantedYear.equals(releaseYear(card.releaseDate())))
                        .toList();

                // Prefer a release-year match for generic or repeated titles (for example, "The Runner").
                // If TMDB has several equally close titles but none from the local file's year, leave it
                // unlinked instead of attaching an unrelated poster.
                List<MovieCard> eligible = !sameYear.isEmpty() ? sameYear
                        : wantedYear != null && closeMatches.size() > 1 ? List.of() : closeMatches;
                MovieCard best = eligible.stream()
                        .max(java.util.Comparator.comparingDouble(card -> similarity(searchTitle, card.title())))
                        .orElse(null);
                if (best != null) {
                    target.rows().forEach(row -> row.attachTmdb(best));
                    repository.saveAll(target.rows());
                    linkedTitles++;
                    linkedFiles += target.rows().size();
                } else {
                    MovieCard closest = choices.stream()
                            .max(java.util.Comparator.comparingDouble(card -> similarity(searchTitle, card.title())))
                            .orElse(null);
                    log.info("No confident poster match for local title '{}' ({} catalog results; closest title: '{}').",
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
                .replaceAll("\\b(19\\d{2}|20\\d{2})\\b", "")
                .replaceAll("[^a-z0-9]+", " ").trim().replaceAll("\\s+", " ");
    }

    private static String releaseYear(String value) {
        if (value == null) return null;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\b((?:19|20)\\d{2})\\b")
                .matcher(value);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String withoutReleaseYear(String value) {
        return value == null ? "" : value
                .replaceAll("\\s*\\(?\\b(?:19|20)\\d{2}\\b\\)?\\s*$", "")
                .trim();
    }

    private record MatchTarget(String title, String mediaType, List<LocalMedia> rows) {
        private MatchTarget(String title, String mediaType) { this(title, mediaType, new ArrayList<>()); }
    }
}
