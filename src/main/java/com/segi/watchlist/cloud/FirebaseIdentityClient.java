package com.segi.watchlist.cloud;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class FirebaseIdentityClient {
    private final FirebaseProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient client = FirebaseRestClientFactory.create();

    public FirebaseIdentityClient(FirebaseProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public FirebaseTokens signIn(String email, String password) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email.trim());
        body.put("password", password);
        body.put("returnSecureToken", true);
        return identityRequest("signInWithPassword", body);
    }

    public FirebaseTokens signUp(String email, String password, String displayName) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email.trim());
        body.put("password", password);
        body.put("returnSecureToken", true);
        if (displayName != null && !displayName.isBlank()) body.put("displayName", displayName.trim());
        return identityRequest("signUp", body);
    }

    public String freshIdToken(FirebaseSessionPrincipal principal) {
        if (!principal.tokenNeedsRefresh()) return principal.idToken();
        FirebaseTokens tokens = refreshTokens(principal.refreshToken(), principal.displayName());
        principal.replaceTokens(tokens);
        return tokens.idToken();
    }

    /** Exchanges a Firebase refresh token for a fresh token set (used for in-session refresh and sign-in restore). */
    public FirebaseTokens refreshTokens(String refreshToken, String displayName) {
        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "refresh_token");
            form.add("refresh_token", refreshToken);
            Map<?, ?> response = client.post()
                    .uri(uri -> uri.scheme("https").host("securetoken.googleapis.com")
                            .path("/v1/token").queryParam("key", properties.webApiKey()).build())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve().body(Map.class);
            if (response == null) throw new FirebaseIdentityException("TOKEN_REFRESH_FAILED");
            return new FirebaseTokens(
                    string(response.get("user_id")),
                    string(response.get("email")), displayName == null ? "" : displayName,
                    string(response.get("id_token")), string(response.get("refresh_token")),
                    parseLong(response.get("expires_in"), 3600));
        } catch (RestClientResponseException exception) {
            throw toIdentityException(exception);
        }
    }

    private FirebaseTokens identityRequest(String operation, Map<String, Object> body) {
        ensureConfigured();
        try {
            Map<?, ?> response = client.post()
                    .uri(uri -> uri.scheme("https").host("identitytoolkit.googleapis.com")
                            .path("/v1/accounts:" + operation)
                            .queryParam("key", properties.webApiKey()).build())
                    .contentType(MediaType.APPLICATION_JSON).body(body)
                    .retrieve().body(Map.class);
            if (response == null) throw new FirebaseIdentityException("IDENTITY_RESPONSE_EMPTY");
            return new FirebaseTokens(
                    string(response.get("localId")), string(response.get("email")),
                    string(response.get("displayName")), string(response.get("idToken")),
                    string(response.get("refreshToken")), parseLong(response.get("expiresIn"), 3600));
        } catch (RestClientResponseException exception) {
            throw toIdentityException(exception);
        }
    }

    private FirebaseIdentityException toIdentityException(RestClientResponseException exception) {
        String code = "FIREBASE_REQUEST_FAILED";
        try {
            JsonNode root = objectMapper.readTree(exception.getResponseBodyAsString());
            String parsed = root.path("error").path("message").asText("");
            if (!parsed.isBlank()) code = parsed.split("\\s*:")[0];
        } catch (Exception ignored) {
            // Do not expose response bodies because they can contain account details.
        }
        return new FirebaseIdentityException(code);
    }

    private void ensureConfigured() {
        if (!properties.authConfigured()) throw new FirebaseIdentityException("FIREBASE_NOT_CONFIGURED");
    }

    private static String string(Object value) {
        return value == null ? "" : value.toString();
    }

    private static long parseLong(Object value, long fallback) {
        try { return Long.parseLong(string(value)); }
        catch (NumberFormatException ignored) { return fallback; }
    }
}
