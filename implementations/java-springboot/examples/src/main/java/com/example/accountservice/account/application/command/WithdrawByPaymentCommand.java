package com.example.accountservice.account.application.command;

// Payment BC의 paymentId. 멱등성 판단(Level 2 Ledger)의 키로 쓰인다.
public record WithdrawByPaymentCommand(String accountId, long amount, String referenceId) {}
