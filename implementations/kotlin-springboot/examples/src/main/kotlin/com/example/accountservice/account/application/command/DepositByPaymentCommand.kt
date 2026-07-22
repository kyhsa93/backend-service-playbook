package com.example.accountservice.account.application.command

data class DepositByPaymentCommand(
    val accountId: String,
    val amount: Long,
    // The Payment BC's paymentId (cancellation compensation credit) or refundId (refund-approval
    // credit). Used as the key for idempotency checks (Level 2 Ledger).
    val referenceId: String,
)
