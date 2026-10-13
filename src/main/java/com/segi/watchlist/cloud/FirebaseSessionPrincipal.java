package com.segi.watchlist.cloud;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public final class FirebaseSessionPrincipal implements UserDetails, Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
    private final String uid;
    private final String email;
    private final String displayName;
    private String idToken;
    private String refreshToken;
    private long tokenExpiresAtMillis;

    public FirebaseSessionPrincipal(FirebaseTokens tokens) {
        this.uid = tokens.uid();
        this.email = tokens.email();
        this.displayName = tokens.displayName();
        replaceTokens(tokens);
    }

    public String uid() { return uid; }
    public String email() { return email; }
    public String displayName() { return displayName; }

    public synchronized String idToken() { return idToken; }
    public synchronized String refreshToken() { return refreshToken; }
    public synchronized boolean tokenNeedsRefresh() {
        return Instant.now().toEpochMilli() >= tokenExpiresAtMillis - 60_000;
    }
    public synchronized void replaceTokens(FirebaseTokens tokens) {
        this.idToken = tokens.idToken();
        this.refreshToken = tokens.refreshToken();
        this.tokenExpiresAtMillis = Instant.now().toEpochMilli()
                + Math.max(1, tokens.expiresInSeconds()) * 1000;
    }

    @Override public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_USER"));
    }
    @Override public String getPassword() { return ""; }
    @Override public String getUsername() { return email; }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return true; }
}
