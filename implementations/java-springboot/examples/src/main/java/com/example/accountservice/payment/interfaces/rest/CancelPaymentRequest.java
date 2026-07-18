package com.example.accountservice.payment.interfaces.rest;

import jakarta.validation.constraints.NotBlank;

public record CancelPaymentRequest(@NotBlank String reason) {}
