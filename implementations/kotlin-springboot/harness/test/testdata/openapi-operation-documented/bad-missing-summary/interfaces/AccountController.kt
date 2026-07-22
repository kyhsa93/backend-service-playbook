package com.example.accountservice.account.interfaces.rest

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

// @Operation is present but only carries an operationId — no summary/description.
@RestController
@RequestMapping("/accounts")
class AccountController {
    @GetMapping("/{accountId}")
    @Operation(operationId = "getAccount")
    @ApiResponse(responseCode = "404", description = "No account exists with the given `accountId` (`ACCOUNT_NOT_FOUND`).")
    fun getAccount(
        @PathVariable accountId: String,
    ): String = accountId
}
