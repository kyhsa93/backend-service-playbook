package com.example.accountservice.auth.interfaces.rest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SignUpRequest(@NotBlank String userId, @NotBlank @Size(min = 8) String password) {}
