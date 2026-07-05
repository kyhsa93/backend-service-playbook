package com.example.accountservice.account.interfaces.rest

import com.example.accountservice.account.application.command.CloseAccountCommand
import com.example.accountservice.account.application.command.CloseAccountService
import com.example.accountservice.account.application.command.CreateAccountCommand
import com.example.accountservice.account.application.command.CreateAccountResult
import com.example.accountservice.account.application.command.CreateAccountService
import com.example.accountservice.account.application.command.DepositCommand
import com.example.accountservice.account.application.command.DepositService
import com.example.accountservice.account.application.command.ReactivateAccountCommand
import com.example.accountservice.account.application.command.ReactivateAccountService
import com.example.accountservice.account.application.command.SuspendAccountCommand
import com.example.accountservice.account.application.command.SuspendAccountService
import com.example.accountservice.account.application.command.TransactionResult
import com.example.accountservice.account.application.command.WithdrawCommand
import com.example.accountservice.account.application.command.WithdrawService
import com.example.accountservice.account.application.query.GetAccountResult
import com.example.accountservice.account.application.query.GetAccountService
import com.example.accountservice.account.application.query.GetTransactionsResult
import com.example.accountservice.account.application.query.GetTransactionsService
import com.example.accountservice.account.domain.AccountException
import com.example.accountservice.account.domain.AccountNotFoundException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/accounts")
class AccountController(
    private val createAccountService: CreateAccountService,
    private val depositService: DepositService,
    private val withdrawService: WithdrawService,
    private val suspendAccountService: SuspendAccountService,
    private val reactivateAccountService: ReactivateAccountService,
    private val closeAccountService: CloseAccountService,
    private val getAccountService: GetAccountService,
    private val getTransactionsService: GetTransactionsService,
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createAccount(
        @RequestHeader("X-User-Id") requesterId: String,
        @RequestBody request: CreateAccountRequest,
    ): CreateAccountResult = createAccountService.create(CreateAccountCommand(requesterId, request.currency))

    @PostMapping("/{accountId}/deposit")
    @ResponseStatus(HttpStatus.CREATED)
    fun deposit(
        @RequestHeader("X-User-Id") requesterId: String,
        @PathVariable accountId: String,
        @RequestBody request: DepositRequest,
    ): TransactionResult = depositService.deposit(DepositCommand(accountId, requesterId, request.amount))

    @PostMapping("/{accountId}/withdraw")
    @ResponseStatus(HttpStatus.CREATED)
    fun withdraw(
        @RequestHeader("X-User-Id") requesterId: String,
        @PathVariable accountId: String,
        @RequestBody request: WithdrawRequest,
    ): TransactionResult = withdrawService.withdraw(WithdrawCommand(accountId, requesterId, request.amount))

    @PostMapping("/{accountId}/suspend")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun suspendAccount(@RequestHeader("X-User-Id") requesterId: String, @PathVariable accountId: String) {
        suspendAccountService.suspend(SuspendAccountCommand(accountId, requesterId))
    }

    @PostMapping("/{accountId}/reactivate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun reactivateAccount(@RequestHeader("X-User-Id") requesterId: String, @PathVariable accountId: String) {
        reactivateAccountService.reactivate(ReactivateAccountCommand(accountId, requesterId))
    }

    @PostMapping("/{accountId}/close")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun closeAccount(@RequestHeader("X-User-Id") requesterId: String, @PathVariable accountId: String) {
        closeAccountService.close(CloseAccountCommand(accountId, requesterId))
    }

    @GetMapping("/{accountId}")
    fun getAccount(@RequestHeader("X-User-Id") requesterId: String, @PathVariable accountId: String): GetAccountResult =
        getAccountService.getAccount(accountId, requesterId)

    @GetMapping("/{accountId}/transactions")
    fun getTransactions(
        @RequestHeader("X-User-Id") requesterId: String,
        @PathVariable accountId: String,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") take: Int,
    ): GetTransactionsResult = getTransactionsService.getTransactions(accountId, requesterId, page, take)

    @ExceptionHandler(AccountNotFoundException::class)
    fun handleNotFound(e: AccountNotFoundException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse(e.message ?: ""))

    @ExceptionHandler(AccountException::class)
    fun handleAccountException(e: AccountException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse(e.message ?: ""))
}
