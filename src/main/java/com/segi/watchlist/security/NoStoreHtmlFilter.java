package com.segi.watchlist.security;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpHeaders;

/**
 * Marks HTML responses as non-cacheable. After an app restart the browser must
 * fetch pages again: a cached sign-in form carries a dead CSRF token and the
 * retry fails with a confusing 403.
 */
public class NoStoreHtmlFilter implements Filter {
    private static final Set<String> STATIC_PREFIXES = Set.of("/css/", "/js/", "/images/", "/offline.html");

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request instanceof HttpServletRequest httpRequest
                && response instanceof HttpServletResponse httpResponse) {
            String path = httpRequest.getRequestURI().substring(httpRequest.getContextPath().length());
            boolean wantsHtml = false;
            for (String value : java.util.Collections.list(httpRequest.getHeaders("Accept"))) {
                if (value.toLowerCase(Locale.ROOT).contains("text/html")) { wantsHtml = true; break; }
            }
            if (wantsHtml && STATIC_PREFIXES.stream().noneMatch(path::startsWith)) {
                httpResponse.setHeader(HttpHeaders.CACHE_CONTROL, "no-store, private");
                httpResponse.setHeader("Pragma", "no-cache");
            }
        }
        chain.doFilter(request, response);
    }
}
