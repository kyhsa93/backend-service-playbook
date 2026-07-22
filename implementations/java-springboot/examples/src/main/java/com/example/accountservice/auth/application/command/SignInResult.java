package com.example.accountservice.auth.application.command;

import io.swagger.v3.oas.annotations.media.Schema;

public record SignInResult(
        @Schema(
                        description =
                                "A bearer JWT to use as `Authorization: Bearer <token>` on every other endpoint.")
                String accessToken) {}
