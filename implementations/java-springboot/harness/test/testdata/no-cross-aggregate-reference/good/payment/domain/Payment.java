package com.example.accountservice.payment.domain;

public class Payment {
    private String paymentId;
    private String cardId;
    private String accountId;
    private PaymentStatus status;

    private Payment() {
    }

    public static Payment create(String cardId, String accountId, long amount) {
        return new Payment();
    }
}
