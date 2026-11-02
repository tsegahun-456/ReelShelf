package com.segi.watchlist.library;

import com.segi.watchlist.catalog.MovieCard;
import com.segi.watchlist.catalog.MovieCatalogService;
import com.segi.watchlist.catalog.MovieDetails;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Read-first review tools; only explicit form submissions change matches or series grouping. */
@Controller
public class LibraryReviewController {
    private final MediaScanner scanner;
    private final LocalMediaRepository mediaRepository;
    private final MovieCatalogService catalog;
    private final MediaScanReviewService scans;
    private final LocalSeriesGroupAliasService aliases;

    public LibraryReviewController(MediaScanner scanner, LocalMediaRepository mediaRepository,
                                   MovieCatalogService catalog, MediaScanReviewService scans,
                                   LocalSeriesGroupAliasService aliases) {
        this.scanner = scanner;
        this.mediaRepository = mediaRepository;
        this.catalog = catalog;
        this.scans = scans;
        this.aliases = aliases;
    }

    @GetMapping("/library/review")
    public String review(Model model, Authentication authentication) {
        header(model, authentication);
        List<LocalMedia> all = scanner.list("", "ALL");
        model.addAttribute("scan", scans.snapshot());
        model.addAttribute("duplicates", duplicateFiles(all));
        model.addAttribute("seriesGroups", seriesGroups(all));
        model.addAttribute("posterTargets", posterTargets(all));
        model.addAttribute("aliases", aliases.all());
        model.addAttribute("tmdbConfigured", catalog.isConfigured());
        return "library/review";
    }

