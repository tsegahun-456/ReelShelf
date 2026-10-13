package com.segi.watchlist.library;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Reads container subtitle metadata and safely converts a selected text stream to WebVTT. */
@Service
public class EmbeddedSubtitleService {
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration EXTRACT_TIMEOUT = Duration.ofSeconds(90);
    private static final int MAX_PROBE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_SUBTITLE_BYTES = 16 * 1024 * 1024;
    private static final List<String> IMAGE_SUBTITLE_CODECS = List.of(
            "dvd_subtitle", "hdmv_pgs_subtitle", "dvb_subtitle", "dvb_teletext", "xsub", "arib_caption");

    private final String ffprobe;
    private final String ffmpeg;
    private final ObjectMapper objectMapper;

    public EmbeddedSubtitleService(@Value("${library.ffprobe-command:ffprobe}") String ffprobe,
                                   @Value("${library.ffmpeg-command:ffmpeg}") String ffmpeg,
                                   ObjectMapper objectMapper) {
        this.ffprobe = ffprobe;
        this.ffmpeg = ffmpeg;
        this.objectMapper = objectMapper;
    }

    public List<SubtitleTrack> tracks(Path mediaFile) {
        byte[] json = run(List.of(ffprobe, "-v", "error", "-show_entries",
                "stream=index,codec_type,codec_name:stream_tags=language,title", "-of", "json",
                mediaFile.toString()), PROBE_TIMEOUT, MAX_PROBE_BYTES, true);
        try {
            JsonNode streams = objectMapper.readTree(json).path("streams");
            List<SubtitleTrack> result = new ArrayList<>();
            for (JsonNode stream : streams) {
                if (!"subtitle".equals(stream.path("codec_type").asString())) continue;
                String codec = stream.path("codec_name").asString("unknown");
                String language = stream.path("tags").path("language").asString("");
                String title = stream.path("tags").path("title").asString("");
                boolean extractable = !IMAGE_SUBTITLE_CODECS.contains(codec.toLowerCase(Locale.ROOT))
                        && !"unknown".equalsIgnoreCase(codec);
                result.add(new SubtitleTrack(stream.path("index").asInt(), codec, language, title, extractable));
            }
            return List.copyOf(result);
        } catch (Exception exception) {
            throw new SubtitleProcessingException("Could not read subtitle tracks from this file.", exception);
        }
    }

    public byte[] extractWebVtt(Path mediaFile, int streamIndex) {
        SubtitleTrack selected = tracks(mediaFile).stream()
                .filter(track -> track.streamIndex() == streamIndex).findFirst()
                .orElseThrow(() -> new SubtitleProcessingException("That subtitle track is not in this video."));
        if (!selected.extractable()) {
            throw new UnsupportedSubtitleException("This is an image-based subtitle track and cannot be converted to browser captions.");
        }
        byte[] bytes = run(List.of(ffmpeg, "-nostdin", "-v", "error", "-i", mediaFile.toString(),
                "-map", "0:" + selected.streamIndex(), "-c:s", "webvtt", "-f", "webvtt", "pipe:1"),
                EXTRACT_TIMEOUT, MAX_SUBTITLE_BYTES, false);
        String webVtt = new String(bytes, StandardCharsets.UTF_8).replace("\uFEFF", "");
        if (!webVtt.stripLeading().startsWith("WEBVTT")) {
            throw new SubtitleProcessingException("FFmpeg did not return a valid WebVTT subtitle track.");
        }
        return webVtt.getBytes(StandardCharsets.UTF_8);
    }

    private byte[] run(List<String> command, Duration timeout, int maxBytes, boolean mergeError) {
        Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            if (mergeError) builder.redirectErrorStream(true);
            else builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            process = builder.start();
        } catch (IOException exception) {
            throw new SubtitleToolsUnavailableException();
        }

        CompletableFuture<byte[]> output = CompletableFuture.supplyAsync(() -> readBounded(process, maxBytes));
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new SubtitleProcessingException("Subtitle processing took too long. Try again or choose another track.");
            }
            byte[] bytes = output.get(3, TimeUnit.SECONDS);
            if (process.exitValue() != 0) {
                throw new SubtitleProcessingException("FFmpeg could not read this subtitle track.");
            }
            return bytes;
        } catch (SubtitleProcessingException exception) {
            throw exception;
        } catch (Exception exception) {
            process.destroyForcibly();
            throw new SubtitleProcessingException("Subtitle processing failed. The video itself is unchanged.", exception);
        }
    }

    private byte[] readBounded(Process process, int maxBytes) {
        try (InputStream input = process.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > maxBytes) {
                    process.destroyForcibly();
                    throw new SubtitleProcessingException("The subtitle track is too large to load in the browser.");
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } catch (IOException exception) {
            throw new SubtitleProcessingException("Could not read the subtitle output.", exception);
        }
    }

    public record SubtitleTrack(int streamIndex, String codec, String language, String title, boolean extractable) {}

    public static class SubtitleProcessingException extends RuntimeException {
        public SubtitleProcessingException(String message) { super(message); }
        public SubtitleProcessingException(String message, Throwable cause) { super(message, cause); }
    }

    public static final class SubtitleToolsUnavailableException extends SubtitleProcessingException {
        public SubtitleToolsUnavailableException() {
            super("Embedded subtitles need FFmpeg (ffprobe and ffmpeg). Add it to the app runtime to enable track detection.");
        }
    }

    public static final class UnsupportedSubtitleException extends SubtitleProcessingException {
        public UnsupportedSubtitleException(String message) { super(message); }
    }
}
