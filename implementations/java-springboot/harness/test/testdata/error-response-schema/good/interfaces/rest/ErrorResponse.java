package com.example.accountservice.interfaces.rest;

import org.springframework.http.HttpStatus;

public record ErrorResponse(int statusCode, String code, String message, String error) {

    public static ErrorResponse of(HttpStatus status, String code, String message) {
        return new ErrorResponse(status.value(), code, message, status.getReasonPhrase());
    }
}
