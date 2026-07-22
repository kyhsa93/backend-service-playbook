package com.example.accountservice.account.application.command;

import com.example.accountservice.account.domain.Account;
import com.example.accountservice.account.domain.AccountFindQuery;
import com.example.accountservice.account.domain.AccountRepository;
import com.example.accountservice.account.domain.TransactionType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * The reaction use case for the Payment BC's {@code payment.completed.v1} Integration Event — it
 * actually performs the deduction that was already decided synchronously by the Adapter at payment
 * time.
 *
 * <p>Idempotency: unlike {@link WithdrawService} (a user's direct withdrawal), this reaction
 * silently ignores the event if a WITHDRAWAL transaction with the same referenceId (paymentId)
 * already exists — unlike Card's status-based idempotency, moving money repeatedly would keep
 * decreasing the balance, so "has this already been processed" must be checked (Level 2 Ledger, see
 * domain-events.md).
 *
 * <p>The shared Outbox drain component (the relay in the outbox package) is not injected via the
 * constructor — this service is always invoked only through {@code
 * PaymentCompletedIntegrationEventHandler} (which is itself called, as an {@code
 * OutboxEventHandler}, from within that relay's in-progress drain loop). Injecting the relay here
 * would create a circular bean dependency, where the relay would require itself again from this
 * service inside the handler list (List&lt;OutboxEventHandler&gt;) it is currently constructing,
 * causing context startup to fail (the same constraint that keeps Card's {@code
 * SuspendCardsByAccountService} from using that relay). The {@code MoneyWithdrawnEvent} newly
 * persisted by this method is automatically picked up on the next pass of the already-running outer
 * drain call.
 */
@Service
@RequiredArgsConstructor
public class WithdrawByPaymentService {

    private final AccountRepository accountRepository;

    public void withdraw(WithdrawByPaymentCommand command) {
        boolean alreadyProcessed =
                accountRepository.hasTransactionWithReference(
                        command.referenceId(), TransactionType.WITHDRAWAL);
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
            return; // Silently ignore if there is no account to react to (e.g., the account was
            // already deleted).
        }

        account.withdraw(command.amount(), command.referenceId());
        accountRepository.saveAccount(account);
    }
}
