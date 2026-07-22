package com.example.accountservice.account.interfaces.rest;

import io.swagger.v3.oas.annotations.media.Schema;

public record DepositRequest(
        @Schema(
                        description = "The amount to deposit. Must be a positive integer.",
                        example = "10000")
                long amount) {}
