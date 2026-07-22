package com.example.accountservice.account.interfaces.rest;

import org.springframework.http.HttpStatus;

/**
 * The standard error response format — includes all 4 fields ({@code statusCode}/{@code
 * code}/{@code message}/{@code error}) required by the root's error-handling.md.
 */
public record ErrorResponse(int statusCode, String code, String message, String error) {

    public static ErrorResponse of(HttpStatus status, String code, String message) {
        return new ErrorResponse(status.value(), code, message, status.getReasonPhrase());
    }
}
