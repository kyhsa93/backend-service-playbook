package com.example.accountservice.payment.interfaces.rest;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record RequestRefundRequest(
        @Schema(
                        description =
                                "The amount to refund. Cannot exceed the original payment amount.",
                        example = "15000")
                @Positive
                long amount,
        @Schema(description = "Why the refund is being requested.", example = "Item was defective.")
                @NotBlank
                String reason) {}
