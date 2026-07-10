import re

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
    MoneyDeposited,
    MoneyWithdrawn,
)


def make_active_account(currency: str = "KRW") -> Account:
    return Account.create(owner_id="owner-1", currency=currency, email="owner1@example.com")


def test_create_계좌_생성_시_AccountCreated_이벤트가_수집된다() -> None:
    account = make_active_account()

    events = account.pull_events()

    assert len(events) == 1
    assert isinstance(events[0], AccountCreated)
    assert events[0].owner_id == "owner-1"
    assert account.balance.amount == 0


def test_create_계좌_ID는_하이픈_없는_32자리_hex_문자열이다() -> None:
    account = make_active_account()

    assert re.fullmatch(r"[0-9a-f]{32}", account.account_id)


def test_deposit_0원_이하_입금_시_InvalidAmountError를_던진다() -> None:
    account = make_active_account()
    account.pull_events()

    with pytest.raises(InvalidAmountError):
        account.deposit(0)


def test_deposit_정지된_계좌에_입금_시_에러를_던진다() -> None:
    account = make_active_account()
    account.suspend()

    with pytest.raises(DepositRequiresActiveAccountError):
        account.deposit(1000)


def test_deposit_성공_시_잔액이_증가하고_MoneyDeposited_이벤트가_수집된다() -> None:
    account = make_active_account()
    account.pull_events()

    account.deposit(10000)

    assert account.balance.amount == 10000
    events = account.pull_events()
    assert any(isinstance(e, MoneyDeposited) for e in events)
    transaction = account.pull_pending_transactions()[0]
    assert re.fullmatch(r"[0-9a-f]{32}", transaction.transaction_id)


def test_withdraw_잔액_부족_시_InsufficientBalanceError를_던진다() -> None:
    account = make_active_account()

    with pytest.raises(InsufficientBalanceError):
        account.withdraw(1000)


def test_withdraw_정지된_계좌에서_출금_시_에러를_던진다() -> None:
    account = make_active_account()
    account.suspend()

    with pytest.raises(WithdrawRequiresActiveAccountError):
        account.withdraw(1000)


def test_withdraw_성공_시_잔액이_감소하고_MoneyWithdrawn_이벤트가_수집된다() -> None:
    account = make_active_account()
    account.deposit(1000)
    account.pull_events()

    account.withdraw(400)

    assert account.balance.amount == 600
    events = account.pull_events()
    assert any(isinstance(e, MoneyWithdrawn) for e in events)


def test_suspend_활성_계좌를_정지하면_SUSPENDED_상태가_되고_AccountSuspended_이벤트가_수집된다() -> None:
    account = make_active_account()
    account.pull_events()

    account.suspend()

    events = account.pull_events()
    assert any(isinstance(e, AccountSuspended) for e in events)


def test_suspend_이미_정지된_계좌를_정지하면_에러를_던진다() -> None:
    account = make_active_account()
    account.suspend()

    with pytest.raises(SuspendRequiresActiveAccountError):
        account.suspend()


def test_reactivate_정지된_계좌를_재개하면_AccountReactivated_이벤트가_수집된다() -> None:
    account = make_active_account()
    account.suspend()
    account.pull_events()

    account.reactivate()

    events = account.pull_events()
    assert any(isinstance(e, AccountReactivated) for e in events)


def test_reactivate_활성_계좌를_재개하면_에러를_던진다() -> None:
    account = make_active_account()

    with pytest.raises(ReactivateRequiresSuspendedAccountError):
        account.reactivate()


def test_close_잔액이_0이_아니면_종료할_수_없다() -> None:
    account = make_active_account()
    account.deposit(5000)

    with pytest.raises(AccountBalanceNotZeroError):
        account.close()


def test_close_잔액이_0이면_종료되고_AccountClosed_이벤트가_수집된다() -> None:
    account = make_active_account()
    account.pull_events()

    account.close()

    events = account.pull_events()
    assert any(isinstance(e, AccountClosed) for e in events)


def test_close_이미_종료된_계좌를_종료하면_에러를_던진다() -> None:
    account = make_active_account()
    account.close()

    with pytest.raises(AccountAlreadyClosedError):
        account.close()


def test_pull_pending_transactions_호출하면_대기중인_거래가_반환되고_비워진다() -> None:
    account = make_active_account()
    account.deposit(1000)

    transactions = account.pull_pending_transactions()

    assert len(transactions) == 1
    assert account.pull_pending_transactions() == []
