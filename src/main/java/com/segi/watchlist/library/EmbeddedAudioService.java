package com.segi.watchlist.library;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Probes embedded audio tracks and prepares a browser-compatible, range-seekable fallback. */
@Service
public class EmbeddedAudioService {
    private static final int MAX_PROBE_BYTES = 2 * 1024 * 1024;
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration TRANSCODE_TIMEOUT = Duration.ofMinutes(45);
    private final String ffprobe;
    private final String ffmpeg;
    private final ObjectMapper mapper;
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public EmbeddedAudioService(@Value("${library.ffprobe-command:ffprobe}") String ffprobe,
                                @Value("${library.ffmpeg-command:ffmpeg}") String ffmpeg,
                                ObjectMapper mapper) {
        this.ffprobe = ffprobe;
        this.ffmpeg = ffmpeg;
        this.mapper = mapper;
    }

    public List<AudioTrack> tracks(Path file) {
        byte[] json = runProbe(List.of(ffprobe, "-v", "error", "-show_entries",
                "stream=index,codec_type,codec_name,channels:stream_tags=language,title", "-of", "json", file.toString()));
        try {
            List<AudioTrack> tracks = new ArrayList<>();
            for (JsonNode stream : mapper.readTree(json).path("streams")) {
                if (!"audio".equals(stream.path("codec_type").asString())) continue;
                String language = stream.path("tags").path("language").asString("");
                String title = stream.path("tags").path("title").asString("");
                tracks.add(new AudioTrack(stream.path("index").asInt(), stream.path("codec_name").asString("unknown"),
                        language, title, stream.path("channels").asInt(0)));
            }
            return List.copyOf(tracks);
        } catch (Exception exception) {
            throw new AudioProcessingException("Could not read audio tracks from this file.", exception);
        }
    }

    public Path browserCompatibleVideo(Path file, int streamIndex) {
        AudioTrack selected = tracks(file).stream().filter(track -> track.streamIndex() == streamIndex)
                .findFirst().orElseThrow(() -> new AudioProcessingException("That audio track is not in this video."));
        String key = cacheKey(file, selected.streamIndex());
        Path directory = Path.of(System.getProperty("java.io.tmpdir"), "reelshelf-audio-cache");
        Path output = directory.resolve(key + ".mp4");
        if (Files.isRegularFile(output) && size(output) > 0) return output;
        Object lock = locks.computeIfAbsent(key, ignored -> new Object());
        try {
            synchronized (lock) {
                if (Files.isRegularFile(output) && size(output) > 0) return output;
                Files.createDirectories(directory);
                Path temporary = directory.resolve(key + ".part.mp4");
                Files.deleteIfExists(temporary);
                List<String> command = List.of(ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
                        "-i", file.toString(), "-map", "0:v:0", "-map", "0:" + selected.streamIndex(),
                        "-sn", "-dn", "-c:v", "copy", "-c:a", "aac", "-b:a", "192k",
                        "-movflags", "+faststart", temporary.toString());
                Process process;
                try { process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start(); }
                catch (IOException exception) { throw new AudioToolsUnavailableException(); }
                try {
                    if (!process.waitFor(TRANSCODE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                        process.destroyForcibly();
                        throw new AudioProcessingException("Preparing this audio track took too long. The original video is unchanged.");
                    }
                    if (process.exitValue() != 0 || !Files.isRegularFile(temporary) || size(temporary) == 0) {
                        throw new AudioProcessingException("This video cannot be remuxed for audio-track switching. The original video is unchanged.");
                    }
                    try { Files.move(temporary, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE); }
                    catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                        Files.move(temporary, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                    return output;
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                    throw new AudioProcessingException("Audio-track preparation was interrupted.", exception);
                } finally {
                    Files.deleteIfExists(temporary);
                }
            }
        } catch (IOException exception) {
            throw new AudioProcessingException("Could not prepare this audio track.", exception);
        }
    }

    private byte[] runProbe(List<String> command) {
        Process process;
        try { process = new ProcessBuilder(command).redirectErrorStream(true).start(); }
        catch (IOException exception) { throw new AudioToolsUnavailableException(); }
        CompletableFuture<byte[]> output = CompletableFuture.supplyAsync(() -> {
            try { return readBounded(process.getInputStream()); }
            catch (IOException exception) { throw new java.util.concurrent.CompletionException(exception); }
        });
        try {
            if (!process.waitFor(PROBE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                output.cancel(true);
                throw new AudioProcessingException("Audio-track inspection took too long.");
            }
            if (process.exitValue() != 0) throw new AudioProcessingException("FFprobe could not inspect this video.");
            return output.get(PROBE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException | TimeoutException exception) {
            process.destroyForcibly();
            output.cancel(true);
            throw new AudioProcessingException("Could not read audio-track information.", exception);
        } catch (InterruptedException exception) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new AudioProcessingException("Audio-track inspection was interrupted.", exception);
        }
    }

    private byte[] readBounded(InputStream input) throws IOException {
        try (input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > MAX_PROBE_BYTES) throw new AudioProcessingException("Audio metadata is too large to inspect.");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private String cacheKey(Path path, int streamIndex) {
        try {
            String source = path.toRealPath() + "|" + Files.size(path) + "|" + Files.getLastModifiedTime(path).toMillis() + "|" + streamIndex;
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new AudioProcessingException("Could not identify the source video.", exception);
        }
    }

    private long size(Path file) {
        try { return Files.size(file); } catch (IOException ignored) { return 0; }
    }

    public record AudioTrack(int streamIndex, String codec, String language, String title, int channels) { }
    public static class AudioProcessingException extends RuntimeException {
        public AudioProcessingException(String message) { super(message); }
        public AudioProcessingException(String message, Throwable cause) { super(message, cause); }
    }
    public static final class AudioToolsUnavailableException extends AudioProcessingException {
        public AudioToolsUnavailableException() { super("Audio track switching needs FFmpeg (ffprobe and ffmpeg) in the app runtime."); }
    }
}
