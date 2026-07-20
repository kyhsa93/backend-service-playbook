package com.example.accountservice.payment.domain;

public class Refund {
    private String refundId;
    private String paymentId;

    public static Refund request(String paymentId) {
        Refund refund = new Refund();
        refund.paymentId = paymentId;
        return refund;
    }
}
