package com.example.accountservice.payment.application.command;

public record RequestRefundCommand(
        String paymentId, long amount, String reason, String requesterId) {}
