package com.segi.watchlist.cloud;

public record FirebaseTokens(String uid, String email, String displayName,
                             String idToken, String refreshToken, long expiresInSeconds) { }
