package com.example.accountservice.card.interfaces.rest;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record IssueCardRequest(
        @Schema(
                        description = "The accountId to link the new card to.",
                        example = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4")
                @NotBlank
                String accountId,
        @Schema(description = "The card network/brand.", example = "VISA") @NotBlank
                String brand) {}
