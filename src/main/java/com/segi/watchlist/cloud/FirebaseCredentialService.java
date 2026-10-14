package com.segi.watchlist.cloud;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

/**
 * Stores and restores remembered sign-ins. The Firebase refresh token is kept
 * AES-encrypted; restoring a session means exchanging it for a fresh ID token.
 */
@Service
public class FirebaseCredentialService {
    private static final Logger log = LoggerFactory.getLogger(FirebaseCredentialService.class);

    private final FirebaseCredentialRepository repository;
    private final FirebaseIdentityClient firebase;
    private final TokenCipher cipher;

    public FirebaseCredentialService(FirebaseCredentialRepository repository, FirebaseIdentityClient firebase,
                                     TokenCipher cipher) {
        this.repository = repository;
        this.firebase = firebase;
        this.cipher = cipher;
    }

    /** Called when the user signs in with "Keep me signed in" ticked. */
    public void remember(FirebaseSessionPrincipal principal) {
        String refreshToken = principal.refreshToken();
        if (refreshToken == null || refreshToken.isBlank()) return;
        if (principal.email() == null || principal.email().isBlank()) {
            // A credential without an email could never be looked up again; refuse it.
            log.warn("Skip remembered sign-in: the session carries no email for uid={}.", principal.uid());
            return;
        }
        try {
            FirebaseCredential row = repository.findById(principal.email()).orElseGet(FirebaseCredential::new);
            row.update(principal.email(), principal.uid(), principal.displayName(), cipher.encrypt(refreshToken));
            repository.save(row);
        } catch (DataAccessException | IllegalStateException exception) {
            // Signing in still succeeds; only the "remember me" part is skipped.
            log.warn("Could not save the remembered sign-in for uid={}: {}", principal.uid(),
                    exception.getMessage());
        }
    }

    /** Called when the user signs in without the option: forget any previous save. */
    public void forgetQuietly(String email) {
        if (email == null || email.isBlank()) return;
        try {
            if (repository.existsById(email.trim())) repository.deleteById(email.trim());
        } catch (DataAccessException exception) {
            log.warn("Could not remove the remembered sign-in: {}", exception.getMessage());
        }
    }

    /**
     * Restores a session from the remembered sign-in by minting a fresh ID token.
     * Throws {@link UsernameNotFoundException} when no usable credential exists so
     * the visitor simply sees the sign-in page again.
     */
    public FirebaseSessionPrincipal restorePrincipal(String email) {
        String normalized = email == null ? "" : email.trim();
        FirebaseCredential row;
        try {
            row = repository.findById(normalized)
                    .orElseThrow(() -> new UsernameNotFoundException("No remembered sign-in for that account."));
        } catch (DataAccessException exception) {
            throw new UsernameNotFoundException("The remembered sign-in cannot be read right now.");
        }

        FirebaseTokens refreshed;
        try {
            refreshed = firebase.refreshTokens(cipher.decrypt(row.getRefreshTokenEncrypted()), row.getDisplayName());
        } catch (FirebaseIdentityException exception) {
            if (isPermanentFailure(exception)) {
                log.info("Remembered sign-in for uid={} is no longer valid; removing it.", row.getFirebaseUid());
                forgetQuietly(normalized);
            }
            throw new UsernameNotFoundException("The remembered sign-in can no longer be used.");
        } catch (RestClientException exception) {
            throw new AuthenticationServiceException("Sign-in restore is unavailable while offline.");
        } catch (IllegalStateException exception) {
            // The stored token cannot be decrypted on this PC (key file changed or damaged).
            log.warn("Remembered sign-in for uid={} cannot be decrypted here; removing it.", row.getFirebaseUid());
            forgetQuietly(normalized);
            throw new UsernameNotFoundException("The remembered sign-in cannot be used on this PC.");
        }

        // The token-refresh response can omit email/display name; keep the stored ones so the
        // restored session stays tied to the right account everywhere.
        FirebaseTokens tokens = new FirebaseTokens(
                refreshed.uid().isBlank() ? row.getFirebaseUid() : refreshed.uid(),
                refreshed.email().isBlank() ? row.getEmail() : refreshed.email(),
                refreshed.displayName().isBlank() ? row.getDisplayName() : refreshed.displayName(),
                refreshed.idToken(), refreshed.refreshToken(), refreshed.expiresInSeconds());

        FirebaseSessionPrincipal principal = new FirebaseSessionPrincipal(tokens);
        // Firebase may rotate the refresh token; keep the newest one encrypted on disk.
        remember(principal);
        log.info("Restored remembered sign-in for uid={}.", principal.uid());
        return principal;
    }

    /**
     * Persists a rotated refresh token after an in-session token refresh, but only
     * for users who opted into "Keep me signed in" (rows only exist for them).
     */
    public void persistRotatedRefreshToken(FirebaseSessionPrincipal principal) {
        if (principal == null || principal.refreshToken() == null || principal.refreshToken().isBlank()) return;
        try {
            if (repository.existsById(principal.email())) remember(principal);
        } catch (DataAccessException exception) {
            log.warn("Could not update the remembered sign-in for uid={}: {}", principal.uid(),
                    exception.getMessage());
        }
    }

    private boolean isPermanentFailure(FirebaseIdentityException exception) {
        return "INVALID_REFRESH_TOKEN".equals(exception.code())
                || "USER_DISABLED".equals(exception.code())
                || "PROJECT_STATE_MISMATCH".equals(exception.code());
    }
}
