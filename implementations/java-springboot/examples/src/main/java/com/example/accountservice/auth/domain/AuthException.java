package com.example.accountservice.auth.domain;

public class AuthException extends RuntimeException {

    public enum ErrorCode {
        INVALID_CREDENTIALS,
        USER_ID_ALREADY_EXISTS
    }

    private final ErrorCode code;

    public AuthException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
