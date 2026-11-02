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
    static final int PARSER_VERSION = 2;
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
    private final MediaScanReviewService scanReview;
    public MediaScanner(LocalMediaRepository repository, LocalPosterCacheService posterCache,
                        MediaRootService rootService, MediaScanReviewService scanReview) {
        this.repository = repository;
        this.posterCache = posterCache;
        this.rootService = rootService;
        this.scanReview = scanReview;
    }

    @Scheduled(fixedDelayString = "${library.scan-interval-ms:300000}", initialDelayString = "${library.scan-initial-delay-ms:3000}")
    public synchronized void scan() {
        // Folders come from the database (Settings page) so they can change without a restart.
        List<Path> roots = rootService.currentRoots();
        scanReview.begin(roots.stream().map(Path::toString).toList());
        try {
        Map<String, LocalMedia> knownByPath = new HashMap<>();
        repository.findAllByOrderByDisplayTitleAsc()
                .forEach(item -> knownByPath.put(item.getFilePath(), item));
        for (Path root : roots) {
            if (!Files.isDirectory(root) || !Files.isReadable(root)) {
                scanReview.issue("Folder unavailable", root.toString(), "Folder is missing or cannot be read; its saved videos were left untouched.");
                scanReview.finishRoot();
                continue;
            }
            Path walkRoot = realRoot(root);
            Set<String> seenPaths = new HashSet<>();
            Map<Path, DirectorySnapshot> directorySnapshots = new HashMap<>();
            boolean[] complete = {true};
            boolean[] removed = {false};
            int[] visited = {0};
            try {
                Files.walkFileTree(walkRoot, new SimpleFileVisitor<>() {
                    @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        scanReview.visitPath();
                        if (!dir.equals(walkRoot) && SKIP_DIR.contains(dir.getFileName().toString().toLowerCase(Locale.ROOT)))
                            return FileVisitResult.SKIP_SUBTREE;
                        if (++visited[0] % CHECK_EVERY == 0 && !rootService.isConfigured(root)) {
                            removed[0] = true;
                            return FileVisitResult.TERMINATE;
                        }
                        return FileVisitResult.CONTINUE;
                    }
                    @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        scanReview.visitPath();
                        String ext = extension(file);
                        if (!VIDEO_EXT.contains(ext) || !attrs.isRegularFile()) return FileVisitResult.CONTINUE;
                        scanReview.foundVideo();
                        if (++visited[0] % CHECK_EVERY == 0 && !rootService.isConfigured(root)) {
                            removed[0] = true;
                            return FileVisitResult.TERMINATE;
                        }
                        if (!Files.isReadable(file)) {
                            complete[0] = false;
                            scanReview.issue("Unreadable video", file.toString(), "File cannot be read; saved library data was left untouched.");
                            return FileVisitResult.CONTINUE;
                        }
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
                                    && row.getParserVersion() == PARSER_VERSION
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
                                    scanReview.addedVideo();
                                } else {
                                    repository.updateScannedMetadata(media.filePath(), media.fileName(),
                                            media.displayTitle(), media.mediaKind(), media.seriesTitle(),
                                            media.seasonNumber(), media.episodeNumber(), media.episodeTitle(),
                                            media.subtitlePath(), media.fileSize(), media.modifiedAt(), Instant.now(), PARSER_VERSION);
                                    scanReview.updatedVideo();
                                }
                                knownByPath.put(media.filePath(), row);
                            }
                            seenPaths.add(media.filePath());
                        } catch (IOException | RuntimeException exception) {
                            complete[0] = false;
                            scanReview.issue("File scan failed", file.toString(), "File metadata could not be read; it will be retried.");
                        }
                        return FileVisitResult.CONTINUE;
                    }
                    @Override public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        complete[0] = false;
                        scanReview.issue("Unreadable path", file.toString(), "The operating system refused access; it will be retried.");
                        return FileVisitResult.CONTINUE;
                    }
                    @Override public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                        directorySnapshots.remove(dir);
                        if (exc != null) {
                            complete[0] = false;
                            scanReview.issue("Unreadable folder", dir.toString(), "Part of this folder could not be read; missing videos were not removed.");
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
                // A folder removed mid-walk stops here; its rows were (or will be) cleaned by the prune below.
                if (complete[0] && !removed[0]) scanReview.removedVideos(removeMissingFiles(walkRoot, seenPaths, knownByPath));
            } catch (IOException exception) {
                scanReview.issue("Folder scan failed", root.toString(), "The folder scan stopped early; existing entries were preserved.");
            } finally {
                scanReview.finishRoot();
            }
        }
        // Safety net: drop rows collected under folders that are no longer configured (covers removal races).
        rootService.pruneOrphanRows();
        } finally {
            scanReview.finish();
        }
    }

    private int removeMissingFiles(Path root, Set<String> seenPaths, Map<String, LocalMedia> knownByPath) {
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
        return stale.size();
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
        for (Path folder = file.getParent(); folder != null && !folder.equals(root); folder = folder.getParent()) {
            Matcher season = SEASON_FOLDER.matcher(folder.getFileName().toString());
            if (!season.matches()) continue;
            Path showFolder = folder.getParent();
            return showFolder == null || showFolder.equals(root) ? null : showFolder.getFileName().toString();
        }
        return null;
    }

    private Optional<Integer> seasonFolderNumber(Path file, Path root) {
        for (Path folder = file.getParent(); folder != null && !folder.equals(root); folder = folder.getParent()) {
            Matcher season = SEASON_FOLDER.matcher(folder.getFileName().toString());
            if (season.matches()) return Optional.of(Integer.parseInt(season.group(1)));
        }
        return Optional.empty();
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
