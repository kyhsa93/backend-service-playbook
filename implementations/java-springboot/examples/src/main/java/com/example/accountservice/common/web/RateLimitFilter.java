package com.example.accountservice.common.web;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 클라이언트(IP)별 전역 요청 속도 제한 — rate-limiting.md 참고.
 * SecurityFilterChain(인증)보다 먼저 실행되어야 인증되지 않은 요청의 무한 재시도를 막을 수 있다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)   // CorrelationIdFilter 다음, SecurityFilterChain보다 먼저
public class RateLimitFilter extends OncePerRequestFilter {

    // 클라이언트(IP 또는 인증 전 식별자)별 독립 RateLimiter — 인메모리, 단일 인스턴스 전제
    private final ConcurrentHashMap<String, RateLimiter> limiters = new ConcurrentHashMap<>();

    private final RateLimiterConfig config = RateLimiterConfig.custom()
            .limitForPeriod(100)                        // 주기당 허용 요청 수
            .limitRefreshPeriod(Duration.ofMinutes(1))   // 주기 길이
            .timeoutDuration(Duration.ZERO)              // 대기 없이 즉시 거부
            .build();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 헬스체크/Actuator 엔드포인트는 rate limiting 대상에서 제외
        return request.getRequestURI().startsWith("/actuator/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws java.io.IOException, jakarta.servlet.ServletException {

        String key = request.getRemoteAddr();   // 실제로는 X-Forwarded-For 등 프록시 환경 고려 필요
        RateLimiter limiter = limiters.computeIfAbsent(key, k -> RateLimiter.of(k, config));

        if (limiter.acquirePermission()) {
            chain.doFilter(request, response);
        } else {
            response.setStatus(429);
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"statusCode\":429,\"code\":\"RATE_LIMIT_EXCEEDED\",\"message\":\"요청이 너무 많습니다.\",\"error\":\"Too Many Requests\"}"
            );
        }
    }
}
