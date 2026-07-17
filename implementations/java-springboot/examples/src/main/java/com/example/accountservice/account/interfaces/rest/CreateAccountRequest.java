package com.example.accountservice.account.interfaces.rest;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record CreateAccountRequest(@NotBlank String currency, @NotBlank @Email String email) {}
