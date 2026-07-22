package com.example.accountservice.account.application.command;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

public record TransactionResult(
        @Schema(description = "The generated transaction ID.") String transactionId,
        @Schema(description = "The accountId this transaction belongs to.") String accountId,
        @Schema(description = "The transaction type.", example = "DEPOSIT") String type,
        @Schema(description = "The transaction amount.") MoneyResult amount,
        @Schema(description = "When the transaction was recorded.") LocalDateTime createdAt) {
    public record MoneyResult(
            @Schema(description = "The amount, in the smallest unit of the currency.") long amount,
            @Schema(description = "The ISO 4217 currency code.", example = "USD")
                    String currency) {}
}
