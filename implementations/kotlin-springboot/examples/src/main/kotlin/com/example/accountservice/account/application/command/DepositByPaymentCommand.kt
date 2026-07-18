package com.example.accountservice.account.application.command

data class DepositByPaymentCommand(
    val accountId: String,
    val amount: Long,
    // Payment BC의 paymentId(결제취소 보상 크레딧) 또는 refundId(환불 승인 크레딧).
    // 멱등성 판단(Level 2 Ledger)의 키로 쓰인다.
    val referenceId: String,
)
