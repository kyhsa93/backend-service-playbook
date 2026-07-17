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
 * 전역 요청 속도 제한 — rate-limiting.md 참고. SecurityFilterChain(인증)보다 먼저 실행되어야 인증되지 않은 요청의 무한 재시도를 막을 수
 * 있다. 쓰기(POST/PUT/PATCH/DELETE)/읽기(GET/HEAD) 메서드별로 {@code resilience4j.ratelimiter.instances}에 정의된
 * named instance(`http-write`/`http-read`)를 {@link RateLimiterRegistry}에서 조회한다 — 값은
 * application.yml/환경 변수로 배포 시점에 조정 가능하다(kotlin-springboot의 RateLimitingFilter와 동일한 방식).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10) // CorrelationIdFilter 다음, SecurityFilterChain보다 먼저
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiterRegistry rateLimiterRegistry;

    public RateLimitFilter(RateLimiterRegistry rateLimiterRegistry) {
        this.rateLimiterRegistry = rateLimiterRegistry;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 헬스체크/Actuator 엔드포인트는 rate limiting 대상에서 제외
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
                            "{\"statusCode\":429,\"code\":\"RATE_LIMIT_EXCEEDED\",\"message\":\"요청이 너무 많습니다.\",\"error\":\"Too Many Requests\"}");
        }
    }
}
