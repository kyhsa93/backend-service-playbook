package com.example.accountservice.account.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.accountservice.account.domain.Account;
import com.example.accountservice.account.domain.AccountFindQuery;
import com.example.accountservice.account.domain.AccountRepository;
import com.example.accountservice.account.domain.AccountStatus;
import com.example.accountservice.account.domain.AccountsWithCount;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PayInterestServiceTest {

    @Mock private AccountRepository accountRepository;

    private PayInterestService service;

    @BeforeEach
    void setUp() {
        service = new PayInterestService(accountRepository);
    }

    private Account activeAccountWithBalance(long amount) {
        Account account = Account.create("owner-1", "owner-1@example.com", "KRW");
        account.deposit(amount);
        account.pullDomainEvents();
        account.pullPendingTransactions();
        return account;
    }

    @Test
    void pays_interest_to_an_active_account_and_saves_it() {
        Account account = activeAccountWithBalance(1_000_000);
        when(accountRepository.findAccounts(
                        new AccountFindQuery(
                                0, 100, null, null, List.of(AccountStatus.ACTIVE.name()))))
                .thenReturn(new AccountsWithCount(List.of(account), 1));

        service.payInterest(new PayInterestCommand(LocalDate.of(2026, 7, 21)));

        verify(accountRepository, times(1)).saveAccount(account);
        assertThat(account.getBalance().amount()).isEqualTo(1_000_100);
    }

    @Test
    void does_not_save_an_account_whose_interest_is_zero_idempotency() {
        Account account = activeAccountWithBalance(10); // 10 / 10_000 = 0
        when(accountRepository.findAccounts(
                        new AccountFindQuery(
                                0, 100, null, null, List.of(AccountStatus.ACTIVE.name()))))
                .thenReturn(new AccountsWithCount(List.of(account), 1));

        service.payInterest(new PayInterestCommand(LocalDate.of(2026, 7, 21)));

        verify(accountRepository, never()).saveAccount(any());
    }

    @Test
    void does_nothing_when_there_are_no_target_accounts() {
        when(accountRepository.findAccounts(
                        new AccountFindQuery(
                                0, 100, null, null, List.of(AccountStatus.ACTIVE.name()))))
                .thenReturn(new AccountsWithCount(List.of(), 0));

        service.payInterest(new PayInterestCommand(LocalDate.of(2026, 7, 21)));

        verify(accountRepository, never()).saveAccount(any());
    }
}
