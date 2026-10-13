package com.segi.watchlist.cloud;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class FirebaseProperties {
    private final String webApiKey;
    private final String projectId;

    public FirebaseProperties(@Value("${firebase.web-api-key:}") String webApiKey,
                              @Value("${firebase.project-id:}") String projectId) {
        this.webApiKey = webApiKey == null ? "" : webApiKey.trim();
        this.projectId = projectId == null ? "" : projectId.trim();
    }

    public String webApiKey() { return webApiKey; }
    public String projectId() { return projectId; }
    public boolean authConfigured() { return !webApiKey.isBlank(); }
    public boolean firestoreConfigured() { return authConfigured() && !projectId.isBlank(); }
}
