package com.example.accountservice.account.interfaces.rest;

import com.example.accountservice.account.application.command.*;
import com.example.accountservice.account.application.query.GetAccountResult;
import com.example.accountservice.account.application.query.GetAccountService;
import com.example.accountservice.account.application.query.GetTransactionsResult;
import com.example.accountservice.account.application.query.GetTransactionsService;
import com.example.accountservice.account.domain.AccountException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final CreateAccountService createAccountService;
    private final DepositService depositService;
    private final WithdrawService withdrawService;
    private final SuspendAccountService suspendAccountService;
    private final ReactivateAccountService reactivateAccountService;
    private final CloseAccountService closeAccountService;
    private final GetAccountService getAccountService;
    private final GetTransactionsService getTransactionsService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreateAccountResult createAccount(
            @RequestHeader("X-User-Id") String requesterId,
            @Valid @RequestBody CreateAccountRequest request
    ) {
        return createAccountService.create(new CreateAccountCommand(requesterId, request.email(), request.currency()));
    }

    @PostMapping("/{accountId}/deposit")
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionResult deposit(
            @RequestHeader("X-User-Id") String requesterId,
            @PathVariable String accountId,
            @RequestBody DepositRequest request
    ) {
        return depositService.deposit(new DepositCommand(accountId, requesterId, request.amount()));
    }

    @PostMapping("/{accountId}/withdraw")
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionResult withdraw(
            @RequestHeader("X-User-Id") String requesterId,
            @PathVariable String accountId,
            @RequestBody WithdrawRequest request
    ) {
        return withdrawService.withdraw(new WithdrawCommand(accountId, requesterId, request.amount()));
    }

    @PostMapping("/{accountId}/suspend")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void suspendAccount(@RequestHeader("X-User-Id") String requesterId, @PathVariable String accountId) {
        suspendAccountService.suspend(new SuspendAccountCommand(accountId, requesterId));
    }

    @PostMapping("/{accountId}/reactivate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reactivateAccount(@RequestHeader("X-User-Id") String requesterId, @PathVariable String accountId) {
        reactivateAccountService.reactivate(new ReactivateAccountCommand(accountId, requesterId));
    }

    @PostMapping("/{accountId}/close")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void closeAccount(@RequestHeader("X-User-Id") String requesterId, @PathVariable String accountId) {
        closeAccountService.close(new CloseAccountCommand(accountId, requesterId));
    }

    @GetMapping("/{accountId}")
    public GetAccountResult getAccount(@RequestHeader("X-User-Id") String requesterId, @PathVariable String accountId) {
        return getAccountService.getAccount(accountId, requesterId);
    }

    @GetMapping("/{accountId}/transactions")
    public GetTransactionsResult getTransactions(
            @RequestHeader("X-User-Id") String requesterId,
            @PathVariable String accountId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int take
    ) {
        return getTransactionsService.getTransactions(accountId, requesterId, page, take);
    }

    @ExceptionHandler(AccountException.class)
    public ResponseEntity<ErrorResponse> handleAccountException(AccountException e) {
        HttpStatus status = e.code() == AccountException.ErrorCode.ACCOUNT_NOT_FOUND
                ? HttpStatus.NOT_FOUND
                : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(new ErrorResponse(e.code().name(), e.getMessage()));
    }
}
