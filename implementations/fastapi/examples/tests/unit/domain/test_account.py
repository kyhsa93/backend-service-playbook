import re
from datetime import date
from decimal import Decimal

import pytest

from src.account.domain.account import Account
from src.account.domain.errors import (
    AccountAlreadyClosedError,
    AccountBalanceNotZeroError,
    DepositRequiresActiveAccountError,
    InsufficientBalanceError,
    InvalidAmountError,
    ReactivateRequiresSuspendedAccountError,
    SuspendRequiresActiveAccountError,
    WithdrawRequiresActiveAccountError,
)
from src.account.domain.events import (
    AccountClosed,
    AccountCreated,
    AccountReactivated,
    AccountSuspended,
    InterestPaid,
    MoneyDeposited,
    MoneyWithdrawn,
)


def make_active_account(currency: str = "KRW") -> Account:
    return Account.create(owner_id="owner-1", currency=currency, email="owner1@example.com")


def test_create_collects_an_AccountCreated_event_on_account_creation() -> None:
    account = make_active_account()

    events = account.pull_events()

    assert len(events) == 1
    assert isinstance(events[0], AccountCreated)
    assert events[0].owner_id == "owner-1"
    assert account.balance.amount == 0


def test_create_account_id_is_a_32_char_hex_string_without_hyphens() -> None:
    account = make_active_account()

    assert re.fullmatch(r"[0-9a-f]{32}", account.account_id)


def test_deposit_raises_InvalidAmountError_for_a_non_positive_amount() -> None:
    account = make_active_account()
    account.pull_events()

    with pytest.raises(InvalidAmountError):
        account.deposit(0)


def test_deposit_raises_an_error_when_depositing_into_a_suspended_account() -> None:
    account = make_active_account()
    account.suspend()

    with pytest.raises(DepositRequiresActiveAccountError):
        account.deposit(1000)


def test_deposit_increases_balance_and_collects_a_MoneyDeposited_event_on_success() -> None:
    account = make_active_account()
    account.pull_events()

    account.deposit(10000)

    assert account.balance.amount == 10000
    events = account.pull_events()
    assert any(isinstance(e, MoneyDeposited) for e in events)
    transaction = account.pull_pending_transactions()[0]
    assert re.fullmatch(r"[0-9a-f]{32}", transaction.transaction_id)


def test_withdraw_raises_InsufficientBalanceError_when_balance_is_insufficient() -> None:
    account = make_active_account()

    with pytest.raises(InsufficientBalanceError):
        account.withdraw(1000)


def test_withdraw_raises_an_error_when_withdrawing_from_a_suspended_account() -> None:
    account = make_active_account()
    account.suspend()

    with pytest.raises(WithdrawRequiresActiveAccountError):
        account.withdraw(1000)


def test_withdraw_decreases_balance_and_collects_a_MoneyWithdrawn_event_on_success() -> None:
    account = make_active_account()
    account.deposit(1000)
    account.pull_events()

    account.withdraw(400)

    assert account.balance.amount == 600
    events = account.pull_events()
    assert any(isinstance(e, MoneyWithdrawn) for e in events)


def test_suspend_an_active_account_becomes_suspended_and_collects_an_AccountSuspended_event() -> None:
    account = make_active_account()
    account.pull_events()

    account.suspend()

    events = account.pull_events()
    assert any(isinstance(e, AccountSuspended) for e in events)


def test_suspend_raises_an_error_for_an_already_suspended_account() -> None:
    account = make_active_account()
    account.suspend()

    with pytest.raises(SuspendRequiresActiveAccountError):
        account.suspend()


def test_reactivate_a_suspended_account_collects_an_AccountReactivated_event() -> None:
    account = make_active_account()
    account.suspend()
    account.pull_events()

    account.reactivate()

    events = account.pull_events()
    assert any(isinstance(e, AccountReactivated) for e in events)


def test_reactivate_raises_an_error_for_an_already_active_account() -> None:
    account = make_active_account()

    with pytest.raises(ReactivateRequiresSuspendedAccountError):
        account.reactivate()


def test_close_cannot_close_when_balance_is_not_zero() -> None:
    account = make_active_account()
    account.deposit(5000)

    with pytest.raises(AccountBalanceNotZeroError):
        account.close()


def test_close_closes_and_collects_an_AccountClosed_event_when_balance_is_zero() -> None:
    account = make_active_account()
    account.pull_events()

    account.close()

    events = account.pull_events()
    assert any(isinstance(e, AccountClosed) for e in events)


def test_close_raises_an_error_for_an_already_closed_account() -> None:
    account = make_active_account()
    account.close()

    with pytest.raises(AccountAlreadyClosedError):
        account.close()


def test_pull_pending_transactions_returns_and_clears_pending_transactions() -> None:
    account = make_active_account()
    account.deposit(1000)

    transactions = account.pull_pending_transactions()

    assert len(transactions) == 1
    assert account.pull_pending_transactions() == []


def test_apply_interest_pays_the_balance_times_rate_amount_rounded_down() -> None:
    account = make_active_account()
    account.deposit(1000000)
    account.pull_events()
    account.pull_pending_transactions()

    transaction = account.apply_interest(Decimal("0.0001"), date(2026, 7, 21))

    # 1_000_000 * 0.0001 = 100 (no rounding needed, an exact value)
    assert transaction is not None
    assert transaction.type == "INTEREST"
    assert transaction.amount.amount == 100
    assert account.balance.amount == 1000100
    assert account.last_interest_paid_at == date(2026, 7, 21)
    events = account.pull_events()
    assert any(isinstance(e, InterestPaid) for e in events)


def test_apply_interest_rounds_down_the_fractional_part() -> None:
    account = make_active_account()
    account.deposit(12345)
    account.pull_events()
    account.pull_pending_transactions()

    # 12345 * 0.0001 = 1.2345 -> floors to 1
    transaction = account.apply_interest(Decimal("0.0001"), date(2026, 7, 21))

    assert transaction is not None
    assert transaction.amount.amount == 1


def test_apply_interest_skips_payment_but_records_todays_processing_when_computed_interest_is_zero() -> None:
    account = make_active_account()
    account.deposit(1)
    account.pull_events()
    account.pull_pending_transactions()

    # 1 * 0.0001 = 0.0001 -> floors to 0
    transaction = account.apply_interest(Decimal("0.0001"), date(2026, 7, 21))

    assert transaction is None
    assert account.balance.amount == 1
    assert account.last_interest_paid_at == date(2026, 7, 21)
    assert account.pull_events() == []


def test_apply_interest_calling_twice_on_the_same_day_makes_the_second_call_a_complete_no_op() -> None:
    account = make_active_account()
    account.deposit(1000000)
    account.pull_events()
    account.pull_pending_transactions()
    account.apply_interest(Decimal("0.0001"), date(2026, 7, 21))
    account.pull_events()
    account.pull_pending_transactions()
    balance_after_first_run = account.balance.amount

    transaction = account.apply_interest(Decimal("0.0001"), date(2026, 7, 21))

    assert transaction is None
    assert account.balance.amount == balance_after_first_run
    assert account.pull_events() == []
    assert account.pull_pending_transactions() == []


def test_apply_interest_a_suspended_account_receives_no_interest() -> None:
    account = make_active_account()
    account.deposit(1000000)
    account.suspend()
    account.pull_events()

    transaction = account.apply_interest(Decimal("0.0001"), date(2026, 7, 21))

    assert transaction is None
    assert account.last_interest_paid_at is None
    assert account.pull_events() == []
