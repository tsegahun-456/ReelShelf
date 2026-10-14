package com.segi.watchlist.security;

import com.segi.watchlist.cloud.FirebaseCredentialService;
import com.segi.watchlist.cloud.FirebaseSessionPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;

/**
 * Builds a live {@link FirebaseSessionPrincipal} from the remembered sign-in
 * (used by the remember-me filter on visits after a restart or session expiry).
 */
@Component
public class RememberMeUserDetailsService implements UserDetailsService {
    private static final Logger log = LoggerFactory.getLogger(RememberMeUserDetailsService.class);

    private final FirebaseCredentialService credentials;

    public RememberMeUserDetailsService(FirebaseCredentialService credentials) {
        this.credentials = credentials;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        try {
            return credentials.restorePrincipal(username);
        } catch (AuthenticationException exception) {
            log.debug("Remember-me sign-in not restored for this visitor.");
            throw exception;
        }
    }
}
