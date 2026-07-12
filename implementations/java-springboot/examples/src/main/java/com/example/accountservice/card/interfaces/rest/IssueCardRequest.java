package com.example.accountservice.card.interfaces.rest;

import jakarta.validation.constraints.NotBlank;

public record IssueCardRequest(
        @NotBlank String accountId,
        @NotBlank String brand
) {}
