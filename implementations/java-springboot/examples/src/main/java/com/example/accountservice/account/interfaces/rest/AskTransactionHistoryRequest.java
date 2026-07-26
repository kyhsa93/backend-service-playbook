package com.example.accountservice.account.interfaces.rest;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AskTransactionHistoryRequest(
        @Schema(
                        description =
                                "A free-text question about this account's transaction"
                                        + " history.",
                        example = "How much did I deposit this month?")
                @NotBlank
                @Size(max = 500)
                String question) {}
