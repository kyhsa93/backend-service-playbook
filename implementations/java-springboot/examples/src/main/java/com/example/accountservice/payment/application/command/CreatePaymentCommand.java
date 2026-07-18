package com.example.accountservice.payment.application.command;

public record CreatePaymentCommand(String cardId, long amount, String requesterId) {}
