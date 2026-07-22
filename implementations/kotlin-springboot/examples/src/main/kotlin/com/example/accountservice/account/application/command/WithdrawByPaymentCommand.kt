package com.example.accountservice.account.application.command

data class WithdrawByPaymentCommand(
    val accountId: String,
    val amount: Long,
    // The Payment BC's paymentId. Used as the key for idempotency checks (Level 2 Ledger).
    val referenceId: String,
)
