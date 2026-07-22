package com.example.accountservice.account.interfaces.rest;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record CreateAccountRequest(
        @Schema(
                        description = "The ISO 4217 currency code the account is opened in.",
                        example = "USD")
                @NotBlank
                String currency,
        @Schema(description = "The account owner's email address.", example = "user@example.com")
                @NotBlank
                @Email
                String email) {}
