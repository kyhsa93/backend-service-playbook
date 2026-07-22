package com.example.accountservice.account.interfaces.rest

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

// summary/description are present, but only the success response is documented — the most common way
// OpenAPI docs rot (looks complete because the page renders; a 404 is undocumented).
@RestController
@RequestMapping("/accounts")
class AccountController {
    @GetMapping("/{accountId}")
    @Operation(summary = "Look up an account", description = "Returns the account only if it belongs to the requester.")
    @ApiResponse(responseCode = "200", description = "The account was found.")
    fun getAccount(
        @PathVariable accountId: String,
    ): String = accountId
}
