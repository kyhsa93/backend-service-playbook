package com.example.accountservice.payment.interfaces.rest

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

// The 401 is declared once at the class level (shared by every method in this Controller) instead of
// being repeated on each operation — the rule must recognize this as satisfying "at least one non-2xx
// documented" for getPayments below, which has no error response of its own.
@RestController
@RequestMapping("/payments")
@SecurityRequirement(name = "bearerAuth")
@ApiResponses(
    ApiResponse(responseCode = "401", description = "The bearer token is missing, malformed, or invalid."),
)
class PaymentController {
    @GetMapping
    @Operation(summary = "List the requester's payments", description = "Returns the authenticated requester's payments, newest first.")
    @ApiResponse(responseCode = "200", description = "The payment list was found.")
    fun getPayments(): String = "ok"
}
