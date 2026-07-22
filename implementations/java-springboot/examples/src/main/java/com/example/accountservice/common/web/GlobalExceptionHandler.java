package com.example.accountservice.common.web;

import com.example.accountservice.account.interfaces.rest.ErrorResponse;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Handles common exceptions unrelated to any particular Controller, globally — see
 * rate-limiting.md. Domain-specific exceptions (AccountException, etc.) are left to each
 * Controller.
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
                                "Too many requests."));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        List<String> messages =
                e.getBindingResult().getFieldErrors().stream()
                        .map(err -> err.getField() + " " + err.getDefaultMessage())
                        .toList();
        return ResponseEntity.badRequest()
                .body(
                        new ErrorResponse(
                                400,
                                "VALIDATION_FAILED",
                                String.join(", ", messages),
                                "Bad Request"));
    }
}