    @GetMapping("/library/review/status")
    @ResponseBody
    public ResponseEntity<MediaScanReviewService.ScanSnapshot> status() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(scans.snapshot());
    }

    @GetMapping("/library/review/posters")
    public String posterSearch(@RequestParam long mediaId, @RequestParam(defaultValue = "") String q,
                               @RequestParam(defaultValue = "") String year,
                               Model model, Authentication authentication) {
        header(model, authentication);
        LocalMedia media = scanner.require(mediaId);
        boolean episode = "EPISODE".equals(media.getMediaKind());
        String type = episode ? "TV" : "MOVIE";
        String title = episode ? aliases.canonicalTitle(media.getSeriesTitle()) : media.getDisplayTitle();
        List<LocalMedia> related = episode ? scanner.list("", "EPISODE").stream()
                .filter(row -> seriesKey(aliases.canonicalTitle(row.getSeriesTitle())).equals(seriesKey(title))).toList()
                : List.of(media);
        String query = q == null || q.isBlank() ? withoutYear(title) : q.trim();
        Integer selectedYear = year.isBlank() ? yearFrom(title) : parseYear(year);
        List<MovieCard> results = List.of();
        String searchError = null;
        if (!query.isBlank() && catalog.isConfigured()) {
            try { results = catalog.search(query, type, null, selectedYear, null); }
            catch (MovieCatalogService.TmdbNotConfiguredException | RestClientException | IllegalStateException exception) {
                searchError = "TMDB could not search right now. Your local files and existing match are unchanged.";
            }
        } else if (!catalog.isConfigured()) {
            searchError = "Add a TMDB API key in Settings to search for poster references.";
        }
        model.addAttribute("media", media);
        model.addAttribute("title", title);
        model.addAttribute("mediaType", type);
        model.addAttribute("relatedCount", related.size());
        model.addAttribute("results", results);
        model.addAttribute("query", query);
        model.addAttribute("selectedYear", selectedYear == null ? "" : selectedYear);
        model.addAttribute("searchError", searchError);
        return "library/poster-review";
    }

    @PostMapping("/library/review/posters/apply")
    public String applyPoster(@RequestParam long mediaId, @RequestParam long tmdbId,
                              @RequestParam String mediaType, RedirectAttributes redirect) {
        LocalMedia media = scanner.require(mediaId);
        boolean episode = "EPISODE".equals(media.getMediaKind());
        String expectedType = episode ? "TV" : "MOVIE";
        if (!expectedType.equalsIgnoreCase(mediaType)) {
            redirect.addFlashAttribute("libraryWarning", "Choose a result for the same media type. No match was changed.");
            return "redirect:/library/review/posters?mediaId=" + mediaId;
        }
        try {
            MovieDetails details = catalog.details(tmdbId, expectedType);
            MovieCard selected = details.movie();
            List<Long> ids = episode ? scanner.list("", "EPISODE").stream()
                    .filter(row -> seriesKey(aliases.canonicalTitle(row.getSeriesTitle()))
                            .equals(seriesKey(aliases.canonicalTitle(media.getSeriesTitle()))))
                    .map(LocalMedia::getId).toList() : List.of(mediaId);
            int updated = mediaRepository.updateTmdbMatchForIds(ids, selected.id(), selected.mediaType(), selected.title(),
                    selected.overview(), selected.posterPath(), selected.releaseDate());
            if (updated == 0) redirect.addFlashAttribute("libraryWarning", "No local entries were updated. Try the match again.");
            else redirect.addFlashAttribute("notice", "Saved the selected TMDB reference for " + updated + " local "
                    + (episode ? "episodes." : "movie."));
        } catch (MovieCatalogService.TmdbNotConfiguredException | RestClientException | IllegalStateException exception) {
            redirect.addFlashAttribute("libraryWarning", "TMDB could not load that title. Your existing match is unchanged.");
        }
        return episode
                ? "redirect:/library/series?title=" + org.springframework.web.util.UriUtils.encode(
                        aliases.canonicalTitle(media.getSeriesTitle()), java.nio.charset.StandardCharsets.UTF_8)
                : "redirect:/library/review";
    }

    @PostMapping("/library/review/series/merge")
    public String mergeSeries(@RequestParam String aliasTitle, @RequestParam String canonicalTitle,
                              RedirectAttributes redirect) {
        List<String> names = scanner.list("", "EPISODE").stream().map(LocalMedia::getSeriesTitle)
                .filter(Objects::nonNull).distinct().toList();
        boolean aliasExists = names.stream().anyMatch(name -> seriesKey(name).equals(seriesKey(aliasTitle)));
        boolean canonicalExists = names.stream().anyMatch(name -> seriesKey(name).equals(seriesKey(canonicalTitle)));
        if (!aliasExists || !canonicalExists) {
            redirect.addFlashAttribute("error", "Choose both names from the currently scanned series list.");
            return "redirect:/library/review";
        }
        try {
            aliases.merge(aliasTitle, canonicalTitle);
            redirect.addFlashAttribute("notice", "Series grouping updated. The original episode filenames and paths were not changed.");
        } catch (IllegalArgumentException exception) {
            redirect.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/library/review";
    }

    @PostMapping("/library/review/series/unmerge")
    public String unmergeSeries(@RequestParam String aliasTitle, RedirectAttributes redirect) {
        aliases.unmerge(aliasTitle);
        redirect.addFlashAttribute("notice", "Series grouping link removed.");
        return "redirect:/library/review";
    }

    private List<PosterTarget> posterTargets(List<LocalMedia> all) {
        Map<String, List<LocalMedia>> grouped = new LinkedHashMap<>();
        all.stream().sorted(Comparator.comparing(LocalMedia::getDisplayTitle, String.CASE_INSENSITIVE_ORDER))
                .forEach(media -> {
                    boolean episode = "EPISODE".equals(media.getMediaKind());
                    String title = episode ? aliases.canonicalTitle(media.getSeriesTitle()) : media.getDisplayTitle();
                    String type = episode ? "TV" : "MOVIE";
                    grouped.computeIfAbsent(type + ":" + seriesKey(title), ignored -> new ArrayList<>()).add(media);
                });
        return grouped.values().stream().map(rows -> {
                    LocalMedia first = rows.get(0);
                    String title = "EPISODE".equals(first.getMediaKind())
                            ? aliases.canonicalTitle(first.getSeriesTitle()) : first.getDisplayTitle();
                    boolean matched = rows.stream().anyMatch(row -> row.getTmdbId() != null);
                    boolean reviewed = rows.stream().allMatch(LocalMedia::isTmdbMatchReviewed);
                    return new PosterTarget(first.getId(), title, "EPISODE".equals(first.getMediaKind()) ? "TV" : "MOVIE",
                            rows.size(), matched, reviewed, rows.stream().filter(row -> row.getTmdbId() != null)
                            .map(LocalMedia::getTmdbTitle).filter(Objects::nonNull).findFirst().orElse(""));
                }).filter(target -> !target.reviewed())
                .sorted(Comparator.comparing(PosterTarget::matched).thenComparing(PosterTarget::title, String.CASE_INSENSITIVE_ORDER))
                .limit(100).toList();
    }

    private List<DuplicateFileGroup> duplicateFiles(List<LocalMedia> all) {
        return all.stream().collect(Collectors.groupingBy(row -> row.getFileName().toLowerCase(Locale.ROOT) + ":" + row.getFileSize()))
                .values().stream().filter(rows -> rows.size() > 1)
                .map(rows -> new DuplicateFileGroup(rows.get(0).getFileName(), rows.get(0).getFileSize(), rows.stream()
                        .map(LocalMedia::getFilePath).sorted(String.CASE_INSENSITIVE_ORDER).toList()))
                .sorted(Comparator.comparing(DuplicateFileGroup::fileName, String.CASE_INSENSITIVE_ORDER))
                .limit(100).toList();
    }

    private List<SeriesGroupView> seriesGroups(List<LocalMedia> all) {
        Map<String, List<LocalMedia>> groups = all.stream().filter(row -> "EPISODE".equals(row.getMediaKind()))
                .collect(Collectors.groupingBy(row -> seriesKey(row.getSeriesTitle()), LinkedHashMap::new, Collectors.toList()));
        List<String> titles = groups.values().stream().map(rows -> rows.get(0).getSeriesTitle()).filter(Objects::nonNull)
                .distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
        List<SeriesGroupView> views = groups.values().stream().map(rows -> {
            String title = rows.get(0).getSeriesTitle();
            List<String> suggestions = titles.stream().filter(other -> !seriesKey(other).equals(seriesKey(title)))
                    .filter(other -> !seriesKey(aliases.canonicalTitle(other)).equals(seriesKey(aliases.canonicalTitle(title))))
                    .filter(other -> likelyTitleVariant(title, other)).toList();
            return new SeriesGroupView(title, rows.size(), suggestions);
        }).toList();
        return views.stream().sorted(Comparator.comparing(SeriesGroupView::title, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    private boolean likelyTitleVariant(String left, String right) {
        String a = seriesKey(withoutYear(left));
        String b = seriesKey(withoutYear(right));
        if (a.isBlank() || b.isBlank() || a.length() < 7 || b.length() < 7) return false;
        if (a.equals(b)) return true;
        String[] leftWords = a.split(" ");
        String[] rightWords = b.split(" ");
        long shared = java.util.Arrays.stream(leftWords).distinct().filter(word -> List.of(rightWords).contains(word)).count();
        double coverage = (double) shared / Math.max(java.util.Arrays.stream(leftWords).distinct().count(),
                java.util.Arrays.stream(rightWords).distinct().count());
        return coverage >= .75 && textSimilarity(a, b) >= .76;
    }

    private double textSimilarity(String a, String b) {
        int[] previous = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            int[] current = new int[b.length() + 1]; current[0] = i;
            for (int j = 1; j <= b.length(); j++) current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1),
                    previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            previous = current;
        }
        return 1 - (double) previous[b.length()] / Math.max(a.length(), b.length());
    }

    private void header(Model model, Authentication authentication) {
        model.addAttribute("activePage", "library");
        model.addAttribute("signedIn", authentication != null);
        model.addAttribute("currentEmail", authentication == null ? "" : authentication.getName());
    }

    private String seriesKey(String value) {
        return LocalSeriesGroupAliasService.aliasKey(value);
    }

    private String withoutYear(String title) { return title.replaceAll("\\s+\\(?\\b(?:19|20)\\d{2}\\b\\)?\\s*$", "").trim(); }
    private Integer yearFrom(String title) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("^.*[\\s._-]+\\(?((?:19|20)\\d{2})\\)?\\s*$").matcher(title);
        return matcher.matches() ? Integer.valueOf(matcher.group(1)) : null;
    }
    private Integer parseYear(String value) {
        try {
            int year = Integer.parseInt(value);
            return year >= 1888 && year <= java.time.Year.now().getValue() + 10 ? year : null;
        } catch (NumberFormatException exception) { return null; }
    }

    public record PosterTarget(long mediaId, String title, String mediaType, int fileCount,
                               boolean matched, boolean reviewed, String currentMatch) { }
    public record DuplicateFileGroup(String fileName, long fileSize, List<String> paths) { }
    public record SeriesGroupView(String title, int episodeCount, List<String> variants) { }
}
