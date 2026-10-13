package com.segi.watchlist.library;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Keeps TMDB poster bytes in PostgreSQL so local posters survive restarts and internet outages. */
@Service
public class LocalPosterCacheService {
    private static final Logger log = LoggerFactory.getLogger(LocalPosterCacheService.class);
    private final JdbcTemplate jdbc;
    private final LocalMediaRepository mediaRepository;
    private final String imageBaseUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final Set<String> warming = ConcurrentHashMap.newKeySet();

    public LocalPosterCacheService(JdbcTemplate jdbc, LocalMediaRepository mediaRepository,
                                   @Value("${tmdb.image-base-url:https://image.tmdb.org/t/p}") String imageBaseUrl) {
        this.jdbc = jdbc;
        this.mediaRepository = mediaRepository;
        this.imageBaseUrl = imageBaseUrl.replaceAll("/+$", "");
    }

    public PosterImage getOrFetch(LocalMedia item) throws IOException, InterruptedException {
        if (item.getTmdbId() == null || item.getTmdbPosterPath() == null || item.getTmdbPosterPath().isBlank()) {
            throw new PosterNotAvailableException();
        }
        String path = safePosterPath(item.getTmdbPosterPath());
        PosterImage cached = read(item.getTmdbMediaType(), item.getTmdbId(), path);
        if (cached != null) return cached;
        return fetchAndSave(item.getTmdbMediaType(), item.getTmdbId(), path);
    }

    @Scheduled(initialDelayString = "${library.poster-cache-initial-delay-ms:10000}",
            fixedDelayString = "${library.poster-cache-interval-ms:60000}")
    public void cacheLinkedPosters() {
        Map<String, LocalMedia> uniqueTitles = new java.util.LinkedHashMap<>();
        for (LocalMedia item : mediaRepository.findAllByOrderByDisplayTitleAsc()) {
            if (item.getTmdbId() == null || item.getTmdbPosterPath() == null || item.getTmdbPosterPath().isBlank()) continue;
            String key = item.getTmdbMediaType() + ":" + item.getTmdbId();
            uniqueTitles.putIfAbsent(key, item);
        }
        for (Map.Entry<String, LocalMedia> entry : uniqueTitles.entrySet()) {
            String key = entry.getKey();
            LocalMedia item = entry.getValue();
            if (!warming.add(key)) continue;
            try {
                getOrFetch(item);
            } catch (IOException | InterruptedException | RuntimeException exception) {
                if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
                log.debug("Could not cache an optional TMDB poster; the local video library is still available.");
            } finally {
                warming.remove(key);
            }
        }
    }

    public void removeUnreferencedPosters() {
        jdbc.update("""
                DELETE FROM local_media_poster_cache AS poster
                WHERE NOT EXISTS (
                    SELECT 1 FROM local_media AS media
                    WHERE media.tmdb_id = poster.tmdb_id
                      AND media.tmdb_media_type = poster.tmdb_media_type
                )
                """);
    }

    private PosterImage read(String mediaType, long tmdbId, String posterPath) {
        List<PosterImage> found = jdbc.query("""
                SELECT content_type, poster_data FROM local_media_poster_cache
                WHERE tmdb_media_type = ? AND tmdb_id = ? AND poster_path = ?
                """, (result, row) -> new PosterImage(result.getBytes("poster_data"), result.getString("content_type")),
                mediaType, tmdbId, posterPath);
        return found.isEmpty() ? null : found.getFirst();
    }

    private PosterImage fetchAndSave(String mediaType, long tmdbId, String posterPath) throws IOException, InterruptedException {
        String media = "TV".equals(mediaType) ? "TV" : "MOVIE";
        URI uri = URI.create(imageBaseUrl + "/w500" + posterPath);
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300 || response.body().length == 0) {
            throw new IOException("The poster image could not be downloaded.");
        }
        String contentType = response.headers().firstValue("Content-Type").orElse("image/jpeg").split(";")[0].toLowerCase(Locale.ROOT);
        if (!contentType.startsWith("image/")) throw new IOException("The poster response was not an image.");
        int updated = jdbc.update("""
                UPDATE local_media_poster_cache
                   SET poster_path = ?, content_type = ?, poster_data = ?, cached_at = CURRENT_TIMESTAMP
                 WHERE tmdb_media_type = ? AND tmdb_id = ?
                """, posterPath, contentType, response.body(), media, tmdbId);
        if (updated == 0) {
            try {
                jdbc.update("""
                        INSERT INTO local_media_poster_cache
                            (tmdb_media_type, tmdb_id, poster_path, content_type, poster_data, cached_at)
                        VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                        """, media, tmdbId, posterPath, contentType, response.body());
            } catch (DuplicateKeyException concurrentInsert) {
                // Another request may have populated this title's cache between our UPDATE and INSERT.
                jdbc.update("""
                        UPDATE local_media_poster_cache
                           SET poster_path = ?, content_type = ?, poster_data = ?, cached_at = CURRENT_TIMESTAMP
                         WHERE tmdb_media_type = ? AND tmdb_id = ?
                        """, posterPath, contentType, response.body(), media, tmdbId);
            }
        }
        return new PosterImage(response.body(), contentType);
    }

    private String safePosterPath(String posterPath) {
        if (!posterPath.startsWith("/") || posterPath.contains("..") || posterPath.contains(":") || posterPath.contains("\\")) {
            throw new PosterNotAvailableException();
        }
        return posterPath;
    }

    public record PosterImage(byte[] bytes, String contentType) {}
    public static class PosterNotAvailableException extends RuntimeException {}
}
