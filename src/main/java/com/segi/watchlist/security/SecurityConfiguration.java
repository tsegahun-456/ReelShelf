package com.segi.watchlist.security;

import com.segi.watchlist.cloud.FirebaseCredentialService;
import com.segi.watchlist.cloud.FirebaseSessionPrincipal;
import com.segi.watchlist.cloud.PersistentSignInKey;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.rememberme.JdbcTokenRepositoryImpl;
import org.springframework.security.web.authentication.rememberme.PersistentTokenRepository;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

@Configuration
public class SecurityConfiguration {
    /** Cookie name used by Spring Security's persistent remember-me services. */
    static final String REMEMBER_ME_COOKIE = "remember-me";
    private static final String LEGACY_REMEMBER_ME_COOKIE = "REMEMBERME";

    private final boolean publicLocalLibrary;
    private final int rememberMeValiditySeconds;

    public SecurityConfiguration(@Value("${library.public-preview:false}") boolean publicLocalLibrary,
                                 @Value("${security.remember-me.validity-seconds:2592000}") int rememberMeValiditySeconds) {
        this.publicLocalLibrary = publicLocalLibrary;
        this.rememberMeValiditySeconds = rememberMeValiditySeconds;
    }
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    FilterRegistrationBean<NoStoreHtmlFilter> noStoreHtmlFilter() {
        FilterRegistrationBean<NoStoreHtmlFilter> registration = new FilterRegistrationBean<>(new NoStoreHtmlFilter());
        registration.addUrlPatterns("/*");
        return registration;
    }

    @Bean
    PersistentTokenRepository persistentTokenRepository(DataSource dataSource) {
        JdbcTokenRepositoryImpl repository = new JdbcTokenRepositoryImpl();
        repository.setDataSource(dataSource);
        // The table is created by Flyway (V7) or the portable schema script, not by Spring.
        repository.setCreateTableOnStartup(false);
        return repository;
    }

    @Bean
    SecurityFilterChain applicationSecurity(HttpSecurity http,
                                             AuthenticationProvider authenticationProvider,
                                             PersistentTokenRepository persistentTokenRepository,
                                             PersistentSignInKey persistentSignInKey,
                                             RememberMeUserDetailsService rememberMeUserDetailsService,
                                             FirebaseCredentialService credentials) throws Exception {
        return http
                .authenticationProvider(authenticationProvider)
                .authorizeHttpRequests(authorize -> {
                    authorize.requestMatchers("/", "/search", "/movie/**", "/tv/**", "/online", "/login", "/signup", "/css/**", "/js/**", "/images/**", "/fonts/**", "/manifest.webmanifest", "/sw.js", "/offline.html", "/error", "/actuator/health").permitAll();
                    if (publicLocalLibrary) authorize.requestMatchers(
                            "/library/**", "/settings", "/settings/**", "/api/settings/update/**").permitAll();
                    authorize.anyRequest().authenticated();
                })
                .formLogin(form -> form
                        .loginPage("/login")
                        .successHandler(new LoginSuccessHandler(credentials, persistentTokenRepository))
                        .permitAll())
                // Opt-in "Keep me signed in": a persistent cookie backed by the database,
                // restoring the Firebase session (via the encrypted refresh token) after restarts.
                .rememberMe(remember -> remember
                        .key(persistentSignInKey.hmacKey())
                        .rememberMeParameter(LoginSuccessHandler.REMEMBER_ME_PARAMETER)
                        .rememberMeCookieName(REMEMBER_ME_COOKIE)
                        .tokenRepository(persistentTokenRepository)
                        .userDetailsService(rememberMeUserDetailsService)
                        .tokenValiditySeconds(rememberMeValiditySeconds)
                        .useSecureCookie(false))
                .logout(logout -> logout
                        .logoutSuccessUrl("/")
                        .deleteCookies(REMEMBER_ME_COOKIE, LEGACY_REMEMBER_ME_COOKIE)
                        .addLogoutHandler((request, response, authentication) -> {
                            if (authentication != null
                                    && authentication.getPrincipal() instanceof FirebaseSessionPrincipal principal) {
                                credentials.forgetQuietly(principal.email());
                                persistentTokenRepository.removeUserTokens(principal.email());
                            }
                        }))
                .build();
    }
}
