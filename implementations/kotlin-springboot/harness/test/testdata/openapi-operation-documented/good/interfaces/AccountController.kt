package com.example.accountservice.account.interfaces.rest

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/accounts")
class AccountController {
    @PostMapping
    @Operation(summary = "Open a new account", description = "Opens a new account for the authenticated requester.")
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "The account was created."),
        ApiResponse(responseCode = "400", description = "Request validation failed (`VALIDATION_FAILED`)."),
    )
    fun createAccount(): String = "ok"

    @GetMapping("/{accountId}")
    @Operation(summary = "Look up an account", description = "Returns the account only if it belongs to the requester.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "The account was found."),
        ApiResponse(responseCode = "404", description = "No account exists with the given `accountId` (`ACCOUNT_NOT_FOUND`)."),
    )
    fun getAccount(
        @PathVariable accountId: String,
    ): String = accountId
}
