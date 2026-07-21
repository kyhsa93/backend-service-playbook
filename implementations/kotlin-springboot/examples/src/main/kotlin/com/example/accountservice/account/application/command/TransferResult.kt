package com.example.accountservice.account.application.command

data class TransferResult(
    val transferId: String,
    val sourceTransaction: TransactionResult,
    val targetTransaction: TransactionResult,
)
