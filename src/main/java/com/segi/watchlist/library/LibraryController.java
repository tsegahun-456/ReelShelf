package com.segi.watchlist.library;

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
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class LibraryController {
    private final MediaScanner scanner;
    private final LocalPosterCacheService posterCache;
    private final EmbeddedSubtitleService embeddedSubtitles;
    private final StudyNoteRepository studyNotes;
    private final EmbeddedAudioService embeddedAudio;
    private final LocalSeriesGroupAliasService seriesAliases;
    public LibraryController(MediaScanner scanner,
                             LocalPosterCacheService posterCache, EmbeddedSubtitleService embeddedSubtitles,
                             StudyNoteRepository studyNotes, EmbeddedAudioService embeddedAudio,
                             LocalSeriesGroupAliasService seriesAliases) {
        this.scanner = scanner;
        this.posterCache = posterCache;
        this.embeddedSubtitles = embeddedSubtitles;
        this.studyNotes = studyNotes;
        this.embeddedAudio = embeddedAudio;
        this.seriesAliases = seriesAliases;
    }

    static final int LIBRARY_PAGE_SIZE = 60;

    @GetMapping("/library")
    public String library(@RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "ALL") String kind,
                          @RequestParam(defaultValue = "1") int page, Model model, Authentication authentication) {
        String selectedKind = "MOVIE".equals(kind) || "SERIES".equals(kind) ? kind : "ALL";
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        List<LocalMedia> all = scanner.list("", "ALL");
        List<LocalMedia> movies = all.stream().filter(item -> "MOVIE".equals(item.getMediaKind()))
                .filter(item -> matches(item, needle)).toList();
        List<LocalMedia> episodes = all.stream().filter(item -> "EPISODE".equals(item.getMediaKind())).toList();
        List<LocalMediaSeries> series = groupSeries(episodes).stream()
                .filter(show -> needle.isBlank() || (show.title() + " " + show.catalogTitle()).toLowerCase(Locale.ROOT).contains(needle))
                .toList();
        int totalPages = Math.max(1, (movies.size() + LIBRARY_PAGE_SIZE - 1) / LIBRARY_PAGE_SIZE);
        int currentPage = Math.min(Math.max(page, 1), totalPages);
        int from = (currentPage - 1) * LIBRARY_PAGE_SIZE;
        List<LocalMedia> pageMovies = movies.subList(from, Math.min(from + LIBRARY_PAGE_SIZE, movies.size()));
        model.addAttribute("activePage", "library");
        model.addAttribute("signedIn", authentication != null);
        model.addAttribute("currentEmail", authentication == null ? "" : authentication.getName());
        model.addAttribute("query", q);
        model.addAttribute("kind", selectedKind);
        model.addAttribute("movies", movies);
        model.addAttribute("pageMovies", pageMovies);
        model.addAttribute("page", currentPage);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("series", series);
        model.addAttribute("movieCount", all.stream().filter(item -> "MOVIE".equals(item.getMediaKind())).count());
        model.addAttribute("episodeCount", episodes.size());
        model.addAttribute("libraryCount", all.size());
        model.addAttribute("seriesCount", groupSeries(episodes).size());
        return "library/index";
    }

    @GetMapping("/library/series")
    public String series(@RequestParam String title, Model model, Authentication authentication) {
        String canonicalTitle = seriesAliases.canonicalTitle(title);
        String key = seriesKey(canonicalTitle);
        List<LocalMedia> episodes = scanner.list("", "EPISODE").stream()
                .filter(item -> seriesKey(seriesAliases.canonicalTitle(item.getSeriesTitle())).equals(key))
                .sorted(Comparator.comparing(LocalMedia::getSeasonNumber, Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(LocalMedia::getEpisodeNumber, Comparator.nullsLast(Integer::compareTo)))
                .toList();
        if (episodes.isEmpty()) throw new SeriesNotFoundException();
        LocalMedia reference = episodes.stream().filter(item -> item.getTmdbId() != null && hasPosterPath(item.getTmdbPosterPath()))
                .findFirst().orElseGet(() -> episodes.stream().filter(item -> item.getTmdbId() != null)
                        .findFirst().orElse(episodes.get(0)));
        Map<Integer, List<LocalMedia>> bySeason = new TreeMap<>();
        for (LocalMedia episode : episodes) bySeason.computeIfAbsent(episode.getSeasonNumber() == null ? 0 : episode.getSeasonNumber(), ignored -> new ArrayList<>()).add(episode);
        List<LocalMediaSeason> seasons = bySeason.entrySet().stream()
                .map(entry -> new LocalMediaSeason(entry.getKey(), entry.getValue())).toList();
        String displayTitle = reference.getTmdbTitle() == null || reference.getTmdbTitle().isBlank()
                ? canonicalTitle : reference.getTmdbTitle();
        model.addAttribute("activePage", "library");
        model.addAttribute("signedIn", authentication != null);
        model.addAttribute("currentEmail", authentication == null ? "" : authentication.getName());
        model.addAttribute("show", reference);
        model.addAttribute("showTitle", displayTitle);
        model.addAttribute("seriesTitle", canonicalTitle);
        model.addAttribute("seasons", seasons);
        model.addAttribute("episodeCount", episodes.size());
        model.addAttribute("seasonCount", seasons.size());
        model.addAttribute("seasonLabel", seasons.size() == 1 ? "1 season" : seasons.size() + " seasons");
        model.addAttribute("episodes", episodes);
            return "library/series";
    }

    @GetMapping("/library/{id}/play")
    public String play(@PathVariable long id, Model model, Authentication authentication, CsrfToken csrfToken) {
        // The player page is long enough to commit its response before Thymeleaf
        // reaches the study-note forms. Resolve the deferred token now so the
        // session-backed CSRF token exists before the template starts rendering.
        csrfToken.getToken();
        LocalMedia item = scanner.require(id);
        model.addAttribute("activePage", "library");
        model.addAttribute("signedIn", authentication != null);
        model.addAttribute("currentEmail", authentication == null ? "" : authentication.getName());
        model.addAttribute("item", item);
        model.addAttribute("studyNotes", studyNotes.findByMediaIdOrderByAtSecondsAscIdAsc(item.getId()));
        boolean episode = "EPISODE".equals(item.getMediaKind()) && item.getSeriesTitle() != null;
        String canonicalSeriesTitle = episode ? seriesAliases.canonicalTitle(item.getSeriesTitle()) : null;
        String backUrl = episode
                ? "/library/series?title=" + org.springframework.web.util.UriUtils.encode(canonicalSeriesTitle, StandardCharsets.UTF_8)
                : "/library?kind=MOVIE";
        model.addAttribute("backUrl", backUrl);
        List<LocalMedia> shelf = episode
                ? scanner.list("", "EPISODE").stream()
                        .filter(candidate -> seriesKey(seriesAliases.canonicalTitle(candidate.getSeriesTitle())).equals(seriesKey(canonicalSeriesTitle)))
                        .sorted(Comparator.comparing(LocalMedia::getSeasonNumber, Comparator.nullsLast(Integer::compareTo))
                                .thenComparing(LocalMedia::getEpisodeNumber, Comparator.nullsLast(Integer::compareTo)))
                        .toList()
                : scanner.list("", "MOVIE").stream()
                        .sorted(Comparator.comparing(LocalMedia::getDisplayTitle, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                        .toList();
        model.addAttribute("shelfKind", episode ? "episode" : "movie");
        for (int index = 0; index < shelf.size(); index++) {
            if (!shelf.get(index).getId().equals(item.getId())) continue;
            if (index > 0) {
                model.addAttribute("previousItem", shelf.get(index - 1));
                model.addAttribute("previousPlayUrl", "/library/" + shelf.get(index - 1).getId() + "/play");
            }
            if (index + 1 < shelf.size()) {
                model.addAttribute("nextItem", shelf.get(index + 1));
                model.addAttribute("nextPlayUrl", "/library/" + shelf.get(index + 1).getId() + "/play");
            }
            break;
        }
        return "library/player";
    }

    @PostMapping("/library/{id}/notes")
    public String addStudyNote(@PathVariable long id, @RequestParam long atSeconds,
                               @RequestParam String text, RedirectAttributes redirect) {
        LocalMedia media = scanner.require(id);
        String noteText = text == null ? "" : text.trim();
        if (noteText.isBlank() || noteText.length() > 2000 || atSeconds < 0 || atSeconds > 604800) {
            redirect.addFlashAttribute("noteError", "Enter a note under 2,000 characters and a valid timestamp.");
        } else {
            studyNotes.save(new StudyNote(media, atSeconds, noteText));
            redirect.addFlashAttribute("notice", "Study note saved at " + formatTimestamp(atSeconds) + ".");
        }
        return "redirect:/library/" + id + "/play";
    }

    @PostMapping("/library/{id}/notes/{noteId}/delete")
    public String deleteStudyNote(@PathVariable long id, @PathVariable long noteId) {
        scanner.require(id);
        studyNotes.findByIdAndMediaId(noteId, id).ifPresent(studyNotes::delete);
        return "redirect:/library/" + id + "/play";
    }

    private String formatTimestamp(long seconds) {
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long remainder = seconds % 60;
        return hours > 0 ? String.format("%d:%02d:%02d", hours, minutes, remainder)
                : String.format("%d:%02d", minutes, remainder);
    }

    @PostMapping("/library/{id}/rename")
    public String rename(@PathVariable long id, @RequestParam String title, RedirectAttributes redirect) {
        scanner.require(id).rename(title);
        redirect.addFlashAttribute("notice", "Title updated.");
        return "redirect:/library";
    }

    @PostMapping("/library/{id}/tmdb-match")
    public String matchTmdb(@PathVariable long id) {
        scanner.require(id);
        return "redirect:/library/review/posters?mediaId=" + id;
    }

    @PostMapping("/library/series/tmdb-match")
    public String matchSeriesTmdb(@RequestParam String title) {
        List<LocalMedia> episodes = scanner.list("", "EPISODE").stream()
                .filter(item -> seriesKey(seriesAliases.canonicalTitle(item.getSeriesTitle()))
                        .equals(seriesKey(seriesAliases.canonicalTitle(title)))).toList();
        if (episodes.isEmpty()) return "redirect:/library?kind=SERIES";
        return "redirect:/library/review/posters?mediaId=" + episodes.get(0).getId();
    }

    private List<LocalMediaSeries> groupSeries(List<LocalMedia> episodes) {
        Map<String, List<LocalMedia>> grouped = new LinkedHashMap<>();
        episodes.stream().sorted(Comparator.comparing(item -> Objects.toString(item.getSeriesTitle(), ""), String.CASE_INSENSITIVE_ORDER))
                .forEach(item -> grouped.computeIfAbsent(seriesKey(seriesAliases.canonicalTitle(item.getSeriesTitle())), ignored -> new ArrayList<>()).add(item));
        return grouped.values().stream().map(items -> {
            LocalMedia reference = items.stream().filter(item -> item.getTmdbId() != null && hasPosterPath(item.getTmdbPosterPath()))
                    .findFirst().orElseGet(() -> items.stream().filter(item -> item.getTmdbId() != null)
                            .findFirst().orElse(items.get(0)));
            String title = reference.getSeriesTitle() == null ? reference.getDisplayTitle()
                    : seriesAliases.canonicalTitle(reference.getSeriesTitle());
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

    private boolean hasPosterPath(String posterPath) {
        return posterPath != null && !posterPath.isBlank();
    }

    @GetMapping("/library/{id}/video")
    @ResponseBody
    public ResponseEntity<ResourceRegion> video(@PathVariable long id, @RequestHeader HttpHeaders requestHeaders) throws IOException {
        LocalMedia item = scanner.require(id);
        Path path = checkedPath(item.getFilePath());
        return rangedVideo(path, requestHeaders, MediaTypeFactory.getMediaType(path.getFileName().toString())
                .orElse(MediaType.APPLICATION_OCTET_STREAM));
    }

    @GetMapping("/library/{id}/audio/tracks")
    @ResponseBody
    public ResponseEntity<AudioTracksResponse> audioTracks(@PathVariable long id) {
        LocalMedia item = scanner.require(id);
        Path media = checkedPath(item.getFilePath());
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(new AudioTracksResponse(true, null, embeddedAudio.tracks(media)));
        } catch (EmbeddedAudioService.AudioToolsUnavailableException exception) {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(new AudioTracksResponse(false, exception.getMessage(), List.of()));
        } catch (EmbeddedAudioService.AudioProcessingException exception) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).cacheControl(CacheControl.noStore())
                    .body(new AudioTracksResponse(true, exception.getMessage(), List.of()));
        }
    }

    @GetMapping("/library/{id}/audio/{streamIndex}/video")
    @ResponseBody
    public ResponseEntity<ResourceRegion> selectedAudioVideo(@PathVariable long id, @PathVariable int streamIndex,
                                                              @RequestHeader HttpHeaders requestHeaders) throws IOException {
        LocalMedia item = scanner.require(id);
        Path source = checkedPath(item.getFilePath());
        try {
            Path compatibleVideo = embeddedAudio.browserCompatibleVideo(source, streamIndex);
            return rangedVideo(compatibleVideo, requestHeaders, MediaType.valueOf("video/mp4"));
        } catch (EmbeddedAudioService.AudioToolsUnavailableException exception) {
            throw new AudioUnavailableException(exception.getMessage());
        } catch (EmbeddedAudioService.AudioProcessingException exception) {
            throw new AudioUnavailableException(exception.getMessage());
        }
    }

    @ExceptionHandler(AudioUnavailableException.class)
    @ResponseBody
    public ResponseEntity<String> audioUnavailable(AudioUnavailableException exception) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .contentType(MediaType.TEXT_PLAIN).body(exception.getMessage());
    }

    private ResponseEntity<ResourceRegion> rangedVideo(Path path, HttpHeaders requestHeaders, MediaType type) throws IOException {
        Resource resource = new FileSystemResource(path);
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
    public record AudioTracksResponse(boolean toolsAvailable, String message,
                                      List<EmbeddedAudioService.AudioTrack> tracks) {}
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY) private static class AudioUnavailableException extends RuntimeException {
        AudioUnavailableException(String message) { super(message); }
    }
}
