package com.example.accountservice.payment.domain;

public class Refund {
    private String refundId;
    private String paymentId;
    private RefundStatus status;

    private Refund() {
    }

    public static Refund request(String paymentId, long amount, String reason) {
        return new Refund();
    }
}
