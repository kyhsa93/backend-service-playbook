package com.example.accountservice.account.application.query;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

public record GetAccountResult(
        @Schema(description = "The account ID.") String accountId,
        @Schema(description = "The userId of the account's owner.") String ownerId,
        @Schema(description = "The account owner's email address.") String email,
        @Schema(description = "The account's current balance.") MoneyResult balance,
        @Schema(description = "The account's status.", example = "ACTIVE") String status,
        @Schema(description = "When the account was created.") LocalDateTime createdAt,
        @Schema(description = "When the account was last updated.") LocalDateTime updatedAt) {
    public record MoneyResult(
            @Schema(description = "The amount, in the smallest unit of the currency.") long amount,
            @Schema(description = "The ISO 4217 currency code.", example = "USD")
                    String currency) {}
}
