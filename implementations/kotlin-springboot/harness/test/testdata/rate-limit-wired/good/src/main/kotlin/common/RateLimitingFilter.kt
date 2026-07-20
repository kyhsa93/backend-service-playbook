package com.example.accountservice.common

import io.github.resilience4j.ratelimiter.RateLimiterRegistry
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class RateLimitingFilter(
    private val rateLimiterRegistry: RateLimiterRegistry,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val rateLimiter = rateLimiterRegistry.rateLimiter("http-write")
        if (!rateLimiter.acquirePermission()) {
            response.status = 429
            return
        }
        filterChain.doFilter(request, response)
    }
}
