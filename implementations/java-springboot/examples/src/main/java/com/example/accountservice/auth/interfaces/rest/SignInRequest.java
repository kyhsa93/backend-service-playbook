package com.example.accountservice.auth.interfaces.rest;

import jakarta.validation.constraints.NotBlank;

public record SignInRequest(@NotBlank String userId, @NotBlank String password) {}
