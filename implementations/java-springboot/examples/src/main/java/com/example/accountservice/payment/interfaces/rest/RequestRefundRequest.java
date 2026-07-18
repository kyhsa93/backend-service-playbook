package com.example.accountservice.payment.interfaces.rest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record RequestRefundRequest(@Positive long amount, @NotBlank String reason) {}
