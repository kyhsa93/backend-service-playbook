package com.example.accountservice.account.interfaces.rest;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

public record WithdrawRequest(
        @Schema(
                        description = "The amount to withdraw. Must be a positive integer.",
                        example = "5000")
                long amount,
        @Schema(
                        description =
                                "The payee/merchant this withdrawal is for, e.g. for spending"
                                        + " categorization. Optional.",
                        example = "Starbucks Gangnam")
                @Size(min = 1)
                String merchantName) {}
