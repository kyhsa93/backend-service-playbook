package com.example.accountservice.payment.domain;

public record PaymentFindQuery(int page, int take, String paymentId, String ownerId) {}
