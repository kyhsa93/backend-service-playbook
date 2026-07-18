package com.example.accountservice.payment.application.command;

public record CancelPaymentCommand(String paymentId, String reason, String requesterId) {}
