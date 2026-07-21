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
    """정기 이자 지급 배치(Task Queue)가 `Account.apply_interest()`를 통해 발행하는
    Domain Event. MoneyDeposited와 필드 구성이 같지만, 사용자가 직접 요청한 입금이 아니라
    시스템이 주기적으로 적용한 이자라는 사실을 이벤트 타입 자체로 구분한다
    (알림 이메일 문구도 MoneyDeposited와 다르게 렌더링된다)."""

    account_id: str
    transaction_id: str
    email: str
    amount: Money
    balance_after: Money
    created_at: datetime
