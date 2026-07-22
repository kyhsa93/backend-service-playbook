package com.example.accountservice.common.web;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Global request rate limiting — see rate-limiting.md. It must run before SecurityFilterChain
 * (authentication) to prevent unlimited retries of unauthenticated requests. It looks up the named
 * instance (`http-write`/`http-read`) defined under {@code resilience4j.ratelimiter.instances} in
 * the {@link RateLimiterRegistry}, keyed by whether the method is a write (POST/PUT/PATCH/DELETE)
 * or a read (GET/HEAD) — values can be tuned at deploy time via application.yml/env vars (the same
 * approach as kotlin-springboot's RateLimitingFilter).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10) // After CorrelationIdFilter, before SecurityFilterChain
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiterRegistry rateLimiterRegistry;

    public RateLimitFilter(RateLimiterRegistry rateLimiterRegistry) {
        this.rateLimiterRegistry = rateLimiterRegistry;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Exclude health-check/Actuator endpoints from rate limiting
        return request.getRequestURI().startsWith("/actuator/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws java.io.IOException, jakarta.servlet.ServletException {

        boolean isRead =
                HttpMethod.GET.matches(request.getMethod())
                        || HttpMethod.HEAD.matches(request.getMethod());
        RateLimiter limiter = rateLimiterRegistry.rateLimiter(isRead ? "http-read" : "http-write");

        if (limiter.acquirePermission()) {
            chain.doFilter(request, response);
        } else {
            response.setStatus(429);
            response.setContentType("application/json");
            response.getWriter()
                    .write(
                            "{\"statusCode\":429,\"code\":\"RATE_LIMIT_EXCEEDED\",\"message\":\"Too many requests.\",\"error\":\"Too Many Requests\"}");
        }
    }
}
