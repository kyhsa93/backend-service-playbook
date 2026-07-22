package com.example.accountservice.auth.interfaces.rest;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record SignInRequest(
        @Schema(description = "The user's login ID.", example = "jane.doe") @NotBlank String userId,
        @Schema(description = "The user's password.", example = "hunter2-password") @NotBlank
                String password) {}
