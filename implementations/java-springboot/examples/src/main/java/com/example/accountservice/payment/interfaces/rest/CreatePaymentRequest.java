package com.example.accountservice.payment.interfaces.rest;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record CreatePaymentRequest(
        @Schema(description = "The cardId to charge.", example = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4")
                @NotBlank
                String cardId,
        @Schema(
                        description = "The amount to charge. Must be a positive integer.",
                        example = "15000")
                @Positive
                long amount) {}
