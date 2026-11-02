package com.segi.watchlist.library;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class MediaScanner {
    private static final Set<String> VIDEO_EXT = Set.of("mkv", "mp4", "m4v", "webm", "avi", "mov", "mpg", "mpeg", "ts");
    private static final Set<String> SUB_EXT = Set.of("srt", "vtt");
    /** How many visited files/directories between "is this folder still configured?" checks. */
    private static final int CHECK_EVERY = 50;
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

    @Scheduled(fixedDelayString = "${library.scan-interval-ms:300000}", initialDelayString = "${library.scan-initial-delay-ms:3000}")
    public synchronized void scan() {
        // Folders come from the database (Settings page) so they can change without a restart.
        Map<String, LocalMedia> knownByPath = new HashMap<>();
        repository.findAllByOrderByDisplayTitleAsc()
                .forEach(item -> knownByPath.put(item.getFilePath(), item));
        for (Path root : rootService.currentRoots()) {
            if (!Files.isDirectory(root) || !Files.isReadable(root)) continue;
            Path walkRoot = realRoot(root);
            Set<String> seenPaths = new HashSet<>();
            Map<Path, DirectorySnapshot> directorySnapshots = new HashMap<>();
            boolean[] complete = {true};
            boolean[] removed = {false};
            int[] visited = {0};
            try {
                Files.walkFileTree(walkRoot, new SimpleFileVisitor<>() {
                    @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (!dir.equals(walkRoot) && SKIP_DIR.contains(dir.getFileName().toString().toLowerCase(Locale.ROOT)))
                            return FileVisitResult.SKIP_SUBTREE;
                        if (++visited[0] % CHECK_EVERY == 0 && !rootService.isConfigured(root)) {
                            removed[0] = true;
                            return FileVisitResult.TERMINATE;
                        }
                        return FileVisitResult.CONTINUE;
                    }
                    @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        String ext = extension(file);
                        if (!VIDEO_EXT.contains(ext) || !attrs.isRegularFile()) return FileVisitResult.CONTINUE;
                        if (++visited[0] % CHECK_EVERY == 0 && !rootService.isConfigured(root)) {
                            removed[0] = true;
                            return FileVisitResult.TERMINATE;
                        }
                        if (!Files.isReadable(file)) { complete[0] = false; return FileVisitResult.CONTINUE; }
                        try {
                            // walkRoot is canonical and directory symlinks are not followed, so
                            // normalized paths are stable without an extra filesystem lookup per video.
                            String realFilePath = file.toAbsolutePath().normalize().toString();
                            DirectorySnapshot directory = directorySnapshot(file.getParent(), directorySnapshots);
                            Path subtitle = findSubtitle(file, directory);
                            String subtitlePath = subtitle == null ? null : subtitle.toRealPath().toString();
                            LocalMedia row = knownByPath.get(realFilePath);
                            Instant modifiedAt = attrs.lastModifiedTime().toInstant().truncatedTo(ChronoUnit.MICROS);
                            if (row != null && row.getFileSize() == attrs.size()
                                    && Objects.equals(row.getModifiedAt(), modifiedAt)
                                    && Objects.equals(row.getSubtitlePath(), subtitlePath)) {
                                seenPaths.add(realFilePath);
                                return FileVisitResult.CONTINUE;
                            }

                            MediaFile media = describe(file, walkRoot, attrs, directory, realFilePath, subtitlePath);
                            if (row == null) row = new LocalMedia();
                            boolean changed = row.updateFrom(media);
                            if (changed) {
                                if (row.getId() == null) {
                                    row = repository.save(row);
                                } else {
                                    repository.updateScannedMetadata(media.filePath(), media.fileName(),
                                            media.displayTitle(), media.mediaKind(), media.seriesTitle(),
                                            media.seasonNumber(), media.episodeNumber(), media.episodeTitle(),
                                            media.subtitlePath(), media.fileSize(), media.modifiedAt(), Instant.now());
                                }
                                knownByPath.put(media.filePath(), row);
                            }
                            seenPaths.add(media.filePath());
                        } catch (IOException | RuntimeException ignored) { complete[0] = false; }
                        return FileVisitResult.CONTINUE;
                    }
                    @Override public FileVisitResult visitFileFailed(Path file, IOException exc) { complete[0] = false; return FileVisitResult.CONTINUE; }
                    @Override public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                        directorySnapshots.remove(dir);
                        if (exc != null) complete[0] = false;
                        return FileVisitResult.CONTINUE;
                    }
                });
                // A folder removed mid-walk stops here; its rows were (or will be) cleaned by the prune below.
                if (complete[0] && !removed[0]) removeMissingFiles(walkRoot, seenPaths, knownByPath);
            } catch (IOException ignored) { /* A temporarily unreadable drive is retried by the next scan. */ }
        }
        // Safety net: drop rows collected under folders that are no longer configured (covers removal races).
        rootService.pruneOrphanRows();
    }

    private void removeMissingFiles(Path root, Set<String> seenPaths, Map<String, LocalMedia> knownByPath) {
        Path realRoot = realRoot(root);
        List<LocalMedia> stale = knownByPath.values().stream()
                .filter(item -> Path.of(item.getFilePath()).startsWith(realRoot))
                .filter(item -> !seenPaths.contains(item.getFilePath()))
                .toList();
        if (!stale.isEmpty()) {
            repository.deleteAll(stale);
            stale.forEach(item -> knownByPath.remove(item.getFilePath()));
            posterCache.removeUnreferencedPosters();
        }
    }

    /** Row paths are stored real-path'ed; resolve the root the same way before comparing prefixes. */
    private static Path realRoot(Path root) {
        try {
            return root.toRealPath();
        } catch (IOException | InvalidPathException ignored) {
            return root;
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
        return rootService.currentRoots().stream().anyMatch(root -> {
            Path allowedRoot;
            try {
                allowedRoot = root.toRealPath();
            } catch (IOException | SecurityException ignored) {
                allowedRoot = root.toAbsolutePath().normalize();
            }
            return resolved.startsWith(allowedRoot);
        });
    }
    public static String extension(Path p) { String name = p.getFileName().toString(); int i = name.lastIndexOf('.'); return i < 0 ? "" : name.substring(i + 1).toLowerCase(Locale.ROOT); }

    private MediaFile describe(Path file, Path root, BasicFileAttributes attrs, DirectorySnapshot directory,
                               String realFilePath, String subtitlePath) {
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
            if (numbered.matches() && hasNumberedSiblings(numberedSeries, directory)) {
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
        return new MediaFile(realFilePath, filename, title, kind, series, season, episode, epTitle,
                subtitlePath, attrs.size(), attrs.lastModifiedTime().toInstant().truncatedTo(ChronoUnit.MICROS));
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

    private boolean hasNumberedSiblings(String seriesTitle, DirectorySnapshot directory) {
        return directory.numberedEpisodes().getOrDefault(seriesTitle.toLowerCase(Locale.ROOT), Set.of()).size() >= 2;
    }

    private DirectorySnapshot directorySnapshot(Path directory, Map<Path, DirectorySnapshot> snapshots) throws IOException {
        DirectorySnapshot cached = snapshots.get(directory);
        if (cached != null) return cached;

        List<Path> subtitles = new ArrayList<>();
        Map<String, Set<Integer>> numberedEpisodes = new HashMap<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                if (!Files.isRegularFile(entry)) continue;
                String extension = extension(entry);
                if (SUB_EXT.contains(extension)) subtitles.add(entry);
                if (!VIDEO_EXT.contains(extension)) continue;
                String stem = entry.getFileName().toString().replaceFirst("(?i)\\.[^.]+$", "")
                        .replace('_', ' ').replace('.', ' ').replaceAll("\\s+", " ").trim();
                Matcher numbered = NUMBERED_EPISODE.matcher(stem);
                if (numbered.matches()) {
                    String title = cleanTitle(numbered.group(2)).toLowerCase(Locale.ROOT);
                    numberedEpisodes.computeIfAbsent(title, ignored -> new HashSet<>())
                            .add(Integer.parseInt(numbered.group(1)));
                }
            }
        }
        Map<String, Set<Integer>> immutableEpisodes = new HashMap<>();
        numberedEpisodes.forEach((title, numbers) -> immutableEpisodes.put(title, Set.copyOf(numbers)));
        DirectorySnapshot result = new DirectorySnapshot(List.copyOf(subtitles), Map.copyOf(immutableEpisodes));
        snapshots.put(directory, result);
        return result;
    }

    private Path findSubtitle(Path video, DirectorySnapshot directory) {
        String stem = video.getFileName().toString().replaceFirst("(?i)\\.[^.]+$", "");
        for (Path entry : directory.subtitles()) {
            String subStem = entry.getFileName().toString().replaceFirst("(?i)\\.[^.]+$", "");
            if ((subStem.equalsIgnoreCase(stem) || subStem.regionMatches(true, 0, stem + ".", 0, stem.length() + 1))
                    && Files.isReadable(entry)) return entry;
        }
        return null;
    }

    private String cleanTitle(String raw) {
        String value = raw.replaceFirst("(?i)^(720p|1080p|2160p|480p|web[- .]?dl|webrip|bluray|brrip|hdtv|x26[45]|h[ .]?26[45]|hevc|av1|10bit|8bit|pahe|yts|proper|repack|aac|dts|hdr|dv).*", "");
        value = RELEASE_NOISE.matcher(value).replaceFirst("").replaceAll("\\[[^]]*]", " ").replaceAll("[._]+", " ").replaceAll("\\s+", " ").trim();
        if (value.isBlank()) return "Untitled";
        String[] words = value.toLowerCase(Locale.ROOT).split(" ");
        StringJoiner result = new StringJoiner(" ");
        for (String word : words) result.add(word.isEmpty() ? word : Character.toUpperCase(word.charAt(0)) + word.substring(1));
        return result.toString();
    }

    public record MediaFile(String filePath, String fileName, String displayTitle, String mediaKind, String seriesTitle,
                            Integer seasonNumber, Integer episodeNumber, String episodeTitle, String subtitlePath,
                            long fileSize, Instant modifiedAt) {}

    private record DirectorySnapshot(List<Path> subtitles, Map<String, Set<Integer>> numberedEpisodes) {}
}
