package com.example.accountservice.account.application.command;

import com.example.accountservice.account.domain.Account;
import com.example.accountservice.account.domain.AccountException;
import com.example.accountservice.account.domain.AccountFindQuery;
import com.example.accountservice.account.domain.AccountRepository;
import com.example.accountservice.account.domain.Transaction;
import com.example.accountservice.account.domain.TransferDecision;
import com.example.accountservice.account.domain.TransferEligibilityService;
import com.example.accountservice.common.IdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Transfer between accounts — the Command Service itself carries no transaction annotation. The
 * transaction boundary lives in {@link AccountRepository#saveAccounts} (at the Repository level)
 * (persistence.md, the same rule as WithdrawService/DepositService — re-attaching a transaction
 * annotation to the Command Service would be a regression).
 */
@Service
@RequiredArgsConstructor
public class TransferService {

    private final AccountRepository accountRepository;
    // TransferEligibilityService is a pure Domain Service with no framework annotations. It is
    // instantiated directly instead of being registered as a Spring bean (the same reason as
    // RefundEligibilityService).
    private final TransferEligibilityService transferEligibilityService =
            new TransferEligibilityService();

    public TransferResult transfer(TransferCommand command) {
        Account source =
                accountRepository
                        .findAccounts(
                                new AccountFindQuery(
                                        0,
                                        1,
                                        command.sourceAccountId(),
                                        command.requesterId(),
                                        null))
                        .accounts()
                        .stream()
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new AccountException(
                                                AccountException.ErrorCode.ACCOUNT_NOT_FOUND,
                                                "Account not found."));
        // target is looked up without an owner filter — since the whole point of this feature is
        // transferring to someone else's account, only its existence and active status need to be
        // checked (ownership verification applies only to source).
        Account target =
                accountRepository
                        .findAccounts(
                                new AccountFindQuery(0, 1, command.targetAccountId(), null, null))
                        .accounts()
                        .stream()
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new AccountException(
                                                AccountException.ErrorCode.ACCOUNT_NOT_FOUND,
                                                "Account not found."));

        TransferDecision decision =
                transferEligibilityService.evaluate(source, target, command.amount());
        if (!decision.approved()) {
            throw new AccountException(decision.code(), decision.reason());
        }

        // transferId does not introduce a new dedicated persistent Aggregate for this transfer; it
        // is
        // used only as the referenceId correlating the two Transaction rows — since the
        // (reference_id,
        // type) combination is already unique, the source (WITHDRAWAL) and target (DEPOSIT) rows
        // can
        // share the same transferId without conflicting. It is used as-is, 32 characters, with no
        // suffix — the reference_id column is VARCHAR(36), so appending a suffix could exceed that
        // limit.
        String transferId = IdGenerator.generate();
        Transaction sourceTransaction = source.withdraw(command.amount(), transferId);
        Transaction targetTransaction = target.deposit(command.amount(), transferId);

        // Saving both Accounts is bundled into a single physical transaction — otherwise a failure
        // mode arises where "the withdrawal is applied but the deposit is lost."
        accountRepository.saveAccounts(source, target);

        return new TransferResult(
                transferId,
                new TransactionResult(
                        sourceTransaction.getTransactionId(),
                        sourceTransaction.getAccountId(),
                        sourceTransaction.getType().name(),
                        new TransactionResult.MoneyResult(
                                sourceTransaction.getAmount().amount(),
                                sourceTransaction.getAmount().currency()),
                        sourceTransaction.getCreatedAt()),
                new TransactionResult(
                        targetTransaction.getTransactionId(),
                        targetTransaction.getAccountId(),
                        targetTransaction.getType().name(),
                        new TransactionResult.MoneyResult(
                                targetTransaction.getAmount().amount(),
                                targetTransaction.getAmount().currency()),
                        targetTransaction.getCreatedAt()));
    }
}
