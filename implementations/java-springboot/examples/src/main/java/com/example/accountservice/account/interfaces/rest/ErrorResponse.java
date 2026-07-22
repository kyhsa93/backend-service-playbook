package com.example.accountservice.account.interfaces.rest;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.http.HttpStatus;

/**
 * The standard error response format — includes all 4 fields ({@code statusCode}/{@code
 * code}/{@code message}/{@code error}) required by the root's error-handling.md. Shared as the
 * OpenAPI schema referenced by every non-2xx {@code @ApiResponse} across every Controller (see
 * docs/architecture/api-response.md "Machine-readable API documentation (OpenAPI)").
 */
@Schema(description = "The standard error response format shared by every non-2xx response.")
public record ErrorResponse(
        @Schema(description = "The HTTP status code.", example = "400") int statusCode,
        @Schema(
                        description =
                                "A stable, machine-readable error code the client can branch on."
                                        + " Unlike `message`, this never changes wording or gets"
                                        + " translated.",
                        example = "VALIDATION_FAILED")
                String code,
        @Schema(
                        description = "A human-readable description of the error.",
                        example = "Account not found.")
                String message,
        @Schema(
                        description = "The standard HTTP status text for `statusCode`.",
                        example = "Bad Request")
                String error) {

    public static ErrorResponse of(HttpStatus status, String code, String message) {
        return new ErrorResponse(status.value(), code, message, status.getReasonPhrase());
    }
}
