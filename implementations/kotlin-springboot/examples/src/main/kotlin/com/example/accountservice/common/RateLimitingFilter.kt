package com.example.accountservice.common

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.resilience4j.ratelimiter.RateLimiterRegistry
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.annotation.Order
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Rate-limits requests at the same pipeline position (the servlet filter chain) as
 * cross-cutting-concerns.md's CorrelationIdFilter. Filtering happens before authentication/validation
 * so unnecessary downstream processing (DB lookups, starting a transaction) is avoided — see
 * rate-limiting.md.
 */
@Component
@Order(Int.MIN_VALUE + 1) // after CorrelationIdFilter, before the Spring Security filter chain (authentication)
class RateLimitingFilter(
    private val rateLimiterRegistry: RateLimiterRegistry,
    private val objectMapper: ObjectMapper,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (request.requestURI.startsWith("/actuator/health")) {
            filterChain.doFilter(request, response)
            return
        }

        val isRead = request.method == HttpMethod.GET.name() || request.method == HttpMethod.HEAD.name()
        val rateLimiter = rateLimiterRegistry.rateLimiter(if (isRead) "http-read" else "http-write")

        if (!rateLimiter.acquirePermission()) {
            response.status = 429
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            response.writer.write(
                objectMapper.writeValueAsString(
                    mapOf(
                        "statusCode" to 429,
                        "code" to "TOO_MANY_REQUESTS",
                        "message" to "Too many requests.",
                        "error" to "Too Many Requests",
                    ),
                ),
            )
            return
        }

        filterChain.doFilter(request, response)
    }
}
