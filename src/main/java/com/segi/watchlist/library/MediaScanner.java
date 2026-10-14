package com.segi.watchlist.library;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class MediaScanner {
    private static final Set<String> VIDEO_EXT = Set.of("mkv", "mp4", "m4v", "webm", "avi", "mov", "mpg", "mpeg", "ts");
    private static final Set<String> SUB_EXT = Set.of("srt", "vtt");
    private static final Set<String> SKIP_DIR = Set.of("music", "$recycle.bin", "system volume information", ".trash-1000",
            "proc", "sys", "dev", "run");
    private static final Pattern EPISODE = Pattern.compile("(?i)^(.*?)\\bS(\\d{1,2})[ ._-]*E(\\d{1,3})(?:[ ._-]*E(\\d{1,3}))?(?:[ ._-]+(.*))?$");
    private static final Pattern NUMBERED_EPISODE = Pattern.compile("(?i)^(\\d{1,3})[ ._-]+(.+)$");
    private static final Pattern SEASON_FOLDER = Pattern.compile("(?i)^(?:s(?:eason)?[ ._-]*)?(\\d{1,2})$");
    private static final Pattern MOVIE_YEAR = Pattern.compile("(?i)^(.*?)[ ._-]+(19\\d{2}|20\\d{2})(?:\\b.*)?$");
    private static final Pattern RELEASE_NOISE = Pattern.compile("(?i)[ ._-]+(?:720p|1080p|2160p|480p|web[ ._-]?dl|webrip|bluray|brrip|hdtv|x26[45]|h[ ._-]?26[45]|hevc|av1|10bit|8bit|pahe|yts|proper|repack|aac|dts|hdr|dv)(?:[ ._-].*)?$");

    private final LocalMediaRepository repository;
    private final LocalPosterCacheService posterCache;
    private final MediaRootService rootService;
    public MediaScanner(LocalMediaRepository repository, LocalPosterCacheService posterCache,
                        MediaRootService rootService) {
        this.repository = repository;
        this.posterCache = posterCache;
        this.rootService = rootService;
    }

    @Scheduled(fixedDelayString = "${library.scan-interval-ms:60000}", initialDelayString = "${library.scan-initial-delay-ms:3000}")
    public synchronized void scan() {
        // Folders come from the database (Settings page) so they can change without a restart.
        for (Path root : rootService.currentRoots()) {
            if (!Files.isDirectory(root) || !Files.isReadable(root)) continue;
            Set<String> seenPaths = new HashSet<>();
            boolean[] complete = {true};
            try {
                Files.walkFileTree(root, new SimpleFileVisitor<>() {
                    @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        return dir.equals(root) || !SKIP_DIR.contains(dir.getFileName().toString().toLowerCase(Locale.ROOT))
                                ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
                    }
                    @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        String ext = extension(file);
                        if (!VIDEO_EXT.contains(ext) || !attrs.isRegularFile()) return FileVisitResult.CONTINUE;
                        if (!Files.isReadable(file)) { complete[0] = false; return FileVisitResult.CONTINUE; }
                        try {
                            MediaFile media = describe(file, root, attrs);
                            LocalMedia row = repository.findByFilePath(media.filePath()).orElseGet(LocalMedia::new);
                            row.updateFrom(media);
                            repository.save(row);
                            seenPaths.add(media.filePath());
                        } catch (IOException | RuntimeException ignored) { complete[0] = false; }
                        return FileVisitResult.CONTINUE;
                    }
                    @Override public FileVisitResult visitFileFailed(Path file, IOException exc) { complete[0] = false; return FileVisitResult.CONTINUE; }
                });
                if (complete[0]) removeMissingFiles(root, seenPaths);
            } catch (IOException ignored) { /* A temporarily unreadable drive is retried by the next scan. */ }
        }
    }

    private void removeMissingFiles(Path root, Set<String> seenPaths) {
        List<LocalMedia> stale = repository.findAllByOrderByDisplayTitleAsc().stream()
                .filter(item -> Path.of(item.getFilePath()).startsWith(root))
                .filter(item -> !seenPaths.contains(item.getFilePath()))
                .toList();
        if (!stale.isEmpty()) {
            repository.deleteAll(stale);
            posterCache.removeUnreferencedPosters();
        }
    }

    public List<LocalMedia> list(String query, String kind) {
        List<LocalMedia> rows = "EPISODE".equals(kind) ? repository.findByMediaKindOrderBySeriesTitleAscSeasonNumberAscEpisodeNumberAsc("EPISODE")
                : "MOVIE".equals(kind) ? repository.findByMediaKindOrderByDisplayTitleAsc("MOVIE") : repository.findAllByOrderByDisplayTitleAsc();
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) return rows;
        return rows.stream().filter(row -> (row.getDisplayTitle() + " " + Objects.toString(row.getSeriesTitle(), "") + " " + row.getFileName())
                .toLowerCase(Locale.ROOT).contains(needle)).toList();
    }

    public LocalMedia require(long id) { return repository.findById(id).orElseThrow(() -> new NoSuchElementException("Media not found")); }
    public boolean isAllowed(Path path) {
        Path resolved = path.toAbsolutePath().normalize();
        return rootService.currentRoots().stream().anyMatch(resolved::startsWith);
    }
    public static String extension(Path p) { String name = p.getFileName().toString(); int i = name.lastIndexOf('.'); return i < 0 ? "" : name.substring(i + 1).toLowerCase(Locale.ROOT); }

    private MediaFile describe(Path file, Path root, BasicFileAttributes attrs) throws IOException {
        String filename = file.getFileName().toString();
        String stem = filename.replaceFirst("(?i)\\.[^.]+$", "").replace('_', ' ').replace('.', ' ').replaceAll("\\s+", " ").trim();
        Matcher em = EPISODE.matcher(stem);
        String kind = em.matches() ? "EPISODE" : "MOVIE";
        String title, series = null, epTitle = null; Integer season = null, episode = null;
        String folderSeries = seriesFolderTitle(file, root);
        if (em.matches()) {
            series = cleanTitle(em.group(1));
            if (series.isBlank()) series = cleanTitle(file.getParent().getFileName().toString());
            if (folderSeries != null && !folderSeries.isBlank()) series = cleanTitle(folderSeries);
            season = Integer.parseInt(em.group(2)); episode = Integer.parseInt(em.group(3));
            epTitle = em.group(5) == null ? null : cleanTitle(em.group(5));
            if ("Untitled".equals(epTitle)) epTitle = null;
            title = series + " · S" + String.format("%02d", season) + "E" + String.format("%02d", episode)
                    + (epTitle == null || epTitle.isBlank() ? "" : " · " + epTitle);
        } else {
            Matcher numbered = NUMBERED_EPISODE.matcher(stem);
            String numberedSeries = numbered.matches() ? cleanTitle(numbered.group(2)) : "";
            if (numbered.matches() && hasNumberedSiblings(file, numberedSeries)) {
                kind = "EPISODE";
                series = folderSeries == null || folderSeries.isBlank() ? numberedSeries : cleanTitle(folderSeries);
                season = seasonFolderNumber(file, root).orElse(1);
                episode = Integer.parseInt(numbered.group(1));
                title = series + " · S" + String.format("%02d", season) + "E" + String.format("%02d", episode);
            } else {
                String movie = stem.replaceAll("\\[[^]]*]", " ").trim();
                Matcher yearMatcher = MOVIE_YEAR.matcher(movie);
                title = yearMatcher.matches() ? cleanTitle(yearMatcher.group(1)) + " (" + yearMatcher.group(2) + ")" : cleanTitle(movie);
            }
        }
        Path sub = findSubtitle(file);
        return new MediaFile(file.toRealPath().toString(), filename, title, kind, series, season, episode, epTitle,
                sub == null ? null : sub.toRealPath().toString(), attrs.size(), attrs.lastModifiedTime().toInstant());
    }

    private String seriesFolderTitle(Path file, Path root) {
        Path parent = file.getParent();
        if (parent == null || parent.equals(root)) return null;
        Matcher season = SEASON_FOLDER.matcher(parent.getFileName().toString());
        if (!season.matches()) return null;
        Path showFolder = parent.getParent();
        return showFolder == null || showFolder.equals(root) ? null : showFolder.getFileName().toString();
    }

    private Optional<Integer> seasonFolderNumber(Path file, Path root) {
        Path parent = file.getParent();
        if (parent == null || parent.equals(root)) return Optional.empty();
        Matcher season = SEASON_FOLDER.matcher(parent.getFileName().toString());
        return season.matches() ? Optional.of(Integer.parseInt(season.group(1))) : Optional.empty();
    }

    private boolean hasNumberedSiblings(Path file, String seriesTitle) {
        Set<Integer> episodeNumbers = new HashSet<>();
        try (DirectoryStream<Path> siblings = Files.newDirectoryStream(file.getParent())) {
            for (Path sibling : siblings) {
                if (!Files.isRegularFile(sibling) || !VIDEO_EXT.contains(extension(sibling))) continue;
                String siblingStem = sibling.getFileName().toString().replaceFirst("(?i)\\.[^.]+$", "").replace('_', ' ').replace('.', ' ').trim();
                Matcher numbered = NUMBERED_EPISODE.matcher(siblingStem);
                if (numbered.matches() && cleanTitle(numbered.group(2)).equalsIgnoreCase(seriesTitle)) {
                    episodeNumbers.add(Integer.parseInt(numbered.group(1)));
                }
            }
        } catch (IOException ignored) { return false; }
        return episodeNumbers.size() >= 2;
    }

    private Path findSubtitle(Path video) throws IOException {
        String stem = video.getFileName().toString().replaceFirst("(?i)\\.[^.]+$", "");
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(video.getParent())) {
            for (Path entry : entries) {
                if (!Files.isRegularFile(entry) || !SUB_EXT.contains(extension(entry))) continue;
                String subStem = entry.getFileName().toString().replaceFirst("(?i)\\.[^.]+$", "");
                if ((subStem.equalsIgnoreCase(stem) || subStem.regionMatches(true, 0, stem + ".", 0, stem.length() + 1))
                        && Files.isReadable(entry)) return entry;
            }
        }
        return null;
    }

    private String cleanTitle(String raw) {
        String value = raw.replaceFirst("(?i)^(720p|1080p|2160p|480p|web[- .]?dl|webrip|bluray|brrip|hdtv|x26[45]|h[ .]?26[45]|hevc|av1|10bit|8bit|pahe|yts|proper|repack|aac|dts|hdr|dv).*", "");
        value = RELEASE_NOISE.matcher(value).replaceFirst("").replaceAll("\\[[^]]*]", " ").replaceAll("[._]+", " ").replaceAll("\\s+", " ").trim();
        value = value.replaceAll("(?i)\\b(19\\d{2}|20\\d{2})\\b", "").replaceAll("\\s+", " ").trim();
        if (value.isBlank()) return "Untitled";
        String[] words = value.toLowerCase(Locale.ROOT).split(" ");
        StringJoiner result = new StringJoiner(" ");
        for (String word : words) result.add(word.isEmpty() ? word : Character.toUpperCase(word.charAt(0)) + word.substring(1));
        return result.toString();
    }

    public record MediaFile(String filePath, String fileName, String displayTitle, String mediaKind, String seriesTitle,
                            Integer seasonNumber, Integer episodeNumber, String episodeTitle, String subtitlePath,
                            long fileSize, Instant modifiedAt) {}
}
