package com.example.accountservice.account.interfaces.rest

import com.example.accountservice.account.application.command.CloseAccountCommand
import com.example.accountservice.account.application.command.CloseAccountService
import com.example.accountservice.account.application.command.CreateAccountCommand
import com.example.accountservice.account.application.command.CreateAccountResult
import com.example.accountservice.account.application.command.CreateAccountService
import com.example.accountservice.account.application.command.DeleteAccountCommand
import com.example.accountservice.account.application.command.DeleteAccountService
import com.example.accountservice.account.application.command.DepositCommand
import com.example.accountservice.account.application.command.DepositService
import com.example.accountservice.account.application.command.ReactivateAccountCommand
import com.example.accountservice.account.application.command.ReactivateAccountService
import com.example.accountservice.account.application.command.SuspendAccountCommand
import com.example.accountservice.account.application.command.SuspendAccountService
import com.example.accountservice.account.application.command.TransactionResult
import com.example.accountservice.account.application.command.TransferCommand
import com.example.accountservice.account.application.command.TransferResult
import com.example.accountservice.account.application.command.TransferService
import com.example.accountservice.account.application.command.WithdrawCommand
import com.example.accountservice.account.application.command.WithdrawService
import com.example.accountservice.account.application.query.GetAccountResult
import com.example.accountservice.account.application.query.GetAccountService
import com.example.accountservice.account.application.query.GetTransactionsResult
import com.example.accountservice.account.application.query.GetTransactionsService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/accounts")
@Tag(name = "Account")
@SecurityRequirement(name = "bearerAuth")
@ApiResponses(
    ApiResponse(
        responseCode = "401",
        description = "The bearer token is missing, malformed, or invalid.",
        content = [Content(schema = Schema(implementation = ErrorResponse::class))],
    ),
)
class AccountController(
    private val createAccountService: CreateAccountService,
    private val depositService: DepositService,
    private val withdrawService: WithdrawService,
    private val transferService: TransferService,
    private val suspendAccountService: SuspendAccountService,
    private val reactivateAccountService: ReactivateAccountService,
    private val closeAccountService: CloseAccountService,
    private val deleteAccountService: DeleteAccountService,
    private val getAccountService: GetAccountService,
    private val getTransactionsService: GetTransactionsService,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
        summary = "Open a new account",
        description = "Opens a new account for the authenticated requester with a 0 balance in the given currency.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "The account was created."),
        ApiResponse(
            responseCode = "400",
            description = "Request validation failed (`VALIDATION_FAILED`) — e.g. an invalid or missing email.",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    fun createAccount(
        authentication: Authentication,
        @Valid @RequestBody request: CreateAccountRequest,
    ): CreateAccountResult = createAccountService.create(CreateAccountCommand(authentication.name, request.currency, request.email))

    @PostMapping("/{accountId}/deposit")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
        summary = "Deposit money into an account",
        description = "Credits the given amount to the account and records a `DEPOSIT` transaction.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "The deposit succeeded."),
        ApiResponse(
            responseCode = "400",
            description =
                "One of: amount not positive (`INVALID_AMOUNT`), or the account is not active " +
                    "(`DEPOSIT_REQUIRES_ACTIVE_ACCOUNT`).",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "No account exists with the given `accountId` for this requester (`ACCOUNT_NOT_FOUND`).",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    fun deposit(
        authentication: Authentication,
        @PathVariable accountId: String,
        @RequestBody request: DepositRequest,
    ): TransactionResult = depositService.deposit(DepositCommand(accountId, authentication.name, request.amount))

    @PostMapping("/{accountId}/withdraw")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
        summary = "Withdraw money from an account",
        description = "Debits the given amount from the account and records a `WITHDRAWAL` transaction.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "The withdrawal succeeded."),
        ApiResponse(
            responseCode = "400",
            description =
                "One of: amount not positive (`INVALID_AMOUNT`), account not active " +
                    "(`WITHDRAW_REQUIRES_ACTIVE_ACCOUNT`), or balance insufficient (`INSUFFICIENT_BALANCE`).",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "No account exists with the given `accountId` for this requester (`ACCOUNT_NOT_FOUND`).",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    fun withdraw(
        authentication: Authentication,
        @PathVariable accountId: String,
        @RequestBody request: WithdrawRequest,
    ): TransactionResult = withdrawService.withdraw(WithdrawCommand(accountId, authentication.name, request.amount))

    @PostMapping("/{accountId}/transfer")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
        summary = "Transfer money to another account",
        description =
            "Atomically debits the source account and credits the target account, recording one " +
                "`WITHDRAWAL` and one `DEPOSIT` transaction.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "The transfer succeeded."),
        ApiResponse(
            responseCode = "400",
            description =
                "One of: amount not positive (`INVALID_AMOUNT`), source/target the same (`TRANSFER_SAME_ACCOUNT`), an account not active " +
                    "(`WITHDRAW_REQUIRES_ACTIVE_ACCOUNT`/`DEPOSIT_REQUIRES_ACTIVE_ACCOUNT`), currency mismatch (`CURRENCY_MISMATCH`), " +
                    "or insufficient balance (`INSUFFICIENT_BALANCE`).",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "No account exists with the given source or target `accountId` (`ACCOUNT_NOT_FOUND`).",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    fun transfer(
        authentication: Authentication,
        @PathVariable accountId: String,
        @RequestBody request: TransferRequest,
    ): TransferResult = transferService.transfer(TransferCommand(accountId, request.targetAccountId, authentication.name, request.amount))

    @PostMapping("/{accountId}/suspend")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
        summary = "Suspend an account",
        description = "Suspends an active account, blocking further deposits/withdrawals/transfers until it is reactivated.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "The account was suspended."),
        ApiResponse(
            responseCode = "400",
            description = "Only an active account can be suspended (`SUSPEND_REQUIRES_ACTIVE_ACCOUNT`).",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "No account exists with the given `accountId` for this requester (`ACCOUNT_NOT_FOUND`).",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    fun suspendAccount(
        authentication: Authentication,
        @PathVariable accountId: String,
    ) {
        suspendAccountService.suspend(SuspendAccountCommand(accountId, authentication.name))
    }

    @PostMapping("/{accountId}/reactivate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
        summary = "Reactivate a suspended account",
        description = "Moves a suspended account back to active, restoring its ability to accept deposits/withdrawals/transfers.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "The account was reactivated."),
        ApiResponse(
            responseCode = "400",
            description = "Only a suspended account can be reactivated (`REACTIVATE_REQUIRES_SUSPENDED_ACCOUNT`).",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "No account exists with the given `accountId` for this requester (`ACCOUNT_NOT_FOUND`).",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    fun reactivateAccount(
        authentication: Authentication,
        @PathVariable accountId: String,
    ) {
        reactivateAccountService.reactivate(ReactivateAccountCommand(accountId, authentication.name))
    }

    @PostMapping("/{accountId}/close")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
        summary = "Close an account",
        description = "Permanently closes an account. The balance must be exactly 0 first (withdraw or transfer out any remaining funds).",
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "The account was closed."),
        ApiResponse(
            responseCode = "400",
            description = "One of: account already closed (`ACCOUNT_ALREADY_CLOSED`), or balance not 0 (`ACCOUNT_BALANCE_NOT_ZERO`).",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "No account exists with the given `accountId` for this requester (`ACCOUNT_NOT_FOUND`).",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    fun closeAccount(
        authentication: Authentication,
        @PathVariable accountId: String,
    ) {
        closeAccountService.close(CloseAccountCommand(accountId, authentication.name))
    }

    @DeleteMapping("/{accountId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
        summary = "Delete a closed account",
        description = "Soft-deletes an account. Only an account already in the `CLOSED` state may be deleted.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "The account was deleted."),
        ApiResponse(
            responseCode = "400",
            description = "Only a closed account can be deleted (`DELETE_REQUIRES_CLOSED_ACCOUNT`).",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "No account exists with the given `accountId` for this requester (`ACCOUNT_NOT_FOUND`).",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    fun deleteAccount(
        authentication: Authentication,
        @PathVariable accountId: String,
    ) {
        deleteAccountService.delete(DeleteAccountCommand(accountId, authentication.name))
    }

    @GetMapping("/{accountId}")
    @Operation(summary = "Look up an account", description = "Returns the account only if it belongs to the authenticated requester.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "The account was found."),
        ApiResponse(
            responseCode = "404",
            description = "No account exists with the given `accountId` for this requester (`ACCOUNT_NOT_FOUND`).",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    fun getAccount(
        authentication: Authentication,
        @PathVariable accountId: String,
    ): GetAccountResult = getAccountService.getAccount(accountId, authentication.name)

    @GetMapping("/{accountId}/transactions")
    @Operation(
        summary = "List an account's transaction history",
        description = "Returns the account's deposit/withdrawal/interest transactions, newest first, paginated with `page`/`take`.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "The transaction history was found."),
        ApiResponse(
            responseCode = "404",
            description = "No account exists with the given `accountId` for this requester (`ACCOUNT_NOT_FOUND`).",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    fun getTransactions(
        authentication: Authentication,
        @PathVariable accountId: String,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") take: Int,
    ): GetTransactionsResult = getTransactionsService.getTransactions(accountId, authentication.name, page, take)
}
