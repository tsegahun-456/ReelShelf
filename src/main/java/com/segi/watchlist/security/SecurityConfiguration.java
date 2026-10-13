package com.segi.watchlist.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfiguration {
    private final boolean publicLocalLibrary;

    public SecurityConfiguration(@Value("${library.public-preview:false}") boolean publicLocalLibrary) {
        this.publicLocalLibrary = publicLocalLibrary;
    }
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecurityFilterChain applicationSecurity(HttpSecurity http,
                                             AuthenticationProvider authenticationProvider) throws Exception {
        return http
                .authenticationProvider(authenticationProvider)
                .authorizeHttpRequests(authorize -> {
                    authorize.requestMatchers("/", "/search", "/movie/**", "/tv/**", "/login", "/signup", "/css/**", "/js/**", "/images/**", "/manifest.webmanifest", "/sw.js", "/offline.html", "/error", "/actuator/health").permitAll();
                    if (publicLocalLibrary) authorize.requestMatchers("/library/**").permitAll();
                    authorize.anyRequest().authenticated();
                })
                .formLogin(form -> form
                        .loginPage("/login")
                        .defaultSuccessUrl("/", true)
                        .permitAll())
                .logout(logout -> logout.logoutSuccessUrl("/"))
                .build();
    }
}
