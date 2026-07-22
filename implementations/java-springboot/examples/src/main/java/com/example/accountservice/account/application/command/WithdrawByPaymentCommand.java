package com.example.accountservice.account.application.command;

// The paymentId from the Payment BC. Used as the key for idempotency checks (Level 2 Ledger).
public record WithdrawByPaymentCommand(String accountId, long amount, String referenceId) {}
