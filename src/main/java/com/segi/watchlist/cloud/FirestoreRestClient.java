package com.segi.watchlist.cloud;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class FirestoreRestClient {
    private static final Logger log = LoggerFactory.getLogger(FirestoreRestClient.class);
    private final FirebaseProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient client = FirebaseRestClientFactory.create();

    public FirestoreRestClient(FirebaseProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public List<CloudTitle> list(String uid, String collection, String idToken) {
        List<CloudTitle> results = new ArrayList<>();
        String pageToken = null;
        do {
            String currentPage = pageToken;
            JsonNode response;
            try {
                response = client.get()
                        .uri(uri -> {
                            var builder = uri.scheme("https").host("firestore.googleapis.com")
                                    .path(collectionPath())
                                    .queryParam("pageSize", 1000);
                            if (currentPage != null && !currentPage.isBlank()) builder.queryParam("pageToken", currentPage);
                            return builder.build(properties.projectId(), uid, collection);
                        })
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + idToken)
                        .retrieve().body(JsonNode.class);
            } catch (RestClientResponseException exception) {
                logRestFailure("list", collection, exception);
                throw exception;
            }
            if (response == null) return results;
            for (JsonNode document : response.path("documents")) {
                CloudTitle title = readTitle(document.path("fields"));
                if (title != null) results.add(title);
            }
            pageToken = response.path("nextPageToken").asText("");
        } while (!pageToken.isBlank());
        return results;
    }

    public boolean initialized(String uid, String idToken) {
        try {
            client.get().uri(uri -> uri.scheme("https").host("firestore.googleapis.com")
                            .path(documentPath()).build(properties.projectId(), uid, "meta", "sync-v1"))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + idToken).retrieve().toBodilessEntity();
            return true;
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) return false; // First sync: marker document not written yet.
            throw wrapCloudError("get-meta", "meta", exception);
        }
    }

    public void markInitialized(String uid, String idToken) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("version", integerValue(1));
        fields.put("initializedAt", Map.of("timestampValue", Instant.now().toString()));
        writeDocument(uid, "meta", "sync-v1", idToken, Map.of("fields", fields));
    }

    public void put(String uid, String collection, CloudTitle title, String idToken) {
        writeDocument(uid, collection, title.documentId(), idToken, Map.of("fields", titleFields(title)));
    }

    public void delete(String uid, String collection, CloudTitle title, String idToken) {
        try {
            client.delete().uri(uri -> uri.scheme("https").host("firestore.googleapis.com")
                            .path(documentPath()).build(properties.projectId(), uid, collection, title.documentId()))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + idToken)
                    .retrieve().toBodilessEntity();
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) return; // Already gone: treat as success.
            throw wrapCloudError("delete", collection, exception);
        }
    }

    private void writeDocument(String uid, String collection, String documentId,
                               String idToken, Map<String, Object> payload) {
        try {
            client.patch().uri(uri -> uri.scheme("https").host("firestore.googleapis.com")
                            .path(documentPath()).build(properties.projectId(), uid, collection, documentId))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + idToken)
                    .contentType(MediaType.APPLICATION_JSON).body(payload)
                    .retrieve().toBodilessEntity();
        } catch (RestClientResponseException exception) {
            throw wrapCloudError("write", collection, exception);
        }
    }

    private Map<String, Object> titleFields(CloudTitle title) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("tmdbId", integerValue(title.tmdbId()));
        fields.put("mediaType", stringValue(title.mediaType()));
        fields.put("title", stringValue(title.title()));
        putOptional(fields, "posterPath", title.posterPath());
        putOptional(fields, "backdropPath", title.backdropPath());
        putOptional(fields, "releaseDate", title.releaseDate());
        if (title.voteAverage() != null) fields.put("voteAverage", Map.of("doubleValue", title.voteAverage()));
        if (title.userRating() != null) fields.put("userRating", integerValue(title.userRating()));
        fields.put("occurredAt", Map.of("timestampValue",
                (title.occurredAt() == null ? Instant.now() : title.occurredAt()).toString()));
        return fields;
    }

    private CloudTitle readTitle(JsonNode fields) {
        long id = readLong(fields.path("tmdbId"));
        String mediaType = readString(fields.path("mediaType"));
        String title = readString(fields.path("title"));
        if (id <= 0 || title.isBlank() || !("MOVIE".equals(mediaType) || "TV".equals(mediaType))) return null;
        BigDecimal vote = null;
        JsonNode voteNode = fields.path("voteAverage");
        if (voteNode.has("doubleValue")) vote = BigDecimal.valueOf(voteNode.path("doubleValue").asDouble());
        else if (voteNode.has("integerValue")) vote = BigDecimal.valueOf(readLong(voteNode));
        Integer rating = fields.path("userRating").isMissingNode() ? null : Math.toIntExact(readLong(fields.path("userRating")));
        Instant at = null;
        try { at = Instant.parse(readString(fields.path("occurredAt"))); }
        catch (Exception ignored) { /* Older cloud documents can omit the timestamp. */ }
        return new CloudTitle(id, mediaType, title, readString(fields.path("posterPath")),
                readString(fields.path("backdropPath")), readString(fields.path("releaseDate")), vote, rating, at);
    }

    private static void putOptional(Map<String, Object> fields, String name, String value) {
        if (value != null) fields.put(name, stringValue(value));
    }

    private static Map<String, Object> stringValue(String value) {
        return Map.of("stringValue", value == null ? "" : value);
    }

    private static Map<String, Object> integerValue(long value) {
        return Map.of("integerValue", Long.toString(value));
    }

    private static String readString(JsonNode field) {
        if (field.has("stringValue")) return field.path("stringValue").asText("");
        if (field.has("timestampValue")) return field.path("timestampValue").asText("");
        return "";
    }

    private static long readLong(JsonNode field) {
        try {
            if (field.has("integerValue")) return Long.parseLong(field.path("integerValue").asText());
            if (field.has("doubleValue")) return (long) field.path("doubleValue").asDouble();
        } catch (NumberFormatException ignored) { }
        return 0;
    }

    private String collectionPath() {
        return "/v1/projects/{projectId}/databases/(default)/documents/users/{uid}/{collection}";
    }

    private String documentPath() {
        return "/v1/projects/{projectId}/databases/(default)/documents/users/{uid}/{collection}/{documentId}";
    }

    private FirebaseCloudException wrapCloudError(String operation, String collection,
                                                  RestClientResponseException exception) {
        logRestFailure(operation, collection, exception);
        int status = exception.getStatusCode().value();
        String message = status == 401 || status == 403
                ? "Firebase rejected this request. Check the Firestore rules and sign in again."
                : "Cloud sync is unavailable right now. Your local copy is still saved.";
        return new FirebaseCloudException(message);
    }

    private void logRestFailure(String operation, String collection, RestClientResponseException exception) {
        log.warn("Firestore {} failed: collection={} projectId={} status={} error=\"{}\"",
                operation, collection, properties.projectId(), exception.getStatusCode().value(),
                safeErrorMessage(exception));
    }

    /**
     * Extracts only the {@code error.message} field from a Firebase error body, whitespace-normalised and
     * truncated. Raw bodies are never logged because identity-style responses can contain account details,
     * and no credentials (ID tokens, refresh tokens, API keys) are ever written to the log.
     */
    private String safeErrorMessage(RestClientResponseException exception) {
        try {
            String message = objectMapper.readTree(exception.getResponseBodyAsString())
                    .path("error").path("message").asText("");
            if (message.isBlank()) return "no message in response body";
            message = message.replaceAll("\\s+", " ").trim();
            return message.length() > 200 ? message.substring(0, 200) + "..." : message;
        } catch (Exception ignored) {
            return "unparseable response body";
        }
    }
}
