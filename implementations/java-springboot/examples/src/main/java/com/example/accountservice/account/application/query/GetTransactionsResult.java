package com.example.accountservice.account.application.query;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;

public record GetTransactionsResult(
        @Schema(description = "The account's transactions, newest first.")
                List<TransactionSummary> transactions,
        @Schema(
                        description =
                                "The total number of transactions for this account (not just the current page).")
                long count) {
    public record TransactionSummary(
            @Schema(description = "The transaction ID.") String transactionId,
            @Schema(description = "The transaction type.", example = "DEPOSIT") String type,
            @Schema(description = "The transaction amount.") MoneyResult amount,
            @Schema(description = "When the transaction was recorded.") LocalDateTime createdAt) {}

    public record MoneyResult(
            @Schema(description = "The amount, in the smallest unit of the currency.") long amount,
            @Schema(description = "The ISO 4217 currency code.", example = "USD")
                    String currency) {}
}
