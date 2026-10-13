package com.segi.watchlist.library;

import com.segi.watchlist.catalog.MovieCard;
import com.segi.watchlist.catalog.MovieCatalogService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.TreeMap;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.core.io.support.ResourceRegion;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClientException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class LibraryController {
    private final MediaScanner scanner;
    private final MovieCatalogService catalog;
    private final LocalMediaRepository repository;
    private final LocalPosterCacheService posterCache;
    private final EmbeddedSubtitleService embeddedSubtitles;
    public LibraryController(MediaScanner scanner, MovieCatalogService catalog, LocalMediaRepository repository,
                             LocalPosterCacheService posterCache, EmbeddedSubtitleService embeddedSubtitles) {
        this.scanner = scanner;
        this.catalog = catalog;
        this.repository = repository;
        this.posterCache = posterCache;
        this.embeddedSubtitles = embeddedSubtitles;
    }

    @GetMapping("/library")
    public String library(@RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "ALL") String kind, Model model, Authentication authentication) {
        String selectedKind = "MOVIE".equals(kind) || "SERIES".equals(kind) ? kind : "ALL";
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        List<LocalMedia> all = scanner.list("", "ALL");
        List<LocalMedia> movies = all.stream().filter(item -> "MOVIE".equals(item.getMediaKind()))
                .filter(item -> matches(item, needle)).toList();
        List<LocalMedia> episodes = all.stream().filter(item -> "EPISODE".equals(item.getMediaKind())).toList();
        List<LocalMediaSeries> series = groupSeries(episodes).stream()
                .filter(show -> needle.isBlank() || (show.title() + " " + show.catalogTitle()).toLowerCase(Locale.ROOT).contains(needle))
                .toList();
        model.addAttribute("activePage", "library");
        model.addAttribute("signedIn", authentication != null);
        model.addAttribute("currentEmail", authentication == null ? "" : authentication.getName());
        model.addAttribute("query", q);
        model.addAttribute("kind", selectedKind);
        model.addAttribute("movies", movies);
        model.addAttribute("series", series);
        model.addAttribute("movieCount", scanner.list("", "MOVIE").size());
        model.addAttribute("episodeCount", scanner.list("", "EPISODE").size());
        model.addAttribute("libraryCount", scanner.list("", "ALL").size());
        model.addAttribute("seriesCount", groupSeries(episodes).size());
        return "library/index";
    }

    @GetMapping("/library/series")
    public String series(@RequestParam String title, Model model, Authentication authentication) {
        String key = seriesKey(title);
        List<LocalMedia> episodes = scanner.list("", "EPISODE").stream()
                .filter(item -> seriesKey(item.getSeriesTitle()).equals(key))
                .sorted(Comparator.comparing(LocalMedia::getSeasonNumber, Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(LocalMedia::getEpisodeNumber, Comparator.nullsLast(Integer::compareTo)))
                .toList();
        if (episodes.isEmpty()) throw new SeriesNotFoundException();
        LocalMedia reference = episodes.stream().filter(item -> item.getTmdbId() != null).findFirst().orElse(episodes.get(0));
        Map<Integer, List<LocalMedia>> bySeason = new TreeMap<>();
        for (LocalMedia episode : episodes) bySeason.computeIfAbsent(episode.getSeasonNumber() == null ? 0 : episode.getSeasonNumber(), ignored -> new ArrayList<>()).add(episode);
        List<LocalMediaSeason> seasons = bySeason.entrySet().stream()
                .map(entry -> new LocalMediaSeason(entry.getKey(), entry.getValue())).toList();
        String displayTitle = reference.getTmdbTitle() == null || reference.getTmdbTitle().isBlank()
                ? reference.getSeriesTitle() : reference.getTmdbTitle();
        model.addAttribute("activePage", "library");
        model.addAttribute("signedIn", authentication != null);
        model.addAttribute("currentEmail", authentication == null ? "" : authentication.getName());
        model.addAttribute("show", reference);
        model.addAttribute("showTitle", displayTitle);
        model.addAttribute("seasons", seasons);
        model.addAttribute("episodeCount", episodes.size());
        model.addAttribute("seasonCount", seasons.size());
        model.addAttribute("seasonLabel", seasons.size() == 1 ? "1 season" : seasons.size() + " seasons");
        model.addAttribute("episodes", episodes);
            return "library/series";
    }

    @GetMapping("/library/{id}/play")
    public String play(@PathVariable long id, Model model, Authentication authentication) {
        LocalMedia item = scanner.require(id);
        model.addAttribute("activePage", "library");
        model.addAttribute("signedIn", authentication != null);
        model.addAttribute("currentEmail", authentication == null ? "" : authentication.getName());
        model.addAttribute("item", item);
        String backUrl = "EPISODE".equals(item.getMediaKind()) && item.getSeriesTitle() != null
                ? "/library/series?title=" + org.springframework.web.util.UriUtils.encode(item.getSeriesTitle(), StandardCharsets.UTF_8)
                : "/library?kind=MOVIE";
        model.addAttribute("backUrl", backUrl);
        if ("EPISODE".equals(item.getMediaKind()) && item.getSeriesTitle() != null) {
            List<LocalMedia> episodes = scanner.list("", "EPISODE").stream()
                    .filter(candidate -> seriesKey(candidate.getSeriesTitle()).equals(seriesKey(item.getSeriesTitle())))
                    .sorted(Comparator.comparing(LocalMedia::getSeasonNumber, Comparator.nullsLast(Integer::compareTo))
                            .thenComparing(LocalMedia::getEpisodeNumber, Comparator.nullsLast(Integer::compareTo)))
                    .toList();
            for (int index = 0; index + 1 < episodes.size(); index++) {
                if (episodes.get(index).getId().equals(item.getId())) {
                    LocalMedia next = episodes.get(index + 1);
                    model.addAttribute("nextItem", next);
                    model.addAttribute("nextPlayUrl", "/library/" + next.getId() + "/play");
                    break;
                }
            }
        }
        return "library/player";
    }

    @PostMapping("/library/{id}/rename")
    public String rename(@PathVariable long id, @RequestParam String title, RedirectAttributes redirect) {
        scanner.require(id).rename(title);
        redirect.addFlashAttribute("notice", "Title updated.");
        return "redirect:/library";
    }

    @PostMapping("/library/{id}/tmdb-match")
    public String matchTmdb(@PathVariable long id, RedirectAttributes redirect) {
        LocalMedia item = scanner.require(id);
        String mediaType = "EPISODE".equals(item.getMediaKind()) ? "TV" : "MOVIE";
        String query = "EPISODE".equals(item.getMediaKind()) ? item.getSeriesTitle() : item.getDisplayTitle();
        String searchTitle = withoutReleaseYear(query);
        Integer year = releaseYear(query);
        try {
            List<MovieCard> choices = catalog.search(searchTitle, mediaType, null, year, null);
            if (choices.isEmpty() && year != null) choices = catalog.search(searchTitle, mediaType, null, null, null);
            MovieCard best = choices.stream().max(java.util.Comparator.comparingDouble(card -> matchScore(searchTitle, card.title()))).orElse(null);
            if (best == null || matchScore(searchTitle, best.title()) < 0.55) {
                redirect.addFlashAttribute("libraryWarning", "No close TMDB title match found. Your local video is still available; correct its title and try again.");
            } else {
                item.attachTmdb(best);
                repository.save(item);
                redirect.addFlashAttribute("notice", "TMDB poster and details linked as a reference. Local playback remains independent.");
            }
        } catch (MovieCatalogService.TmdbNotConfiguredException | RestClientException | IllegalStateException exception) {
            redirect.addFlashAttribute("libraryWarning", "TMDB is unavailable right now. Your local library and playback are unaffected.");
        }
        return "redirect:/library";
    }

    @PostMapping("/library/series/tmdb-match")
    public String matchSeriesTmdb(@RequestParam String title, RedirectAttributes redirect) {
        List<LocalMedia> episodes = scanner.list("", "EPISODE").stream()
                .filter(item -> seriesKey(item.getSeriesTitle()).equals(seriesKey(title))).toList();
        if (episodes.isEmpty()) return "redirect:/library?kind=SERIES";
        try {
            String searchTitle = withoutReleaseYear(title);
            Integer year = releaseYear(title);
            List<MovieCard> choices = catalog.search(searchTitle, "TV", null, year, null);
            if (choices.isEmpty() && year != null) choices = catalog.search(searchTitle, "TV", null, null, null);
            MovieCard best = choices.stream().max(Comparator.comparingDouble(card -> matchScore(searchTitle, card.title()))).orElse(null);
            if (best == null || matchScore(searchTitle, best.title()) < 0.55) {
                redirect.addFlashAttribute("libraryWarning", "No close TMDB series match found. Your local episodes remain available.");
            } else {
                episodes.forEach(item -> item.attachTmdb(best));
                repository.saveAll(episodes);
                redirect.addFlashAttribute("notice", "Series poster and details linked as a reference.");
            }
        } catch (MovieCatalogService.TmdbNotConfiguredException | RestClientException | IllegalStateException exception) {
            redirect.addFlashAttribute("libraryWarning", "TMDB is unavailable right now. Your local series and playback are unaffected.");
        }
        return "redirect:/library/series?title=" + org.springframework.web.util.UriUtils.encode(title, StandardCharsets.UTF_8);
    }

    private List<LocalMediaSeries> groupSeries(List<LocalMedia> episodes) {
        Map<String, List<LocalMedia>> grouped = new LinkedHashMap<>();
        episodes.stream().sorted(Comparator.comparing(item -> Objects.toString(item.getSeriesTitle(), ""), String.CASE_INSENSITIVE_ORDER))
                .forEach(item -> grouped.computeIfAbsent(seriesKey(item.getSeriesTitle()), ignored -> new ArrayList<>()).add(item));
        return grouped.values().stream().map(items -> {
            LocalMedia reference = items.stream().filter(item -> item.getTmdbId() != null).findFirst().orElse(items.get(0));
            String title = reference.getSeriesTitle() == null ? reference.getDisplayTitle() : reference.getSeriesTitle();
            String catalogTitle = Objects.toString(reference.getTmdbTitle(), "");
            int seasonCount = (int) items.stream().map(item -> item.getSeasonNumber() == null ? 0 : item.getSeasonNumber()).distinct().count();
            return new LocalMediaSeries(title, title, catalogTitle, reference.getId(), reference.getTmdbPosterPath(),
                    reference.getTmdbOverview(), reference.getTmdbReleaseDate(), items.size(), seasonCount, reference.getTmdbId());
        }).sorted(Comparator.comparing(LocalMediaSeries::title, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    private boolean matches(LocalMedia item, String needle) {
        return needle.isBlank() || (item.getDisplayTitle() + " " + item.getFileName() + " " + Objects.toString(item.getTmdbTitle(), ""))
                .toLowerCase(Locale.ROOT).contains(needle);
    }

    private String seriesKey(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim().replaceAll("\\s+", " ");
    }

    @GetMapping("/library/posters/media/{id}")
    @ResponseBody
    public ResponseEntity<byte[]> poster(@PathVariable long id) {
        LocalMedia item = scanner.require(id);
        try {
            LocalPosterCacheService.PosterImage image = posterCache.getOrFetch(item);
            return ResponseEntity.ok().contentType(MediaType.parseMediaType(image.contentType()))
                    .cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(1)).cachePrivate())
                    .body(image.bytes());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new PosterUnavailableException();
        } catch (IOException | LocalPosterCacheService.PosterNotAvailableException exception) {
            throw new PosterUnavailableException();
        }
    }

    private double matchScore(String left, String right) {
        String a = left == null ? "" : left.toLowerCase().replaceAll("[^a-z0-9]", "");
        String b = right == null ? "" : right.toLowerCase().replaceAll("[^a-z0-9]", "");
        if (a.isBlank() || b.isBlank()) return 0;
        if (a.equals(b) || a.contains(b) || b.contains(a)) return 1;
        int[] previous = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            int[] current = new int[b.length() + 1]; current[0] = i;
            for (int j = 1; j <= b.length(); j++) current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            previous = current;
        }
        return 1.0 - (double) previous[b.length()] / Math.max(a.length(), b.length());
    }

    private String withoutReleaseYear(String value) {
        return value == null ? "" : value.replaceAll("\\s*\\(?\\b(?:19|20)\\d{2}\\b\\)?\\s*$", "").trim();
    }

    private Integer releaseYear(String value) {
        if (value == null) return null;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\b((?:19|20)\\d{2})\\b").matcher(value);
        return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
    }

    @GetMapping("/library/{id}/video")
    @ResponseBody
    public ResponseEntity<ResourceRegion> video(@PathVariable long id, @RequestHeader HttpHeaders requestHeaders) throws IOException {
        LocalMedia item = scanner.require(id);
        Path path = checkedPath(item.getFilePath());
        Resource resource = new FileSystemResource(path);
        MediaType type = MediaTypeFactory.getMediaType(path.getFileName().toString()).orElse(MediaType.APPLICATION_OCTET_STREAM);
        long size = Files.size(path);
        List<HttpRange> ranges = requestHeaders.getRange();
        if (ranges == null || ranges.isEmpty()) {
            return ResponseEntity.ok().contentType(type).contentLength(size).header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .body(new ResourceRegion(resource, 0, size));
        }
        HttpRange range = ranges.get(0);
        long start = range.getRangeStart(size);
        long end = range.getRangeEnd(size);
        long length = end - start + 1;
        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT).contentType(type).contentLength(length)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + size)
                .body(new ResourceRegion(resource, start, length));
    }

    @GetMapping(value = "/library/{id}/subtitle", produces = "text/vtt;charset=UTF-8")
    @ResponseBody
    public ResponseEntity<String> subtitle(@PathVariable long id) throws IOException {
        LocalMedia item = scanner.require(id);
        if (item.getSubtitlePath() == null) throw new NoSubtitleException();
        Path path = checkedPath(item.getSubtitlePath());
        String content = Files.readString(path, StandardCharsets.UTF_8);
        if (MediaScanner.extension(path).equals("srt")) content = "WEBVTT\n\n" + content.replace("\uFEFF", "").replaceAll("(?m)^(\\d{2}:\\d{2}:\\d{2}),(\\d{3})", "$1.$2");
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/vtt;charset=UTF-8")).body(content);
    }

    @GetMapping("/library/{id}/subtitle/tracks")
    @ResponseBody
    public ResponseEntity<SubtitleTracksResponse> subtitleTracks(@PathVariable long id) {
        LocalMedia item = scanner.require(id);
        Path media = checkedPath(item.getFilePath());
        ExternalSubtitle external = item.getSubtitlePath() == null ? null
                : new ExternalSubtitle("External · " + Path.of(item.getSubtitlePath()).getFileName(), "/library/" + id + "/subtitle");
        try {
            List<EmbeddedSubtitleService.SubtitleTrack> tracks = embeddedSubtitles.tracks(media);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(new SubtitleTracksResponse(true, null, external, tracks));
        } catch (EmbeddedSubtitleService.SubtitleToolsUnavailableException exception) {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(new SubtitleTracksResponse(false, exception.getMessage(), external, List.of()));
        } catch (EmbeddedSubtitleService.SubtitleProcessingException exception) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).cacheControl(CacheControl.noStore())
                    .body(new SubtitleTracksResponse(true, exception.getMessage(), external, List.of()));
        }
    }

    @GetMapping(value = "/library/{id}/subtitle/embedded/{streamIndex}", produces = "text/vtt;charset=UTF-8")
    @ResponseBody
    public ResponseEntity<byte[]> embeddedSubtitle(@PathVariable long id, @PathVariable int streamIndex) {
        LocalMedia item = scanner.require(id);
        Path media = checkedPath(item.getFilePath());
        try {
            byte[] content = embeddedSubtitles.extractWebVtt(media, streamIndex);
            return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/vtt;charset=UTF-8"))
                    .cacheControl(CacheControl.noStore()).body(content);
        } catch (EmbeddedSubtitleService.UnsupportedSubtitleException exception) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).contentType(MediaType.TEXT_PLAIN)
                    .body(exception.getMessage().getBytes(StandardCharsets.UTF_8));
        } catch (EmbeddedSubtitleService.SubtitleToolsUnavailableException exception) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.TEXT_PLAIN)
                    .body(exception.getMessage().getBytes(StandardCharsets.UTF_8));
        } catch (EmbeddedSubtitleService.SubtitleProcessingException exception) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).contentType(MediaType.TEXT_PLAIN)
                    .body(exception.getMessage().getBytes(StandardCharsets.UTF_8));
        }
    }

    private Path checkedPath(String raw) {
        Path path = Path.of(raw).toAbsolutePath().normalize();
        if (!scanner.isAllowed(path) || !Files.isRegularFile(path) || !Files.isReadable(path)) throw new MediaUnavailableException();
        return path;
    }
    @ResponseStatus(HttpStatus.NOT_FOUND) private static class NoSubtitleException extends RuntimeException {}
    @ResponseStatus(HttpStatus.NOT_FOUND) private static class MediaUnavailableException extends RuntimeException {}
    @ResponseStatus(HttpStatus.NOT_FOUND) private static class SeriesNotFoundException extends RuntimeException {}
    @ResponseStatus(HttpStatus.NOT_FOUND) private static class PosterUnavailableException extends RuntimeException {}

    public record LocalMediaSeries(String key, String title, String catalogTitle, Long posterMediaId, String posterPath, String overview,
                                   String releaseDate, int episodeCount, int seasonCount, Long tmdbId) {}
    public record LocalMediaSeason(int seasonNumber, List<LocalMedia> episodes) {}
    public record ExternalSubtitle(String label, String url) {}
    public record SubtitleTracksResponse(boolean toolsAvailable, String message, ExternalSubtitle external,
                                         List<EmbeddedSubtitleService.SubtitleTrack> embedded) {}
}
