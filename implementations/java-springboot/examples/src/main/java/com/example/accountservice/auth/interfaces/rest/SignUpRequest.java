package com.example.accountservice.auth.interfaces.rest;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SignUpRequest(
        @Schema(
                        description = "The desired login ID. Must not already be in use.",
                        example = "jane.doe")
                @NotBlank
                String userId,
        @Schema(
                        description = "The desired password. Must be at least 8 characters.",
                        example = "hunter2-password")
                @NotBlank
                @Size(min = 8)
                String password) {}
