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
    void creating_account_starts_with_zero_balance_and_ACTIVE_status() {
        Account account = createAccount();

        assertThat(account.getBalance().amount()).isEqualTo(0);
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.pullDomainEvents())
                .hasSize(1)
                .first()
                .isInstanceOf(AccountCreatedEvent.class);
    }

    @Test
    void account_id_is_a_32_character_hex_string_with_no_hyphens() {
        Account account = createAccount();

        assertThat(account.getAccountId()).matches("^[0-9a-f]{32}$");
    }

    @Test
    void throws_exception_when_depositing_to_a_suspended_account() {
        Account account = createAccount();
        account.suspend();

        assertThatThrownBy(() -> account.deposit(1000))
                .isInstanceOf(AccountException.class)
                .extracting(e -> ((AccountException) e).code())
                .isEqualTo(AccountException.ErrorCode.DEPOSIT_REQUIRES_ACTIVE_ACCOUNT);
    }

    @Test
    void throws_exception_when_deposit_amount_is_zero_or_less() {
        Account account = createAccount();

        assertThatThrownBy(() -> account.deposit(0))
                .isInstanceOf(AccountException.class)
                .extracting(e -> ((AccountException) e).code())
                .isEqualTo(AccountException.ErrorCode.INVALID_AMOUNT);
    }

    @Test
    void collects_MoneyDepositedEvent_on_deposit() {
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
    void throws_exception_when_withdrawing_from_a_suspended_account() {
        Account account = createAccount();
        account.suspend();

        assertThatThrownBy(() -> account.withdraw(1000))
                .isInstanceOf(AccountException.class)
                .extracting(e -> ((AccountException) e).code())
                .isEqualTo(AccountException.ErrorCode.WITHDRAW_REQUIRES_ACTIVE_ACCOUNT);
    }

    @Test
    void throws_exception_when_withdrawing_more_than_the_balance() {
        Account account = createAccount();
        account.deposit(1000);

        assertThatThrownBy(() -> account.withdraw(2000))
                .isInstanceOf(AccountException.class)
                .extracting(e -> ((AccountException) e).code())
                .isEqualTo(AccountException.ErrorCode.INSUFFICIENT_BALANCE);
    }

    @Test
    void collects_MoneyWithdrawnEvent_on_withdrawal() {
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
    void suspending_moves_to_SUSPENDED_status_and_collects_AccountSuspendedEvent() {
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
    void throws_exception_when_suspending_an_already_suspended_account() {
        Account account = createAccount();
        account.suspend();

        assertThatThrownBy(account::suspend)
                .isInstanceOf(AccountException.class)
                .extracting(e -> ((AccountException) e).code())
                .isEqualTo(AccountException.ErrorCode.SUSPEND_REQUIRES_ACTIVE_ACCOUNT);
    }

    @Test
    void
            reactivating_a_suspended_account_moves_to_ACTIVE_status_and_collects_AccountReactivatedEvent() {
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
    void throws_exception_when_reactivating_an_active_account() {
        Account account = createAccount();

        assertThatThrownBy(account::reactivate)
                .isInstanceOf(AccountException.class)
                .extracting(e -> ((AccountException) e).code())
                .isEqualTo(AccountException.ErrorCode.REACTIVATE_REQUIRES_SUSPENDED_ACCOUNT);
    }

    @Test
    void throws_exception_when_closing_an_account_with_non_zero_balance() {
        Account account = createAccount();
        account.deposit(1000);

        assertThatThrownBy(account::close)
                .isInstanceOf(AccountException.class)
                .extracting(e -> ((AccountException) e).code())
                .isEqualTo(AccountException.ErrorCode.ACCOUNT_BALANCE_NOT_ZERO);
    }

    @Test
    void closing_a_zero_balance_account_moves_to_CLOSED_status_and_collects_AccountClosedEvent() {
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
    void throws_exception_when_closing_an_already_closed_account() {
        Account account = createAccount();
        account.close();

        assertThatThrownBy(account::close)
                .isInstanceOf(AccountException.class)
                .extracting(e -> ((AccountException) e).code())
                .isEqualTo(AccountException.ErrorCode.ACCOUNT_ALREADY_CLOSED);
    }

    @Test
    void calling_pullPendingTransactions_returns_and_clears_pending_transactions() {
        Account account = createAccount();
        account.deposit(1000);

        var transactions = account.pullPendingTransactions();

        assertThat(transactions).hasSize(1);
        assertThat(account.pullPendingTransactions()).isEmpty();
    }

    @Test
    void pays_interest_and_leaves_an_INTEREST_transaction_when_balance_is_sufficient() {
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
    void paying_interest_twice_on_the_same_day_does_nothing_the_second_time() {
        Account account = createAccount();
        account.deposit(1_000_000);
        LocalDate today = LocalDate.of(2026, 7, 21);
        account.payInterest(today);

        var result = account.payInterest(today);

        assertThat(result).isEmpty();
        assertThat(account.getBalance().amount()).isEqualTo(1_000_100);
    }

    @Test
    void leaves_no_transaction_when_computed_interest_is_zero_due_to_small_balance() {
        Account account = createAccount();
        account.deposit(50); // 50 / 10_000 = 0

        var result = account.payInterest(LocalDate.of(2026, 7, 21));

        assertThat(result).isEmpty();
        assertThat(account.getBalance().amount()).isEqualTo(50);
        assertThat(account.getLastInterestPaidAt()).isNull();
    }

    @Test
    void a_suspended_account_does_not_receive_interest() {
        Account account = createAccount();
        account.deposit(1_000_000);
        account.suspend();

        var result = account.payInterest(LocalDate.of(2026, 7, 21));

        assertThat(result).isEmpty();
        assertThat(account.getBalance().amount()).isEqualTo(1_000_000);
    }

    @Test
    void can_receive_interest_again_on_the_next_day() {
        Account account = createAccount();
        account.deposit(1_000_000);
        account.payInterest(LocalDate.of(2026, 7, 21));

        var result = account.payInterest(LocalDate.of(2026, 7, 22));

        assertThat(result).isPresent();
        assertThat(account.getBalance().amount()).isEqualTo(1_000_200);
    }
}
