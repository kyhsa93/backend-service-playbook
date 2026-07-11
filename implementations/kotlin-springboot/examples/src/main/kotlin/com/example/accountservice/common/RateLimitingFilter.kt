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
 * cross-cutting-concerns.md의 CorrelationIdFilter와 동일한 파이프라인 위치(서블릿 필터 체인)에서
 * 요청 속도를 제한한다. 인증/검증보다 앞서 걸러내 불필요한 다운스트림 처리(DB 조회, 트랜잭션 시작)를
 * 막는다 — rate-limiting.md 참조.
 */
@Component
@Order(Int.MIN_VALUE + 1) // CorrelationIdFilter 다음, Spring Security 필터 체인(인증)보다 먼저
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
                        "message" to "요청이 너무 많습니다.",
                        "error" to "Too Many Requests",
                    ),
                ),
            )
            return
        }

        filterChain.doFilter(request, response)
    }
}
