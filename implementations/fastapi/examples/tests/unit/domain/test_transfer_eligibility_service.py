from src.account.domain.account import Account
from src.account.domain.errors import (
    CurrencyMismatchError,
    DepositRequiresActiveAccountError,
    InsufficientBalanceError,
    TransferSameAccountError,
    WithdrawRequiresActiveAccountError,
)
from src.account.domain.transfer_eligibility_service import TransferEligibilityService


def make_funded_account(currency: str = "KRW", amount: int = 0) -> Account:
    account = Account.create(owner_id="owner-1", currency=currency, email="owner1@example.com")
    if amount > 0:
        account.deposit(amount)
    return account


def test_evaluate_approved_when_all_conditions_are_satisfied() -> None:
    source = make_funded_account(amount=10000)
    target = make_funded_account(amount=0)

    decision = TransferEligibilityService().evaluate(source, target, 5000)

    assert decision.approved is True
    assert decision.error is None


def test_evaluate_rejected_when_withdrawal_and_deposit_accounts_are_the_same() -> None:
    source = make_funded_account(amount=10000)

    decision = TransferEligibilityService().evaluate(source, source, 5000)

    assert decision.approved is False
    assert isinstance(decision.error, TransferSameAccountError)


def test_evaluate_rejected_when_withdrawal_account_is_inactive() -> None:
    source = make_funded_account(amount=10000)
    source.suspend()
    target = make_funded_account(amount=0)

    decision = TransferEligibilityService().evaluate(source, target, 5000)

    assert decision.approved is False
    assert isinstance(decision.error, WithdrawRequiresActiveAccountError)


def test_evaluate_rejected_when_deposit_account_is_inactive() -> None:
    source = make_funded_account(amount=10000)
    target = make_funded_account(amount=0)
    target.suspend()

    decision = TransferEligibilityService().evaluate(source, target, 5000)

    assert decision.approved is False
    assert isinstance(decision.error, DepositRequiresActiveAccountError)


def test_evaluate_rejected_when_currencies_do_not_match() -> None:
    source = make_funded_account(currency="KRW", amount=10000)
    target = make_funded_account(currency="USD", amount=0)

    decision = TransferEligibilityService().evaluate(source, target, 5000)

    assert decision.approved is False
    assert isinstance(decision.error, CurrencyMismatchError)


def test_evaluate_rejected_when_withdrawal_account_balance_is_insufficient() -> None:
    source = make_funded_account(amount=1000)
    target = make_funded_account(amount=0)

    decision = TransferEligibilityService().evaluate(source, target, 5000)

    assert decision.approved is False
    assert isinstance(decision.error, InsufficientBalanceError)
