package com.segi.watchlist.settings;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

@RestController
@RequestMapping("/api/settings/update")
public class UpdateController {

    private final BuildProperties buildProperties;
    private final RestClient restClient;
    private final String repository;

    public UpdateController(ObjectProvider<BuildProperties> buildProperties,
                            @Value("${reelshelf.update.repository:tsegahun-456/ReelShelf}") String repository) {
        this.buildProperties = buildProperties.getIfAvailable();
        this.repository = repository;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(8));
        this.restClient = RestClient.builder()
                .baseUrl("https://api.github.com")
                .requestFactory(requestFactory)
                .build();
    }

    @GetMapping("/check")
    public UpdateCheck check() {
        String current = buildProperties != null ? buildProperties.getVersion() : "unknown";
        try {
            Map<String, Object> release = restClient.get()
                    .uri("/repos/{repo}/releases/latest", repository)
                    .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});
            if (release != null && release.get("tag_name") != null) {
                String latest = String.valueOf(release.get("tag_name"));
                String releaseUrl = String.valueOf(release.getOrDefault("html_url",
                        "https://github.com/" + repository + "/releases/latest"));
                return result(current, latest, releaseUrl);
            }
        } catch (Exception ignored) {
            // Tags also work for a repository before its first GitHub release is published.
        }

        try {
            List<Map<String, Object>> tags = restClient.get()
                    .uri("/repos/{repo}/tags", repository)
                    .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<Map<String, Object>>>() {});
            if (tags == null || tags.isEmpty()) {
                return new UpdateCheck(current, null, false, null,
                        "No ReelShelf release or version tag is published on GitHub yet.");
            }
            String latest = tags.stream()
                    .map(tag -> String.valueOf(tag.get("name")))
                    .max(Comparator.comparing(tag -> normalize(tag), UpdateController::compareVersions))
                    .orElseThrow();
            return result(current, latest, "https://github.com/" + repository + "/tree/" + latest);
        } catch (Exception exception) {
            return new UpdateCheck(current, null, false, null,
                    "Could not reach GitHub to check for updates. Check the internet connection and try again.");
        }
    }

    private UpdateCheck result(String current, String latest, String releaseUrl) {
        boolean updateAvailable = "unknown".equals(current) || compareVersions(latest, current) > 0
                || (normalize(latest).equals(normalize(current)) && isSnapshot(current) && !isSnapshot(latest));
        return new UpdateCheck(current, latest, updateAvailable, updateAvailable ? releaseUrl : null, updateAvailable
                ? "ReelShelf " + latest + " is available. Open the release page to download the updated app."
                : "You are running the latest build.");
    }

    private static String normalize(String version) {
        String value = version == null ? "" : version.trim();
        if (value.startsWith("v") || value.startsWith("V")) value = value.substring(1);
        return value.replaceFirst("(?i)-SNAPSHOT$", "");
    }

    private static boolean isSnapshot(String version) {
        return version != null && version.toUpperCase(Locale.ROOT).endsWith("-SNAPSHOT");
    }

    private static int compareVersions(String left, String right) {
        String[] leftParts = normalize(left).split("[.-]");
        String[] rightParts = normalize(right).split("[.-]");
        int length = Math.max(leftParts.length, rightParts.length);
        for (int i = 0; i < length; i++) {
            int comparison = Long.compare(numericPart(leftParts, i), numericPart(rightParts, i));
            if (comparison != 0) return comparison;
        }
        return 0;
    }

    private static long numericPart(String[] parts, int index) {
        if (index >= parts.length) return 0;
        String digits = parts[index].replaceFirst("^([0-9]+).*$", "$1");
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    public record UpdateCheck(String currentVersion, String latestVersion, boolean updateAvailable,
                              String releaseUrl, String message) {}
}
