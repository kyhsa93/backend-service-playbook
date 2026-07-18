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
    void 처리되지_않은_보상크레딧_이벤트면_입금하고_저장이_일어난다() {
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
    void 결제완료_출금과_같은_paymentId를_참조해도_type이_다르면_중복처리로_판단하지_않는다() {
        // 결제완료(WITHDRAWAL)와 그 결제취소 보상 크레딧(DEPOSIT)은 같은 paymentId를 referenceId로 공유하는
        // 서로 다른 거래다 — hasTransactionWithReference가 (referenceId, type) 조합으로 확인하므로,
        // WITHDRAWAL이 이미 존재해도 DEPOSIT 여부는 별도로 확인되어야 한다(이 테스트는 그 호출 스코프를 검증한다).
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
    void 이미_처리된_referenceId면_조용히_무시한다_멱등성() {
        when(accountRepository.hasTransactionWithReference("payment-1", TransactionType.DEPOSIT))
                .thenReturn(true);

        service.deposit(new DepositByPaymentCommand("account-1", 1000, "payment-1"));

        verify(accountRepository, never()).findAccounts(any());
        verify(accountRepository, never()).saveAccount(any());
    }
}
