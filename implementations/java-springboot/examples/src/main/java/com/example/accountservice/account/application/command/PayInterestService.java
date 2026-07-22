package com.example.accountservice.account.application.command;

import com.example.accountservice.account.domain.Account;
import com.example.accountservice.account.domain.AccountFindQuery;
import com.example.accountservice.account.domain.AccountRepository;
import com.example.accountservice.account.domain.AccountStatus;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * A system use case (scheduled interest payment) invoked once daily by a batch — not a Command
 * invoked directly by a user, but called by {@code interfaces/task/PayInterestTaskController} when
 * it receives a Task Queue message (scheduling.md Feature 1). It paginates over active accounts,
 * delegating the actual interest calculation/payment decision entirely to {@link
 * Account#payInterest} — this Service only orchestrates lookup and save.
 *
 * <p>The transaction boundary lives not in this Command Service but in {@code
 * AccountRepository.saveAccount()} (the transaction-boundary rule, persistence.md) — each account
 * is saved in its own individual transaction, so if an exception occurs partway through the batch,
 * accounts already processed remain committed (the next tick processes the rest; this is safe
 * because it is idempotent).
 */
@Service
@RequiredArgsConstructor
public class PayInterestService {

    private static final int PAGE_SIZE = 100;

    private final AccountRepository accountRepository;

    public void payInterest(PayInterestCommand command) {
        int page = 0;
        while (true) {
            List<Account> accounts =
                    accountRepository
                            .findAccounts(
                                    new AccountFindQuery(
                                            page,
                                            PAGE_SIZE,
                                            null,
                                            null,
                                            List.of(AccountStatus.ACTIVE.name())))
                            .accounts();
            if (accounts.isEmpty()) {
                break;
            }
            for (Account account : accounts) {
                account.payInterest(command.date())
                        .ifPresent(transaction -> accountRepository.saveAccount(account));
            }
            if (accounts.size() < PAGE_SIZE) {
                break;
            }
            page++;
        }
    }
}
