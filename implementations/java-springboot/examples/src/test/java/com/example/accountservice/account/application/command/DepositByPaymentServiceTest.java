package com.example.accountservice.account.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.accountservice.account.domain.Account;
import com.example.accountservice.account.domain.AccountFindQuery;
import com.example.accountservice.account.domain.AccountRepository;
import com.example.accountservice.account.domain.AccountsWithCount;
import com.example.accountservice.account.domain.TransactionType;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DepositByPaymentServiceTest {

    @Mock private AccountRepository accountRepository;

    private DepositByPaymentService service;

    @BeforeEach
    void setUp() {
        service = new DepositByPaymentService(accountRepository);
    }

    @Test
    void deposits_and_saves_for_an_unprocessed_compensating_credit_event() {
        Account account = Account.create("owner-1", "owner-1@example.com", "KRW");
        when(accountRepository.hasTransactionWithReference("payment-1", TransactionType.DEPOSIT))
                .thenReturn(false);
        when(accountRepository.findAccounts(
                        new AccountFindQuery(0, 1, account.getAccountId(), null, null)))
                .thenReturn(new AccountsWithCount(List.of(account), 1));

        service.deposit(new DepositByPaymentCommand(account.getAccountId(), 1000, "payment-1"));

        assertThat(account.getBalance().amount()).isEqualTo(1000);
        verify(accountRepository).saveAccount(account);
    }

    @Test
    void
            does_not_treat_as_duplicate_when_type_differs_despite_referencing_the_same_paymentId_as_the_completed_withdrawal() {
        // A completed payment (WITHDRAWAL) and its cancellation compensation credit (DEPOSIT) share
        // the same paymentId as referenceId, but they are different transactions —
        // hasTransactionWithReference checks the (referenceId, type) combination, so whether a
        // DEPOSIT exists must be checked separately even if a WITHDRAWAL already exists (this test
        // verifies that call scope).
        Account account = Account.create("owner-1", "owner-1@example.com", "KRW");
        when(accountRepository.hasTransactionWithReference("payment-1", TransactionType.DEPOSIT))
                .thenReturn(false);
        when(accountRepository.findAccounts(
                        new AccountFindQuery(0, 1, account.getAccountId(), null, null)))
                .thenReturn(new AccountsWithCount(List.of(account), 1));

        service.deposit(new DepositByPaymentCommand(account.getAccountId(), 1000, "payment-1"));

        verify(accountRepository).hasTransactionWithReference("payment-1", TransactionType.DEPOSIT);
        verify(accountRepository).saveAccount(account);
    }

    @Test
    void silently_ignores_an_already_processed_referenceId_idempotency() {
        when(accountRepository.hasTransactionWithReference("payment-1", TransactionType.DEPOSIT))
                .thenReturn(true);

        service.deposit(new DepositByPaymentCommand("account-1", 1000, "payment-1"));

        verify(accountRepository, never()).findAccounts(any());
        verify(accountRepository, never()).saveAccount(any());
    }
}
