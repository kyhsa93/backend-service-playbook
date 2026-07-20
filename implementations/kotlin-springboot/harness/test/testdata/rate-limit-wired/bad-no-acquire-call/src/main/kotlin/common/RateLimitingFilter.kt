package com.example.accountservice.common

import io.github.resilience4j.ratelimiter.RateLimiterRegistry
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

// RateLimiterRegistry를 주입만 받고 실제로 permission을 확인하는 호출이 없다 — 아무것도 제한하지 않는 스텁.
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
