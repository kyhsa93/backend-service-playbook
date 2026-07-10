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
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
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
    private val logger = LoggerFactory.getLogger(AccountController::class.java)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createAccount(
        authentication: Authentication,
        @Valid @RequestBody request: CreateAccountRequest,
    ): CreateAccountResult =
        createAccountService.create(CreateAccountCommand(authentication.name, request.currency, request.email))

    @PostMapping("/{accountId}/deposit")
    @ResponseStatus(HttpStatus.CREATED)
    fun deposit(
        authentication: Authentication,
        @PathVariable accountId: String,
        @RequestBody request: DepositRequest,
    ): TransactionResult = depositService.deposit(DepositCommand(accountId, authentication.name, request.amount))

    @PostMapping("/{accountId}/withdraw")
    @ResponseStatus(HttpStatus.CREATED)
    fun withdraw(
        authentication: Authentication,
        @PathVariable accountId: String,
        @RequestBody request: WithdrawRequest,
    ): TransactionResult = withdrawService.withdraw(WithdrawCommand(accountId, authentication.name, request.amount))

    @PostMapping("/{accountId}/suspend")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun suspendAccount(authentication: Authentication, @PathVariable accountId: String) {
        suspendAccountService.suspend(SuspendAccountCommand(accountId, authentication.name))
    }

    @PostMapping("/{accountId}/reactivate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun reactivateAccount(authentication: Authentication, @PathVariable accountId: String) {
        reactivateAccountService.reactivate(ReactivateAccountCommand(accountId, authentication.name))
    }

    @PostMapping("/{accountId}/close")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun closeAccount(authentication: Authentication, @PathVariable accountId: String) {
        closeAccountService.close(CloseAccountCommand(accountId, authentication.name))
    }

    @GetMapping("/{accountId}")
    fun getAccount(authentication: Authentication, @PathVariable accountId: String): GetAccountResult =
        getAccountService.getAccount(accountId, authentication.name)

    @GetMapping("/{accountId}/transactions")
    fun getTransactions(
        authentication: Authentication,
        @PathVariable accountId: String,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") take: Int,
    ): GetTransactionsResult = getTransactionsService.getTransactions(accountId, authentication.name, page, take)

    @ExceptionHandler(AccountNotFoundException::class)
    fun handleNotFound(e: AccountNotFoundException): ResponseEntity<ErrorResponse> {
        logger.warn("계좌를 찾을 수 없음: {}", e.message)
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse(e.message ?: ""))
    }

    @ExceptionHandler(AccountException::class)
    fun handleAccountException(e: AccountException): ResponseEntity<ErrorResponse> {
        logger.warn("계좌 요청 실패: {}", e.message)
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse(e.message ?: ""))
    }
}
