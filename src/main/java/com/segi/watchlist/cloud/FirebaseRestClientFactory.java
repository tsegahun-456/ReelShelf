package com.segi.watchlist.cloud;

import java.time.Duration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

final class FirebaseRestClientFactory {
    private FirebaseRestClientFactory() { }

    static RestClient create() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(3));
        requestFactory.setReadTimeout(Duration.ofSeconds(4));
        return RestClient.builder().requestFactory(requestFactory).build();
    }
}
