package com.example.accountservice.common

import io.github.resilience4j.ratelimiter.RateLimiterRegistry
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter

// @Component 없음 — Spring이 이 클래스를 빈으로 등록하지 않으므로 필터 체인에 절대 들어가지 않는다.
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
