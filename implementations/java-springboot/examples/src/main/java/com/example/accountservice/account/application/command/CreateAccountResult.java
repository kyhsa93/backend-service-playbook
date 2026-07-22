package com.example.accountservice.account.application.command;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

public record CreateAccountResult(
        @Schema(description = "The generated account ID.") String accountId,
        @Schema(description = "The userId of the account's owner.") String ownerId,
        @Schema(description = "The account owner's email address.") String email,
        @Schema(description = "The account's current balance.") MoneyResult balance,
        @Schema(description = "The account's status.", example = "ACTIVE") String status,
        @Schema(description = "When the account was created.") LocalDateTime createdAt) {
    public record MoneyResult(
            @Schema(description = "The amount, in the smallest unit of the currency.") long amount,
            @Schema(description = "The ISO 4217 currency code.", example = "USD")
                    String currency) {}
}
