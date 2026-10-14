package com.segi.watchlist.library;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Owns the list of folders the local library scans (database-backed, editable
 * from the Settings page). The MEDIA_ROOTS configuration only seeds the list
 * on the very first run.
 */
@Service
public class MediaRootService {
    private static final Logger log = LoggerFactory.getLogger(MediaRootService.class);
    private static final List<String> SYSTEM_DIRECTORIES = List.of("/proc", "/sys", "/dev", "/run");

    private final MediaScanRootRepository roots;
    private final LocalMediaRepository media;
    private final LocalPosterCacheService posterCache;
    private final String seedRootsText;

    public MediaRootService(MediaScanRootRepository roots, LocalMediaRepository media,
                            LocalPosterCacheService posterCache,
                            @Value("${library.roots:}") String seedRootsText) {
        this.roots = roots;
        this.media = media;
        this.posterCache = posterCache;
        this.seedRootsText = seedRootsText;
    }

    @PostConstruct
    void seedFromConfiguration() {
        try {
            if (roots.count() > 0 || seedRootsText == null || seedRootsText.isBlank()) return;
            List<String> seeded = new ArrayList<>();
            Arrays.stream(seedRootsText.split(",")).map(String::trim)
                    .filter(value -> !value.isBlank())
                    .map(value -> normalizeQuietly(value))
                    .filter(value -> value != null && !isSystemDirectory(value))
                    .forEach(value -> { roots.save(new MediaScanRoot(value)); seeded.add(value); });
            if (!seeded.isEmpty()) log.info("Seeded media scan folders from configuration: {}", seeded);
        } catch (RuntimeException exception) {
            log.warn("Could not seed media scan folders from configuration: {}", exception.getMessage());
        }
    }

    /** Folders currently scanned, with live status for the Settings page. */
    public List<ScanFolder> folders() {
        return roots.findAllByOrderByPathAsc().stream()
                .map(row -> new ScanFolder(row.getPath(), isUsableDirectory(row.getPath()),
                        countMedia(row.getPath())))
                .toList();
    }

    /** Folders the scanner walks right now. */
    public List<Path> currentRoots() {
        return roots.findAllByOrderByPathAsc().stream()
                .map(row -> Paths.get(row.getPath()))
                .toList();
    }

    /** Existing folders on this PC that are worth suggesting and not configured yet. */
    public List<ScanFolder> suggestions() {
        Set<Path> candidates = new LinkedHashSet<>();
        Path home = homeDirectory();
        addIfExists(candidates, home.resolve("Videos"));
        addIfExists(candidates, home.resolve("Downloads"));
        addIfExists(candidates, home.resolve("Movies"));
        collectChildren(Paths.get("/media"), candidates, 1);
        collectChildren(Paths.get("/mnt"), candidates, 0);
        collectChildren(Paths.get("/run/media"), candidates, 1);

        Set<String> configured = new LinkedHashSet<>();
        roots.findAllByOrderByPathAsc().forEach(row -> configured.add(row.getPath()));
        return candidates.stream()
                .map(Path::toString)
                .filter(path -> !configured.contains(path))
                .sorted()
                .map(path -> new ScanFolder(path, true, countMedia(path)))
                .toList();
    }

    /** The visitor's home folder, when it exists and is not already configured. */
    public ScanFolder homeFolder() {
        Path home = homeDirectory();
        String path = home.toString();
        if (!isUsableDirectory(path) || roots.existsById(path)) return null;
        return new ScanFolder(path, true, countMedia(path));
    }

    /** The whole computer ("/"), when not already configured. */
    public ScanFolder everythingFolder() {
        String path = "/";
        if (roots.existsById(path)) return null;
        return new ScanFolder(path, true, countMedia(path));
    }

    public void add(String rawPath) {
        String path = requireValidPath(rawPath);
        if (roots.existsById(path)) throw new IllegalArgumentException("That folder is already in the list.");
        roots.save(new MediaScanRoot(path));
    }

    public void remove(String rawPath) {
        String path = rawPath == null ? "" : rawPath.trim();
        if (!roots.existsById(path)) throw new IllegalArgumentException("That folder is not in the list.");
        roots.deleteById(path);
        deleteCollectedMedia(path);
    }

    private void deleteCollectedMedia(String root) {
        try {
            String prefix = root.endsWith(File.separator) ? root : root + File.separator;
            List<LocalMedia> stale = media.findAllByOrderByDisplayTitleAsc().stream()
                    .filter(item -> item.getFilePath().equals(root)
                            || item.getFilePath().startsWith(prefix))
                    .toList();
            if (!stale.isEmpty()) {
                media.deleteAll(stale);
                posterCache.removeUnreferencedPosters();
                log.info("Removed {} local titles collected under {}", stale.size(), root);
            }
        } catch (RuntimeException exception) {
            log.warn("Could not clean up local titles under {}: {}", root, exception.getMessage());
        }
    }

    private String requireValidPath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("Enter a folder path first.");
        }
        String normalized = normalizeQuietly(rawPath);
        if (normalized == null) throw new IllegalArgumentException("That is not a valid folder path.");
        if (isSystemDirectory(normalized)) {
            throw new IllegalArgumentException("That is a system folder and is not scanned.");
        }
        if (!Files.isDirectory(Paths.get(normalized))) {
            throw new IllegalArgumentException("No folder was found at that path (it may be unplugged).");
        }
        if (!Files.isReadable(Paths.get(normalized))) {
            throw new IllegalArgumentException("That folder cannot be read by this app.");
        }
        return normalized;
    }

    private static String normalizeQuietly(String rawPath) {
        try {
            Path path = Paths.get(rawPath.trim());
            if (!path.isAbsolute()) return null;
            return path.toAbsolutePath().normalize().toString();
        } catch (InvalidPathException exception) {
            return null;
        }
    }

    private static boolean isSystemDirectory(String path) {
        String comparable = path.toLowerCase(Locale.ROOT);
        return SYSTEM_DIRECTORIES.stream().anyMatch(system -> comparable.equals(system)
                || comparable.startsWith(system + File.separator));
    }

    private static boolean isUsableDirectory(String path) {
        try {
            return Files.isDirectory(Paths.get(path)) && Files.isReadable(Paths.get(path));
        } catch (InvalidPathException exception) {
            return false;
        }
    }

    private long countMedia(String path) {
        try {
            String prefix = path.endsWith(File.separator) ? path : path + File.separator;
            return media.countByFilePathStartingWith(prefix);
        } catch (RuntimeException exception) {
            return 0;
        }
    }

    private static Path homeDirectory() {
        return Paths.get(System.getProperty("user.home", "/")).toAbsolutePath().normalize();
    }

    private static void addIfExists(Set<Path> candidates, Path candidate) {
        Path normalized = candidate.toAbsolutePath().normalize();
        if (Files.isDirectory(normalized)) candidates.add(normalized);
    }

    private static void collectChildren(Path directory, Set<Path> candidates, int depth) {
        if (depth < 0 || !Files.isDirectory(directory) || !Files.isReadable(directory)) return;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory, Files::isDirectory)) {
            for (Path child : entries) {
                Path normalized = child.toAbsolutePath().normalize();
                if (isSystemDirectory(normalized.toString())) continue;
                candidates.add(normalized);
                if (depth > 0) collectChildren(normalized, candidates, depth - 1);
            }
        } catch (IOException | SecurityException ignored) {
            // A drive that cannot be listed is simply not suggested.
        }
    }

    /** A folder plus its live status, for the Settings page. */
    public record ScanFolder(String path, boolean exists, long mediaCount) {
    }
}
