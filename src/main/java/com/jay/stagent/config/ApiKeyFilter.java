package com.jay.stagent.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * API Key authentication filter.
 *
 * Protects all /api/* endpoints with a shared secret passed via the
 * X-API-Key request header.  UI pages and the H2 console are excluded.
 *
 * Set API_KEY in your .env to enable.  If API_KEY is blank (default for
 * local development), the filter is a no-op and all requests pass through.
 *
 * Usage:
 *   curl -H "X-API-Key: your-secret" http://host:8080/api/status
 */
@Slf4j
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    @Value("${API_KEY:}")
    private String expectedKey;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain chain) throws ServletException, IOException {

        String path = request.getRequestURI();

        // Only guard /api/* — let UI, H2 console, and favicon through
        if (!path.startsWith("/api/")) {
            chain.doFilter(request, response);
            return;
        }

        // If no API_KEY is configured, skip auth (local dev / first boot)
        if (expectedKey == null || expectedKey.isBlank()) {
            chain.doFilter(request, response);
            return;
        }

        String key = request.getHeader("X-API-Key");
        if (!expectedKey.equals(key)) {
            log.warn("Rejected unauthenticated request to {} from {}", path, request.getRemoteAddr());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Unauthorized\",\"message\":\"Missing or invalid X-API-Key header\"}");
            return;
        }

        chain.doFilter(request, response);
    }
}
