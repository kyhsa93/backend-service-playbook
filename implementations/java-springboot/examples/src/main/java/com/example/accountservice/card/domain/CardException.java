package com.example.accountservice.card.domain;

public class CardException extends RuntimeException {

    public enum ErrorCode {
        CARD_NOT_FOUND,
        LINKED_ACCOUNT_NOT_FOUND,
        CARD_ISSUE_REQUIRES_ACTIVE_ACCOUNT,
        CANCELLED_CARD_CANNOT_BE_SUSPENDED,
        CARD_ALREADY_SUSPENDED,
        CARD_ALREADY_CANCELLED
    }

    private final ErrorCode code;

    public CardException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
