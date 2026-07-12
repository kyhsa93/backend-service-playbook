package com.example.accountservice.account.application.command;

import com.example.accountservice.account.domain.Account;
import com.example.accountservice.account.domain.AccountException;
import com.example.accountservice.account.domain.AccountFindQuery;
import com.example.accountservice.account.domain.AccountRepository;
import com.example.accountservice.account.domain.AccountsWithCount;
import com.example.accountservice.outbox.OutboxRelay;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DepositServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private OutboxRelay outboxRelay;

    private DepositService service;

    @BeforeEach
    void setUp() {
        service = new DepositService(accountRepository, outboxRelay);
    }

    @Test
    void 계좌가_존재하면_잔액이_증가하고_저장_및_Outbox_드레인이_일어난다() {
        Account account = Account.create("owner-1", "owner-1@example.com", "KRW");
        when(accountRepository.findAccounts(new AccountFindQuery(0, 1, account.getAccountId(), "owner-1", null)))
                .thenReturn(new AccountsWithCount(List.of(account), 1));

        TransactionResult result = service.deposit(new DepositCommand(account.getAccountId(), "owner-1", 500));

        assertThat(result.type()).isEqualTo("DEPOSIT");
        assertThat(account.getBalance().amount()).isEqualTo(500);
        verify(accountRepository).saveAccount(account);
        verify(outboxRelay).processPending();
    }

    @Test
    void 계좌가_존재하지_않으면_예외를_던진다() {
        when(accountRepository.findAccounts(new AccountFindQuery(0, 1, "non-existent", "owner-1", null)))
                .thenReturn(new AccountsWithCount(List.of(), 0));

        assertThatThrownBy(() -> service.deposit(new DepositCommand("non-existent", "owner-1", 500)))
                .isInstanceOf(AccountException.class)
                .extracting(e -> ((AccountException) e).code())
                .isEqualTo(AccountException.ErrorCode.ACCOUNT_NOT_FOUND);
    }

    @Test
    void 정지된_계좌면_예외를_던지고_저장하지_않는다() {
        Account account = Account.create("owner-1", "owner-1@example.com", "KRW");
        account.suspend();
        when(accountRepository.findAccounts(new AccountFindQuery(0, 1, account.getAccountId(), "owner-1", null)))
                .thenReturn(new AccountsWithCount(List.of(account), 1));

        assertThatThrownBy(() -> service.deposit(new DepositCommand(account.getAccountId(), "owner-1", 500)))
                .isInstanceOf(AccountException.class)
                .extracting(e -> ((AccountException) e).code())
                .isEqualTo(AccountException.ErrorCode.DEPOSIT_REQUIRES_ACTIVE_ACCOUNT);
        verify(accountRepository, never()).saveAccount(any());
    }
}
