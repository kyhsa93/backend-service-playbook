package com.example.accountservice.account.interfaces.rest;

import com.example.accountservice.account.application.command.*;
import com.example.accountservice.account.application.query.GetAccountResult;
import com.example.accountservice.account.application.query.GetAccountService;
import com.example.accountservice.account.application.query.GetTransactionsResult;
import com.example.accountservice.account.application.query.GetTransactionsService;
import com.example.accountservice.account.domain.AccountException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import static net.logstash.logback.argument.StructuredArguments.kv;

@RestController
@RequestMapping("/accounts")
@RequiredArgsConstructor
public class AccountController {

    private static final Logger log = LoggerFactory.getLogger(AccountController.class);

    private final CreateAccountService createAccountService;
    private final DepositService depositService;
    private final WithdrawService withdrawService;
    private final SuspendAccountService suspendAccountService;
    private final ReactivateAccountService reactivateAccountService;
    private final CloseAccountService closeAccountService;
    private final DeleteAccountService deleteAccountService;
    private final GetAccountService getAccountService;
    private final GetTransactionsService getTransactionsService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @io.github.resilience4j.ratelimiter.annotation.RateLimiter(name = "createAccount")
    public CreateAccountResult createAccount(
            Authentication authentication,
            @Valid @RequestBody CreateAccountRequest request
    ) {
        String requesterId = authentication.getName();
        return createAccountService.create(new CreateAccountCommand(requesterId, request.email(), request.currency()));
    }

    @PostMapping("/{accountId}/deposit")
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionResult deposit(
            Authentication authentication,
            @PathVariable String accountId,
            @RequestBody DepositRequest request
    ) {
        String requesterId = authentication.getName();
        return depositService.deposit(new DepositCommand(accountId, requesterId, request.amount()));
    }

    @PostMapping("/{accountId}/withdraw")
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionResult withdraw(
            Authentication authentication,
            @PathVariable String accountId,
            @RequestBody WithdrawRequest request
    ) {
        String requesterId = authentication.getName();
        return withdrawService.withdraw(new WithdrawCommand(accountId, requesterId, request.amount()));
    }

    @PostMapping("/{accountId}/suspend")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void suspendAccount(Authentication authentication, @PathVariable String accountId) {
        suspendAccountService.suspend(new SuspendAccountCommand(accountId, authentication.getName()));
    }

    @PostMapping("/{accountId}/reactivate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reactivateAccount(Authentication authentication, @PathVariable String accountId) {
        reactivateAccountService.reactivate(new ReactivateAccountCommand(accountId, authentication.getName()));
    }

    @PostMapping("/{accountId}/close")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void closeAccount(Authentication authentication, @PathVariable String accountId) {
        closeAccountService.close(new CloseAccountCommand(accountId, authentication.getName()));
    }

    @DeleteMapping("/{accountId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAccount(Authentication authentication, @PathVariable String accountId) {
        deleteAccountService.delete(new DeleteAccountCommand(accountId, authentication.getName()));
    }

    @GetMapping("/{accountId}")
    public GetAccountResult getAccount(Authentication authentication, @PathVariable String accountId) {
        return getAccountService.getAccount(accountId, authentication.getName());
    }

    @GetMapping("/{accountId}/transactions")
    public GetTransactionsResult getTransactions(
            Authentication authentication,
            @PathVariable String accountId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int take
    ) {
        return getTransactionsService.getTransactions(accountId, authentication.getName(), page, take);
    }

    @ExceptionHandler(AccountException.class)
    public ResponseEntity<ErrorResponse> handleAccountException(AccountException e) {
        HttpStatus status = e.code() == AccountException.ErrorCode.ACCOUNT_NOT_FOUND
                ? HttpStatus.NOT_FOUND
                : HttpStatus.BAD_REQUEST;
        log.warn("계좌 요청 실패", kv("code", e.code()), kv("message", e.getMessage()));
        return ResponseEntity.status(status).body(ErrorResponse.of(status, e.code().name(), e.getMessage()));
    }
}
