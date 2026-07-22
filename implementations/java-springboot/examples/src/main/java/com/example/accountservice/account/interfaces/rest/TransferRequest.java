package com.example.accountservice.account.interfaces.rest;

import io.swagger.v3.oas.annotations.media.Schema;

public record TransferRequest(
        @Schema(
                        description = "The accountId of the account receiving the funds.",
                        example = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4")
                String targetAccountId,
        @Schema(
                        description = "The amount to transfer. Must be a positive integer.",
                        example = "2500")
                long amount) {}
