package com.segi.watchlist.settings;

import java.time.Duration;
import java.util.List;
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
            List<Map<String, Object>> tags = restClient.get()
                    .uri("/repos/{repo}/tags", repository)
                    .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<Map<String, Object>>>() {});
            if (tags == null || tags.isEmpty()) {
                return new UpdateCheck(current, null, false,
                        "No published release tags exist on GitHub yet, so there is nothing newer to install.");
            }
            String latest = String.valueOf(tags.get(0).get("name"));
            boolean updateAvailable = !normalize(latest).equals(normalize(current));
            return new UpdateCheck(current, latest, updateAvailable, updateAvailable
                    ? "ReelShelf " + latest + " is available. Run scripts/self-update.sh to install it."
                    : "You are running the latest build.");
        } catch (Exception ex) {
            return new UpdateCheck(current, null, false,
                    "Could not reach GitHub to check for updates. Check the internet connection and try again.");
        }
    }

    private static String normalize(String version) {
        String value = version.startsWith("v") ? version.substring(1) : version;
        return value.endsWith("-SNAPSHOT") ? value.substring(0, value.length() - "-SNAPSHOT".length()) : value;
    }

    public record UpdateCheck(String currentVersion, String latestVersion, boolean updateAvailable, String message) {}
}
