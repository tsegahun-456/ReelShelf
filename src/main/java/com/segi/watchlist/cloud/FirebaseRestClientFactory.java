package com.segi.watchlist.cloud;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

final class FirebaseRestClientFactory {
    private FirebaseRestClientFactory() { }

    /**
     * Builds the shared Firestore/Identity REST client.
     *
     * <p>Uses the JDK {@code java.net.http}-backed request factory instead of
     * {@code SimpleClientHttpRequestFactory}: the latter is {@code java.net.HttpURLConnection}-based and
     * rejects the {@code PATCH} verb client-side with {@code ProtocolException: Invalid HTTP method: PATCH},
     * which broke every Firestore document write (create/update uses PATCH on the document path).</p>
     */
    static RestClient create() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(4));
        return RestClient.builder().requestFactory(requestFactory).build();
    }
}
