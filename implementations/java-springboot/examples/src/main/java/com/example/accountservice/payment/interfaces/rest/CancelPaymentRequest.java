package com.example.accountservice.payment.interfaces.rest;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record CancelPaymentRequest(
        @Schema(
                        description = "Why the payment is being cancelled.",
                        example = "Customer requested cancellation.")
                @NotBlank
                String reason) {}
