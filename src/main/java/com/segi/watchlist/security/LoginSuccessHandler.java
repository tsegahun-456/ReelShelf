package com.segi.watchlist.security;

import com.segi.watchlist.cloud.FirebaseCredentialService;
import com.segi.watchlist.cloud.FirebaseSessionPrincipal;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.rememberme.PersistentTokenRepository;

/**
 * Completes a successful sign-in like the previous default redirect, and stores
 * (or clears) the remembered sign-in depending on the "Keep me signed in" box.
 */
public class LoginSuccessHandler extends SavedRequestAwareAuthenticationSuccessHandler {
    private static final Logger log = LoggerFactory.getLogger(LoginSuccessHandler.class);
    static final String REMEMBER_ME_PARAMETER = "remember-me";

    private final FirebaseCredentialService credentials;
    private final PersistentTokenRepository persistentTokens;

    public LoginSuccessHandler(FirebaseCredentialService credentials, PersistentTokenRepository persistentTokens) {
        this.credentials = credentials;
        this.persistentTokens = persistentTokens;
        setDefaultTargetUrl("/");
        setAlwaysUseDefaultTargetUrl(true);
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {
        if (rememberMeRequested(request)) {
            if (authentication.getPrincipal() instanceof FirebaseSessionPrincipal principal) {
                credentials.remember(principal);
                log.info("Sign-in will be remembered on this computer for uid={}.", principal.uid());
            }
        } else {
            String username = authentication.getName();
            credentials.forgetQuietly(username);
            persistentTokens.removeUserTokens(username);
        }
        super.onAuthenticationSuccess(request, response, authentication);
    }

    private boolean rememberMeRequested(HttpServletRequest request) {
        String[] values = request.getParameterValues(REMEMBER_ME_PARAMETER);
        return values != null && Arrays.stream(values)
                .anyMatch(value -> "true".equalsIgnoreCase(value.trim())
                        || "on".equalsIgnoreCase(value.trim())
                        || "yes".equalsIgnoreCase(value.trim()));
    }
}
