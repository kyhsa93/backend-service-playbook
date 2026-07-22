package com.example.accountservice.account.application.command;

import com.example.accountservice.account.domain.Account;
import com.example.accountservice.account.domain.AccountFindQuery;
import com.example.accountservice.account.domain.AccountRepository;
import com.example.accountservice.account.domain.TransactionType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * The reaction use case for both of the Payment BC's {@code payment.cancelled.v1}
 * (payment-cancellation compensating credit) and {@code refund.approved.v1} (refund-approval
 * credit) Integration Events — both events are the same action, "reverse an already-debited
 * amount," differing only in referenceId (paymentId or refundId), so a single Command is reused.
 *
 * <p>Idempotency uses Level 2 Ledger for the same reason as {@link WithdrawByPaymentService}. The
 * reason the shared Outbox drain component is not injected via the constructor is also the same as
 * {@link WithdrawByPaymentService} (avoiding a circular bean dependency — this service is only ever
 * called from inside an in-progress drain loop, via an {@code OutboxEventHandler} implementation).
 */
@Service
@RequiredArgsConstructor
public class DepositByPaymentService {

    private final AccountRepository accountRepository;

    public void deposit(DepositByPaymentCommand command) {
        boolean alreadyProcessed =
                accountRepository.hasTransactionWithReference(
                        command.referenceId(), TransactionType.DEPOSIT);
        if (alreadyProcessed) {
            return;
        }

        Account account =
                accountRepository
                        .findAccounts(new AccountFindQuery(0, 1, command.accountId(), null, null))
                        .accounts()
                        .stream()
                        .findFirst()
                        .orElse(null);
        if (account == null) {
            return;
        }

        account.deposit(command.amount(), command.referenceId());
        accountRepository.saveAccount(account);
    }
}
