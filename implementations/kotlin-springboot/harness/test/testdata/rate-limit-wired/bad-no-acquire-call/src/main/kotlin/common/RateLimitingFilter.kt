package com.example.accountservice.common

import io.github.resilience4j.ratelimiter.RateLimiterRegistry
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

// Only injects RateLimiterRegistry with no actual call checking permission — a stub that limits nothing.
@Component
class RateLimitingFilter(
    private val rateLimiterRegistry: RateLimiterRegistry,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        filterChain.doFilter(request, response)
    }
}
