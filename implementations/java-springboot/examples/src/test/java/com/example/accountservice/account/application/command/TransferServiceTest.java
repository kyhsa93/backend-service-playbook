package com.example.accountservice.account.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.accountservice.account.domain.Account;
import com.example.accountservice.account.domain.AccountException;
import com.example.accountservice.account.domain.AccountFindQuery;
import com.example.accountservice.account.domain.AccountRepository;
import com.example.accountservice.account.domain.AccountsWithCount;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

// TransferEligibilityService (a Domain Service) is a plain class, so it is not mocked here — this
// spec verifies that the Application layer loads both accounts, delegates to the actual
// eligibility logic, and, once approved, saves both accounts in a single transaction
// (saveAccounts).
@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    @Mock private AccountRepository accountRepository;

    private TransferService service;

    @BeforeEach
    void setUp() {
        service = new TransferService(accountRepository);
    }

    @Test
    void saves_both_accounts_in_one_transaction_when_approved() {
        Account source = Account.create("owner-1", "owner-1@example.com", "KRW");
        source.deposit(10000);
        Account target = Account.create("owner-2", "owner-2@example.com", "KRW");
        when(accountRepository.findAccounts(
                        new AccountFindQuery(0, 1, source.getAccountId(), "owner-1", null)))
                .thenReturn(new AccountsWithCount(List.of(source), 1));
        when(accountRepository.findAccounts(
                        new AccountFindQuery(0, 1, target.getAccountId(), null, null)))
                .thenReturn(new AccountsWithCount(List.of(target), 1));

        TransferResult result =
                service.transfer(
                        new TransferCommand(
                                source.getAccountId(), target.getAccountId(), "owner-1", 4000));

        assertThat(result.sourceTransaction().type()).isEqualTo("WITHDRAWAL");
        assertThat(result.targetTransaction().type()).isEqualTo("DEPOSIT");
        assertThat(source.getBalance().amount()).isEqualTo(6000);
        assertThat(target.getBalance().amount()).isEqualTo(4000);
        verify(accountRepository).saveAccounts(source, target);
    }

    @Test
    void throws_exception_and_does_not_save_when_source_account_is_not_found() {
        when(accountRepository.findAccounts(new AccountFindQuery(0, 1, "missing", "owner-1", null)))
                .thenReturn(new AccountsWithCount(List.of(), 0));

        assertThatThrownBy(
                        () ->
                                service.transfer(
                                        new TransferCommand(
                                                "missing", "account-2", "owner-1", 1000)))
                .isInstanceOf(AccountException.class)
                .extracting(e -> ((AccountException) e).code())
                .isEqualTo(AccountException.ErrorCode.ACCOUNT_NOT_FOUND);
        verify(accountRepository, never())
                .saveAccounts(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void throws_exception_and_does_not_save_when_balance_is_insufficient() {
        Account source = Account.create("owner-1", "owner-1@example.com", "KRW");
        source.deposit(1000);
        Account target = Account.create("owner-2", "owner-2@example.com", "KRW");
        when(accountRepository.findAccounts(
                        new AccountFindQuery(0, 1, source.getAccountId(), "owner-1", null)))
                .thenReturn(new AccountsWithCount(List.of(source), 1));
        when(accountRepository.findAccounts(
                        new AccountFindQuery(0, 1, target.getAccountId(), null, null)))
                .thenReturn(new AccountsWithCount(List.of(target), 1));

        assertThatThrownBy(
                        () ->
                                service.transfer(
                                        new TransferCommand(
                                                source.getAccountId(),
                                                target.getAccountId(),
                                                "owner-1",
                                                5000)))
                .isInstanceOf(AccountException.class)
                .extracting(e -> ((AccountException) e).code())
                .isEqualTo(AccountException.ErrorCode.INSUFFICIENT_BALANCE);
        verify(accountRepository, never())
                .saveAccounts(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
