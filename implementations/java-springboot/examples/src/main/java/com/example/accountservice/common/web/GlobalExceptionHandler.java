package com.example.accountservice.common.web;

import com.example.accountservice.account.interfaces.rest.ErrorResponse;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Controller에 무관한 공통 예외를 전역으로 처리한다 — rate-limiting.md 참고. 도메인 특화 예외(AccountException 등)는 각
 * Controller에 남긴다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(RequestNotPermitted.class)
    public ResponseEntity<ErrorResponse> handleRateLimit(RequestNotPermitted e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(
                        ErrorResponse.of(
                                HttpStatus.TOO_MANY_REQUESTS,
                                "RATE_LIMIT_EXCEEDED",
                                "요청이 너무 많습니다."));
    }
}
