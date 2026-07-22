from __future__ import annotations

from dataclasses import dataclass

from .account import Account
from .account_status import AccountStatus
from .errors import (
    AccountError,
    CurrencyMismatchError,
    DepositRequiresActiveAccountError,
    InsufficientBalanceError,
    TransferSameAccountError,
    WithdrawRequiresActiveAccountError,
)


@dataclass(frozen=True)
class TransferDecision:
    approved: bool
    # Rather than following the existing RefundDecision's `reason: str | None` shape as-is,
    # this holds the actual AccountError instance on rejection — unlike Refund, Transfer has
    # no persisted Aggregate of its own (there's nowhere to store a rejection), so a
    # rejection must be thrown immediately as an exception, and that exception must be
    # identical to what a user gets by calling withdraw/deposit directly. This is an
    # intentional difference, so it is not reshaped back into RefundDecision's form.
    error: AccountError | None = None


class TransferEligibilityService:
    """A Domain Service — a pure class with no framework dependency. It is never registered
    in any DI container such as FastAPI's Depends — the Application layer instantiates it
    directly whenever needed (the same reason as RefundEligibilityService).

    The decision "the withdrawal account and deposit account are different, both are
    active, the currencies match, and the withdrawal account's balance is sufficient" cannot
    be made from either Account instance alone — both Aggregate instances must be loaded and
    compared in the same place.
    """

    def evaluate(self, source: Account, target: Account, amount: int) -> TransferDecision:
        if source.account_id == target.account_id:
            return TransferDecision(approved=False, error=TransferSameAccountError())
        if source.status != AccountStatus.ACTIVE:
            return TransferDecision(approved=False, error=WithdrawRequiresActiveAccountError())
        if target.status != AccountStatus.ACTIVE:
            return TransferDecision(approved=False, error=DepositRequiresActiveAccountError())
        if source.balance.currency != target.balance.currency:
            return TransferDecision(approved=False, error=CurrencyMismatchError())
        if source.balance.amount < amount:
            return TransferDecision(approved=False, error=InsufficientBalanceError())
        return TransferDecision(approved=True)
