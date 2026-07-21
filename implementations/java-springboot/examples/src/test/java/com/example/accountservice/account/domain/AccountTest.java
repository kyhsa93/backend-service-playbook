package com.example.accountservice.account.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class AccountTest {

    private Account createAccount() {
        return Account.create("owner-1", "owner-1@example.com", "KRW");
    }

    @Test
    void 계좌_생성_시_잔액은_0이고_ACTIVE_상태다() {
        Account account = createAccount();

        assertThat(account.getBalance().amount()).isEqualTo(0);
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.pullDomainEvents())
                .hasSize(1)
                .first()
                .isInstanceOf(AccountCreatedEvent.class);
    }

    @Test
    void 계좌_ID는_하이픈_없는_32자리_hex_문자열이다() {
        Account account = createAccount();

        assertThat(account.getAccountId()).matches("^[0-9a-f]{32}$");
    }

    @Test
    void 정지된_계좌에_입금하면_예외를_던진다() {
        Account account = createAccount();
        account.suspend();

        assertThatThrownBy(() -> account.deposit(1000))
                .isInstanceOf(AccountException.class)
                .extracting(e -> ((AccountException) e).code())
                .isEqualTo(AccountException.ErrorCode.DEPOSIT_REQUIRES_ACTIVE_ACCOUNT);
    }

    @Test
    void 입금_금액이_0이하면_예외를_던진다() {
        Account account = createAccount();

        assertThatThrownBy(() -> account.deposit(0))
                .isInstanceOf(AccountException.class)
                .extracting(e -> ((AccountException) e).code())
                .isEqualTo(AccountException.ErrorCode.INVALID_AMOUNT);
    }

    @Test
    void 입금하면_MoneyDepositedEvent가_수집된다() {
        Account account = createAccount();
        account.pullDomainEvents();

        account.deposit(5000);

        var events = account.pullDomainEvents();
        assertThat(events).hasSize(1);
        assertThat(((MoneyDepositedEvent) events.get(0)).amount().amount()).isEqualTo(5000);
        assertThat(account.getBalance().amount()).isEqualTo(5000);
        assertThat(account.pullPendingTransactions().get(0).getTransactionId())
                .matches("^[0-9a-f]{32}$");
    }

    @Test
    void 정지된_계좌에서_출금하면_예외를_던진다() {
        Account account = createAccount();
        account.suspend();

        assertThatThrownBy(() -> account.withdraw(1000))
                .isInstanceOf(AccountException.class)
                .extracting(e -> ((AccountException) e).code())
                .isEqualTo(AccountException.ErrorCode.WITHDRAW_REQUIRES_ACTIVE_ACCOUNT);
    }

    @Test
    void 잔액보다_큰_금액을_출금하면_예외를_던진다() {
        Account account = createAccount();
        account.deposit(1000);

        assertThatThrownBy(() -> account.withdraw(2000))
                .isInstanceOf(AccountException.class)
                .extracting(e -> ((AccountException) e).code())
                .isEqualTo(AccountException.ErrorCode.INSUFFICIENT_BALANCE);
    }

    @Test
    void 출금하면_MoneyWithdrawnEvent가_수집된다() {
        Account account = createAccount();
        account.deposit(1000);
        account.pullDomainEvents();

        account.withdraw(400);

        var events = account.pullDomainEvents();
        assertThat(events).hasSize(1);
        assertThat(((MoneyWithdrawnEvent) events.get(0)).amount().amount()).isEqualTo(400);
        assertThat(account.getBalance().amount()).isEqualTo(600);
    }

    @Test
    void 정지하면_SUSPENDED_상태가_되고_AccountSuspendedEvent가_수집된다() {
        Account account = createAccount();
        account.pullDomainEvents();

        account.suspend();

        assertThat(account.getStatus()).isEqualTo(AccountStatus.SUSPENDED);
        assertThat(account.pullDomainEvents())
                .hasSize(1)
                .first()
                .isInstanceOf(AccountSuspendedEvent.class);
    }

    @Test
    void 이미_정지된_계좌를_정지하면_예외를_던진다() {
        Account account = createAccount();
        account.suspend();

        assertThatThrownBy(account::suspend)
                .isInstanceOf(AccountException.class)
                .extracting(e -> ((AccountException) e).code())
                .isEqualTo(AccountException.ErrorCode.SUSPEND_REQUIRES_ACTIVE_ACCOUNT);
    }

    @Test
    void 정지된_계좌를_재개하면_ACTIVE_상태가_되고_AccountReactivatedEvent가_수집된다() {
        Account account = createAccount();
        account.suspend();
        account.pullDomainEvents();

        account.reactivate();

        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.pullDomainEvents())
                .hasSize(1)
                .first()
                .isInstanceOf(AccountReactivatedEvent.class);
    }

    @Test
    void 활성_계좌를_재개하면_예외를_던진다() {
        Account account = createAccount();

        assertThatThrownBy(account::reactivate)
                .isInstanceOf(AccountException.class)
                .extracting(e -> ((AccountException) e).code())
                .isEqualTo(AccountException.ErrorCode.REACTIVATE_REQUIRES_SUSPENDED_ACCOUNT);
    }

    @Test
    void 잔액이_0이_아닌_계좌를_종료하면_예외를_던진다() {
        Account account = createAccount();
        account.deposit(1000);

        assertThatThrownBy(account::close)
                .isInstanceOf(AccountException.class)
                .extracting(e -> ((AccountException) e).code())
                .isEqualTo(AccountException.ErrorCode.ACCOUNT_BALANCE_NOT_ZERO);
    }

    @Test
    void 잔액이_0인_계좌를_종료하면_CLOSED_상태가_되고_AccountClosedEvent가_수집된다() {
        Account account = createAccount();
        account.pullDomainEvents();

        account.close();

        assertThat(account.getStatus()).isEqualTo(AccountStatus.CLOSED);
        assertThat(account.pullDomainEvents())
                .hasSize(1)
                .first()
                .isInstanceOf(AccountClosedEvent.class);
    }

    @Test
    void 이미_종료된_계좌를_종료하면_예외를_던진다() {
        Account account = createAccount();
        account.close();

        assertThatThrownBy(account::close)
                .isInstanceOf(AccountException.class)
                .extracting(e -> ((AccountException) e).code())
                .isEqualTo(AccountException.ErrorCode.ACCOUNT_ALREADY_CLOSED);
    }

    @Test
    void pullPendingTransactions_호출하면_대기중인_거래가_반환되고_비워진다() {
        Account account = createAccount();
        account.deposit(1000);

        var transactions = account.pullPendingTransactions();

        assertThat(transactions).hasSize(1);
        assertThat(account.pullPendingTransactions()).isEmpty();
    }

    @Test
    void 잔액이_충분하면_이자를_지급하고_INTEREST_거래를_남긴다() {
        Account account = createAccount();
        account.deposit(1_000_000);
        account.pullPendingTransactions();

        var result = account.payInterest(LocalDate.of(2026, 7, 21));

        assertThat(result).isPresent();
        assertThat(result.get().getType()).isEqualTo(TransactionType.INTEREST);
        assertThat(result.get().getAmount().amount()).isEqualTo(100); // 1_000_000 / 10_000
        assertThat(account.getBalance().amount()).isEqualTo(1_000_100);
        assertThat(account.getLastInterestPaidAt()).isEqualTo(LocalDate.of(2026, 7, 21));
        assertThat(account.pullPendingTransactions()).hasSize(1);
    }

    @Test
    void 같은_날_이자를_두번_지급하면_두번째는_아무_일도_하지_않는다() {
        Account account = createAccount();
        account.deposit(1_000_000);
        LocalDate today = LocalDate.of(2026, 7, 21);
        account.payInterest(today);

        var result = account.payInterest(today);

        assertThat(result).isEmpty();
        assertThat(account.getBalance().amount()).isEqualTo(1_000_100);
    }

    @Test
    void 잔액이_작아서_이자가_0으로_계산되면_거래를_남기지_않는다() {
        Account account = createAccount();
        account.deposit(50); // 50 / 10_000 = 0

        var result = account.payInterest(LocalDate.of(2026, 7, 21));

        assertThat(result).isEmpty();
        assertThat(account.getBalance().amount()).isEqualTo(50);
        assertThat(account.getLastInterestPaidAt()).isNull();
    }

    @Test
    void 정지된_계좌는_이자를_지급받지_않는다() {
        Account account = createAccount();
        account.deposit(1_000_000);
        account.suspend();

        var result = account.payInterest(LocalDate.of(2026, 7, 21));

        assertThat(result).isEmpty();
        assertThat(account.getBalance().amount()).isEqualTo(1_000_000);
    }

    @Test
    void 다음날에는_다시_이자를_지급받을_수_있다() {
        Account account = createAccount();
        account.deposit(1_000_000);
        account.payInterest(LocalDate.of(2026, 7, 21));

        var result = account.payInterest(LocalDate.of(2026, 7, 22));

        assertThat(result).isPresent();
        assertThat(account.getBalance().amount()).isEqualTo(1_000_200);
    }
}
