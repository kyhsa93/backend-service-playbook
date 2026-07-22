package com.example.accountservice.account.application.command;

// Payment BC's paymentId (payment-cancellation compensating credit) or refundId (refund-approval
// credit). Used as the key for the idempotency check (Level 2 Ledger).
public record DepositByPaymentCommand(String accountId, long amount, String referenceId) {}
