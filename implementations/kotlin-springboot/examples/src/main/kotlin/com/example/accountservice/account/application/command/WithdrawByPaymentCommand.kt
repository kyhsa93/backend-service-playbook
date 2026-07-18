package com.example.accountservice.account.application.command

data class WithdrawByPaymentCommand(
    val accountId: String,
    val amount: Long,
    // Payment BC의 paymentId. 멱등성 판단(Level 2 Ledger)의 키로 쓰인다.
    val referenceId: String,
)
