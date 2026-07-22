package com.example.accountservice.account.interfaces.rest;

import io.swagger.v3.oas.annotations.media.Schema;

public record WithdrawRequest(
        @Schema(
                        description = "The amount to withdraw. Must be a positive integer.",
                        example = "5000")
                long amount) {}
