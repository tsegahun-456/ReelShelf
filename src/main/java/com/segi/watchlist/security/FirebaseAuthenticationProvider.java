package com.segi.watchlist.security;

import com.segi.watchlist.account.AccountIdentityConflictException;
import com.segi.watchlist.account.AccountService;
import com.segi.watchlist.cloud.FirebaseIdentityClient;
import com.segi.watchlist.cloud.FirebaseIdentityException;
import com.segi.watchlist.cloud.FirebaseProperties;
import com.segi.watchlist.cloud.FirebaseSessionPrincipal;
import com.segi.watchlist.cloud.FirebaseTokens;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

@Component
public class FirebaseAuthenticationProvider implements AuthenticationProvider {
    private final AccountService accounts;
    private final FirebaseIdentityClient firebase;
    private final FirebaseProperties properties;
    private final DaoAuthenticationProvider localProvider;

    public FirebaseAuthenticationProvider(AccountService accounts, PasswordEncoder passwordEncoder,
                                          FirebaseIdentityClient firebase, FirebaseProperties properties) {
        this.accounts = accounts;
        this.firebase = firebase;
        this.properties = properties;
        this.localProvider = new DaoAuthenticationProvider(accounts);
        this.localProvider.setPasswordEncoder(passwordEncoder);
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        if (!properties.firestoreConfigured()) return localProvider.authenticate(authentication);

        String email = authentication.getName().trim();
        String password = String.valueOf(authentication.getCredentials());
        FirebaseTokens tokens;
        try {
            try {
                tokens = firebase.signIn(email, password);
            } catch (FirebaseIdentityException signInError) {
                if (!isLegacyMigrationCase(signInError) || !accounts.legacyCredentialsMatch(email, password)) {
                    throw new BadCredentialsException("Email or password was not accepted.");
                }
                try {
                    String displayName = accounts.getRequiredByEmail(email).getDisplayName();
                    tokens = firebase.signUp(email, password, displayName);
                } catch (FirebaseIdentityException alreadyCreatedOrUnavailable) {
                    if (!"EMAIL_EXISTS".equals(alreadyCreatedOrUnavailable.code())) {
                        throw new BadCredentialsException("Could not link the existing local account to cloud sign-in.");
                    }
                    try {
                        tokens = firebase.signIn(email, password);
                    } catch (FirebaseIdentityException retryError) {
                        throw new BadCredentialsException("Email or password was not accepted.");
                    }
                }
            }
        } catch (RestClientException exception) {
            throw new AuthenticationServiceException("Firebase sign-in is unavailable. Try again when online.");
        }

        String resolvedEmail = tokens.email().isBlank() ? email : tokens.email();
        try {
            accounts.linkFirebaseIdentity(resolvedEmail, tokens.displayName(), tokens.uid());
        } catch (AccountIdentityConflictException exception) {
            throw new BadCredentialsException("This email is linked to a different account.");
        }
        FirebaseSessionPrincipal principal = new FirebaseSessionPrincipal(tokens);
        return UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities());
    }

    private boolean isLegacyMigrationCase(FirebaseIdentityException exception) {
        return "EMAIL_NOT_FOUND".equals(exception.code())
                || "INVALID_LOGIN_CREDENTIALS".equals(exception.code());
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
