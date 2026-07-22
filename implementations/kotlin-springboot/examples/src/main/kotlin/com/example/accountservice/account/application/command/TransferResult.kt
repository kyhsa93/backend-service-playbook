package com.example.accountservice.account.application.command

import io.swagger.v3.oas.annotations.media.Schema

data class TransferResult(
    @field:Schema(
        description = "Correlates the source/target transactions from this transfer. Not a persistent Aggregate ID.",
        example = "c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6",
    )
    val transferId: String,
    @field:Schema(description = "The WITHDRAWAL transaction recorded on the source account.")
    val sourceTransaction: TransactionResult,
    @field:Schema(description = "The DEPOSIT transaction recorded on the target account.")
    val targetTransaction: TransactionResult,
)
