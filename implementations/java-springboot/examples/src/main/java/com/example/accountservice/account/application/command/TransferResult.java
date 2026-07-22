package com.example.accountservice.account.application.command;

import io.swagger.v3.oas.annotations.media.Schema;

public record TransferResult(
        @Schema(
                        description =
                                "Correlates the source and target transactions produced by this transfer.")
                String transferId,
        @Schema(description = "The WITHDRAWAL transaction recorded on the source account.")
                TransactionResult sourceTransaction,
        @Schema(description = "The DEPOSIT transaction recorded on the target account.")
                TransactionResult targetTransaction) {}
