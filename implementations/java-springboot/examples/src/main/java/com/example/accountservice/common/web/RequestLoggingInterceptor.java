package com.example.accountservice.common.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class RequestLoggingInterceptor implements HandlerInterceptor {
    private static final Logger log = LoggerFactory.getLogger(RequestLoggingInterceptor.class);
    private static final String START_TIME_ATTR = "startTime";

    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(START_TIME_ATTR, System.currentTimeMillis());
        return true;
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception ex) {
        long durationMs = System.currentTimeMillis() - (long) request.getAttribute(START_TIME_ATTR);
        log.info(
                "{} {} status={} duration_ms={}",
                request.getMethod(),
                request.getRequestURI(),
                response.getStatus(),
                durationMs);
    }
}
