from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime

from .money import Money


@dataclass(frozen=True)
class AccountCreated:
    account_id: str
    owner_id: str
    currency: str
    email: str
    created_at: datetime


@dataclass(frozen=True)
class MoneyDeposited:
    account_id: str
    transaction_id: str
    email: str
    amount: Money
    balance_after: Money
    created_at: datetime


@dataclass(frozen=True)
class MoneyWithdrawn:
    account_id: str
    transaction_id: str
    email: str
    amount: Money
    balance_after: Money
    created_at: datetime


@dataclass(frozen=True)
class AccountSuspended:
    account_id: str
    email: str
    suspended_at: datetime


@dataclass(frozen=True)
class AccountReactivated:
    account_id: str
    email: str
    reactivated_at: datetime


@dataclass(frozen=True)
class AccountClosed:
    account_id: str
    email: str
    closed_at: datetime


@dataclass(frozen=True)
class InterestPaid:
    """A Domain Event published by the regular interest-payment batch (Task Queue) via
    `Account.apply_interest()`. It has the same field composition as MoneyDeposited, but the
    event type itself distinguishes the fact that this is interest applied periodically by
    the system, not a deposit the user requested directly (the notification email text is
    also rendered differently from MoneyDeposited)."""

    account_id: str
    transaction_id: str
    email: str
    amount: Money
    balance_after: Money
    created_at: datetime
