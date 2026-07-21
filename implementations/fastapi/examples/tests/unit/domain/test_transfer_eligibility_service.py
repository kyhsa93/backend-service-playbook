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


def test_evaluate_모든_조건을_만족하면_승인된다() -> None:
    source = make_funded_account(amount=10000)
    target = make_funded_account(amount=0)

    decision = TransferEligibilityService().evaluate(source, target, 5000)

    assert decision.approved is True
    assert decision.error is None


def test_evaluate_출금_계좌와_입금_계좌가_같으면_거부된다() -> None:
    source = make_funded_account(amount=10000)

    decision = TransferEligibilityService().evaluate(source, source, 5000)

    assert decision.approved is False
    assert isinstance(decision.error, TransferSameAccountError)


def test_evaluate_출금_계좌가_비활성이면_거부된다() -> None:
    source = make_funded_account(amount=10000)
    source.suspend()
    target = make_funded_account(amount=0)

    decision = TransferEligibilityService().evaluate(source, target, 5000)

    assert decision.approved is False
    assert isinstance(decision.error, WithdrawRequiresActiveAccountError)


def test_evaluate_입금_계좌가_비활성이면_거부된다() -> None:
    source = make_funded_account(amount=10000)
    target = make_funded_account(amount=0)
    target.suspend()

    decision = TransferEligibilityService().evaluate(source, target, 5000)

    assert decision.approved is False
    assert isinstance(decision.error, DepositRequiresActiveAccountError)


def test_evaluate_통화가_일치하지_않으면_거부된다() -> None:
    source = make_funded_account(currency="KRW", amount=10000)
    target = make_funded_account(currency="USD", amount=0)

    decision = TransferEligibilityService().evaluate(source, target, 5000)

    assert decision.approved is False
    assert isinstance(decision.error, CurrencyMismatchError)


def test_evaluate_출금_계좌_잔액이_부족하면_거부된다() -> None:
    source = make_funded_account(amount=1000)
    target = make_funded_account(amount=0)

    decision = TransferEligibilityService().evaluate(source, target, 5000)

    assert decision.approved is False
    assert isinstance(decision.error, InsufficientBalanceError)
