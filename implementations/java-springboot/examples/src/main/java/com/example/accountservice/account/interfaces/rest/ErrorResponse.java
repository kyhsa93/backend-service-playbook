package com.example.accountservice.account.interfaces.rest;

import org.springframework.http.HttpStatus;

/**
 * 표준 에러 응답 형식 — root의 error-handling.md가 요구하는 4필드 ({@code statusCode}/{@code code}/{@code
 * message}/{@code error})를 모두 포함한다.
 */
public record ErrorResponse(int statusCode, String code, String message, String error) {

    public static ErrorResponse of(HttpStatus status, String code, String message) {
        return new ErrorResponse(status.value(), code, message, status.getReasonPhrase());
    }
}
