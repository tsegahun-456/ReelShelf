package com.segi.watchlist.cloud;

public class FirebaseIdentityException extends RuntimeException {
    private final String code;

    public FirebaseIdentityException(String code) {
        super(code);
        this.code = code;
    }

    public String code() { return code; }
}
