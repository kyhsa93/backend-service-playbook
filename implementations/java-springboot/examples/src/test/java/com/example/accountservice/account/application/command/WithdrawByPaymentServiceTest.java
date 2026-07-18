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
class WithdrawByPaymentServiceTest {

    @Mock private AccountRepository accountRepository;

    private WithdrawByPaymentService service;

    @BeforeEach
    void setUp() {
        service = new WithdrawByPaymentService(accountRepository);
    }

    @Test
    void 처리되지_않은_결제완료_이벤트면_출금하고_저장이_일어난다() {
        Account account = Account.create("owner-1", "owner-1@example.com", "KRW");
        account.deposit(5000);
        when(accountRepository.hasTransactionWithReference("payment-1", TransactionType.WITHDRAWAL))
                .thenReturn(false);
        when(accountRepository.findAccounts(
                        new AccountFindQuery(0, 1, account.getAccountId(), null, null)))
                .thenReturn(new AccountsWithCount(List.of(account), 1));

        service.withdraw(new WithdrawByPaymentCommand(account.getAccountId(), 1000, "payment-1"));

        assertThat(account.getBalance().amount()).isEqualTo(4000);
        verify(accountRepository).saveAccount(account);
    }

    @Test
    void 이미_처리된_referenceId면_조용히_무시한다_멱등성() {
        when(accountRepository.hasTransactionWithReference("payment-1", TransactionType.WITHDRAWAL))
                .thenReturn(true);

        service.withdraw(new WithdrawByPaymentCommand("account-1", 1000, "payment-1"));

        verify(accountRepository, never()).findAccounts(any());
        verify(accountRepository, never()).saveAccount(any());
    }

    @Test
    void 대상_계좌가_없으면_조용히_무시한다() {
        when(accountRepository.hasTransactionWithReference("payment-1", TransactionType.WITHDRAWAL))
                .thenReturn(false);
        when(accountRepository.findAccounts(new AccountFindQuery(0, 1, "account-1", null, null)))
                .thenReturn(new AccountsWithCount(List.of(), 0));

        service.withdraw(new WithdrawByPaymentCommand("account-1", 1000, "payment-1"));

        verify(accountRepository, never()).saveAccount(any());
    }
}
