package com.example.accountservice.account.interfaces.rest;

import static net.logstash.logback.argument.StructuredArguments.kv;

import com.example.accountservice.account.application.command.*;
import com.example.accountservice.account.application.query.GetAccountResult;
import com.example.accountservice.account.application.query.GetAccountService;
import com.example.accountservice.account.application.query.GetTransactionsResult;
import com.example.accountservice.account.application.query.GetTransactionsService;
import com.example.accountservice.account.domain.AccountException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/accounts")
@RequiredArgsConstructor
@Tag(name = "Account")
@SecurityRequirement(name = "bearer-jwt")
@ApiResponse(
        responseCode = "401",
        description = "The bearer token is missing, malformed, or invalid.",
        content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
public class AccountController {

    private static final Logger log = LoggerFactory.getLogger(AccountController.class);

    private final CreateAccountService createAccountService;
    private final DepositService depositService;
    private final WithdrawService withdrawService;
    private final TransferService transferService;
    private final SuspendAccountService suspendAccountService;
    private final ReactivateAccountService reactivateAccountService;
    private final CloseAccountService closeAccountService;
    private final DeleteAccountService deleteAccountService;
    private final GetAccountService getAccountService;
    private final GetTransactionsService getTransactionsService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @io.github.resilience4j.ratelimiter.annotation.RateLimiter(name = "createAccount")
    @Operation(
            summary = "Open a new account",
            description =
                    "Opens a new account for the authenticated requester with a 0 balance in the"
                            + " given currency.")
    @ApiResponse(
            responseCode = "201",
            description = "The account was created.",
            content = @Content(schema = @Schema(implementation = CreateAccountResult.class)))
    @ApiResponse(
            responseCode = "400",
            description =
                    "Request validation failed (`VALIDATION_FAILED`) — e.g. an invalid email or"
                            + " missing currency.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public CreateAccountResult createAccount(
            Authentication authentication, @Valid @RequestBody CreateAccountRequest request) {
        String requesterId = authentication.getName();
        return createAccountService.create(
                new CreateAccountCommand(requesterId, request.email(), request.currency()));
    }

    @PostMapping("/{accountId}/deposit")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Deposit money into an account",
            description =
                    "Credits the given amount to the account and records a `DEPOSIT` transaction.")
    @ApiResponse(
            responseCode = "201",
            description = "The deposit succeeded.",
            content = @Content(schema = @Schema(implementation = TransactionResult.class)))
    @ApiResponse(
            responseCode = "400",
            description =
                    "One of: the amount is not a positive integer (`INVALID_AMOUNT`), or the"
                            + " account is not active (`DEPOSIT_REQUIRES_ACTIVE_ACCOUNT`).",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(
            responseCode = "404",
            description =
                    "No account exists with the given `accountId` for this requester (`ACCOUNT_NOT_FOUND`).",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public TransactionResult deposit(
            Authentication authentication,
            @PathVariable String accountId,
            @RequestBody DepositRequest request) {
        String requesterId = authentication.getName();
        return depositService.deposit(new DepositCommand(accountId, requesterId, request.amount()));
    }

    @PostMapping("/{accountId}/withdraw")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Withdraw money from an account",
            description =
                    "Debits the given amount from the account and records a `WITHDRAWAL` transaction.")
    @ApiResponse(
            responseCode = "201",
            description = "The withdrawal succeeded.",
            content = @Content(schema = @Schema(implementation = TransactionResult.class)))
    @ApiResponse(
            responseCode = "400",
            description =
                    "One of: the amount is not a positive integer (`INVALID_AMOUNT`), the account"
                            + " is not active (`WITHDRAW_REQUIRES_ACTIVE_ACCOUNT`), or the balance"
                            + " is insufficient (`INSUFFICIENT_BALANCE`).",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(
            responseCode = "404",
            description =
                    "No account exists with the given `accountId` for this requester (`ACCOUNT_NOT_FOUND`).",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public TransactionResult withdraw(
            Authentication authentication,
            @PathVariable String accountId,
            @RequestBody WithdrawRequest request) {
        String requesterId = authentication.getName();
        return withdrawService.withdraw(
                new WithdrawCommand(accountId, requesterId, request.amount()));
    }

    @PostMapping("/{accountId}/transfer")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Transfer money to another account",
            description =
                    "Atomically debits the source account and credits the target account with the"
                            + " given amount, recording one `WITHDRAWAL` and one `DEPOSIT`"
                            + " transaction.")
    @ApiResponse(
            responseCode = "201",
            description = "The transfer succeeded.",
            content = @Content(schema = @Schema(implementation = TransferResult.class)))
    @ApiResponse(
            responseCode = "400",
            description =
                    "One of: the amount is not a positive integer (`INVALID_AMOUNT`), the source"
                            + " and target accounts are the same (`TRANSFER_SAME_ACCOUNT`), either"
                            + " account is not active"
                            + " (`WITHDRAW_REQUIRES_ACTIVE_ACCOUNT`/`DEPOSIT_REQUIRES_ACTIVE_ACCOUNT`),"
                            + " the currencies do not match (`CURRENCY_MISMATCH`), or the balance"
                            + " is insufficient (`INSUFFICIENT_BALANCE`).",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(
            responseCode = "404",
            description =
                    "No account exists with the given source or target `accountId` (`ACCOUNT_NOT_FOUND`).",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public TransferResult transfer(
            Authentication authentication,
            @PathVariable String accountId,
            @RequestBody TransferRequest request) {
        String requesterId = authentication.getName();
        return transferService.transfer(
                new TransferCommand(
                        accountId, request.targetAccountId(), requesterId, request.amount()));
    }

    @PostMapping("/{accountId}/suspend")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            summary = "Suspend an account",
            description =
                    "Suspends an active account, blocking further deposits/withdrawals/transfers"
                            + " until it is reactivated.")
    @ApiResponse(responseCode = "204", description = "The account was suspended.")
    @ApiResponse(
            responseCode = "400",
            description =
                    "Only an active account can be suspended (`SUSPEND_REQUIRES_ACTIVE_ACCOUNT`).",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(
            responseCode = "404",
            description =
                    "No account exists with the given `accountId` for this requester (`ACCOUNT_NOT_FOUND`).",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public void suspendAccount(Authentication authentication, @PathVariable String accountId) {
        suspendAccountService.suspend(
                new SuspendAccountCommand(accountId, authentication.getName()));
    }

    @PostMapping("/{accountId}/reactivate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            summary = "Reactivate a suspended account",
            description =
                    "Moves a suspended account back to active, restoring its ability to accept"
                            + " deposits/withdrawals/transfers.")
    @ApiResponse(responseCode = "204", description = "The account was reactivated.")
    @ApiResponse(
            responseCode = "400",
            description =
                    "Only a suspended account can be reactivated (`REACTIVATE_REQUIRES_SUSPENDED_ACCOUNT`).",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(
            responseCode = "404",
            description =
                    "No account exists with the given `accountId` for this requester (`ACCOUNT_NOT_FOUND`).",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public void reactivateAccount(Authentication authentication, @PathVariable String accountId) {
        reactivateAccountService.reactivate(
                new ReactivateAccountCommand(accountId, authentication.getName()));
    }

    @PostMapping("/{accountId}/close")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            summary = "Close an account",
            description =
                    "Permanently closes an account. The balance must be exactly 0 first (withdraw"
                            + " or transfer out any remaining funds).")
    @ApiResponse(responseCode = "204", description = "The account was closed.")
    @ApiResponse(
            responseCode = "400",
            description =
                    "One of: the account is already closed (`ACCOUNT_ALREADY_CLOSED`), or the"
                            + " balance is not 0 (`ACCOUNT_BALANCE_NOT_ZERO`).",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(
            responseCode = "404",
            description =
                    "No account exists with the given `accountId` for this requester (`ACCOUNT_NOT_FOUND`).",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public void closeAccount(Authentication authentication, @PathVariable String accountId) {
        closeAccountService.close(new CloseAccountCommand(accountId, authentication.getName()));
    }

    @DeleteMapping("/{accountId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            summary = "Delete a closed account",
            description =
                    "Soft-deletes a closed account's record. This is a data-lifecycle operation"
                            + " distinct from `close` — only an already-`CLOSED` account can be"
                            + " deleted.")
    @ApiResponse(responseCode = "204", description = "The account was deleted.")
    @ApiResponse(
            responseCode = "400",
            description =
                    "Only a closed account can be deleted (`ACCOUNT_NOT_CLOSABLE_FOR_DELETE`).",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(
            responseCode = "404",
            description =
                    "No account exists with the given `accountId` for this requester (`ACCOUNT_NOT_FOUND`).",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public void deleteAccount(Authentication authentication, @PathVariable String accountId) {
        deleteAccountService.delete(new DeleteAccountCommand(accountId, authentication.getName()));
    }

    @GetMapping("/{accountId}")
    @Operation(
            summary = "Look up an account",
            description = "Returns the account only if it belongs to the authenticated requester.")
    @ApiResponse(
            responseCode = "200",
            description = "The account was found.",
            content = @Content(schema = @Schema(implementation = GetAccountResult.class)))
    @ApiResponse(
            responseCode = "404",
            description =
                    "No account exists with the given `accountId` for this requester (`ACCOUNT_NOT_FOUND`).",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public GetAccountResult getAccount(
            Authentication authentication, @PathVariable String accountId) {
        return getAccountService.getAccount(accountId, authentication.getName());
    }

    @GetMapping("/{accountId}/transactions")
    @Operation(
            summary = "List an account's transaction history",
            description =
                    "Returns the account's deposit/withdrawal/interest transactions, newest first,"
                            + " paginated with `page`/`take`.")
    @ApiResponse(
            responseCode = "200",
            description = "The transaction history was found.",
            content = @Content(schema = @Schema(implementation = GetTransactionsResult.class)))
    @ApiResponse(
            responseCode = "404",
            description =
                    "No account exists with the given `accountId` for this requester (`ACCOUNT_NOT_FOUND`).",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public GetTransactionsResult getTransactions(
            Authentication authentication,
            @PathVariable String accountId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int take) {
        return getTransactionsService.getTransactions(
                accountId, authentication.getName(), page, take);
    }

    @ExceptionHandler(AccountException.class)
    public ResponseEntity<ErrorResponse> handleAccountException(AccountException e) {
        HttpStatus status =
                e.code() == AccountException.ErrorCode.ACCOUNT_NOT_FOUND
                        ? HttpStatus.NOT_FOUND
                        : HttpStatus.BAD_REQUEST;
        log.warn("Account request failed", kv("code", e.code()), kv("message", e.getMessage()));
        return ResponseEntity.status(status)
                .body(ErrorResponse.of(status, e.code().name(), e.getMessage()));
    }
}
