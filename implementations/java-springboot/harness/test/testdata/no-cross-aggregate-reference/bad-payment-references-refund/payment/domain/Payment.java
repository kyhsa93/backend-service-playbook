package com.example.accountservice.payment.domain;

import java.util.List;

public class Payment {
    private String paymentId;
    private List<Refund> refunds;

    private Payment() {
    }

    public static Payment create(String cardId, String accountId, long amount) {
        return new Payment();
    }
}
