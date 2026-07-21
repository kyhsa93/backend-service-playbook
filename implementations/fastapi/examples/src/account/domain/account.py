from __future__ import annotations

from datetime import date, datetime
from decimal import ROUND_FLOOR, Decimal
from typing import Union

from ...common.generate_id import generate_id
from .account_status import AccountStatus
from .errors import (
    AccountAlreadyClosedError,
    AccountBalanceNotZeroError,
    DepositRequiresActiveAccountError,
    InsufficientBalanceError,
    InvalidAmountError,
    ReactivateRequiresSuspendedAccountError,
    SuspendRequiresActiveAccountError,
    WithdrawRequiresActiveAccountError,
)
from .events import (
    AccountClosed,
    AccountCreated,
    AccountReactivated,
    AccountSuspended,
    InterestPaid,
    MoneyDeposited,
    MoneyWithdrawn,
)
from .money import Money
from .transaction import Transaction

AccountDomainEvent = Union[
    AccountCreated, MoneyDeposited, MoneyWithdrawn, AccountSuspended, AccountReactivated, AccountClosed, InterestPaid
]


class Account:
    def __init__(
        self,
        account_id: str,
        owner_id: str,
        email: str,
        balance: Money,
        status: AccountStatus,
        created_at: datetime,
        updated_at: datetime,
        last_interest_paid_at: date | None = None,
    ) -> None:
        self.account_id = account_id
        self.owner_id = owner_id
        self.email = email
        self.balance = balance
        self.status = status
        self.created_at = created_at
        self.updated_at = updated_at
        # 정기 이자 지급 배치(scheduling.md)의 멱등성 마커 — "오늘 이미 이자를 지급받았는가"를
        # Aggregate 상태만으로 판단한다(Level 1 본질적 멱등, domain-events.md "이벤트 핸들러
        # 멱등성" 참고). 별도 Ledger 테이블 없이 이 필드 하나로 at-least-once 재전달을
        # 안전하게 무시할 수 있다 — apply_interest() 참고.
        self.last_interest_paid_at = last_interest_paid_at
        self._events: list[AccountDomainEvent] = []
        self._pending_transactions: list[Transaction] = []

    @classmethod
    def create(cls, owner_id: str, currency: str, email: str) -> Account:
        now = datetime.utcnow()
        account = cls(
            account_id=generate_id(),
            owner_id=owner_id,
            email=email,
            balance=Money(0, currency),
            status=AccountStatus.ACTIVE,
            created_at=now,
            updated_at=now,
        )
        account._events.append(
            AccountCreated(
                account_id=account.account_id,
                owner_id=owner_id,
                currency=currency,
                email=email,
                created_at=now,
            )
        )
        return account

    def deposit(self, amount: int, reference_id: str | None = None) -> Transaction:
        if self.status != AccountStatus.ACTIVE:
            raise DepositRequiresActiveAccountError()
        if amount <= 0:
            raise InvalidAmountError()
        money = Money(amount, self.balance.currency)
        self.balance = self.balance.add(money)
        self.updated_at = datetime.utcnow()
        transaction = Transaction.create(self.account_id, "DEPOSIT", money, reference_id=reference_id)
        self._pending_transactions.append(transaction)
        self._events.append(
            MoneyDeposited(
                account_id=self.account_id,
                transaction_id=transaction.transaction_id,
                email=self.email,
                amount=money,
                balance_after=self.balance,
                created_at=transaction.created_at,
            )
        )
        return transaction

    def withdraw(self, amount: int, reference_id: str | None = None) -> Transaction:
        if self.status != AccountStatus.ACTIVE:
            raise WithdrawRequiresActiveAccountError()
        if amount <= 0:
            raise InvalidAmountError()
        money = Money(amount, self.balance.currency)
        if self.balance.is_less_than(money):
            raise InsufficientBalanceError()
        self.balance = self.balance.subtract(money)
        self.updated_at = datetime.utcnow()
        transaction = Transaction.create(self.account_id, "WITHDRAWAL", money, reference_id=reference_id)
        self._pending_transactions.append(transaction)
        self._events.append(
            MoneyWithdrawn(
                account_id=self.account_id,
                transaction_id=transaction.transaction_id,
                email=self.email,
                amount=money,
                balance_after=self.balance,
                created_at=transaction.created_at,
            )
        )
        return transaction

    def apply_interest(self, daily_rate: Decimal, today: date) -> Transaction | None:
        """정기 이자 지급 배치(Task Queue → account.interest.apply)가 시스템 주도로 호출하는
        Aggregate 메서드다. 사용자가 직접 요청하는 것이 아니므로 deposit()과 달리 기존
        커맨드 표면을 거치지 않는다 — 배치 Command Handler가 이 메서드를 직접 호출한다.

        멱등성은 `last_interest_paid_at`(오늘 이미 처리했는가) 필드 하나로 보장한다(Level 1).
        오늘 이미 지급했다면 완전한 no-op(필드조차 건드리지 않는다) — Task 메시지가
        at-least-once로 재전달돼도 안전하다. 계산된 이자가 0원이면(잔액이 매우 작은 경우)
        크레딧은 생략하지만, "오늘 처리를 마쳤다"는 사실 자체는 기록해 같은 날 재계산을
        막는다.
        """
        if self.status != AccountStatus.ACTIVE:
            return None
        if self.last_interest_paid_at == today:
            return None

        self.last_interest_paid_at = today
        interest_amount = int((Decimal(self.balance.amount) * daily_rate).to_integral_value(rounding=ROUND_FLOOR))
        if interest_amount <= 0:
            return None

        money = Money(interest_amount, self.balance.currency)
        self.balance = self.balance.add(money)
        self.updated_at = datetime.utcnow()
        transaction = Transaction.create(self.account_id, "INTEREST", money)
        self._pending_transactions.append(transaction)
        self._events.append(
            InterestPaid(
                account_id=self.account_id,
                transaction_id=transaction.transaction_id,
                email=self.email,
                amount=money,
                balance_after=self.balance,
                created_at=transaction.created_at,
            )
        )
        return transaction

    def suspend(self) -> None:
        if self.status != AccountStatus.ACTIVE:
            raise SuspendRequiresActiveAccountError()
        self.status = AccountStatus.SUSPENDED
        self.updated_at = datetime.utcnow()
        self._events.append(
            AccountSuspended(account_id=self.account_id, email=self.email, suspended_at=self.updated_at)
        )

    def reactivate(self) -> None:
        if self.status != AccountStatus.SUSPENDED:
            raise ReactivateRequiresSuspendedAccountError()
        self.status = AccountStatus.ACTIVE
        self.updated_at = datetime.utcnow()
        self._events.append(
            AccountReactivated(account_id=self.account_id, email=self.email, reactivated_at=self.updated_at)
        )

    def close(self) -> None:
        if self.status == AccountStatus.CLOSED:
            raise AccountAlreadyClosedError()
        if not self.balance.is_zero():
            raise AccountBalanceNotZeroError()
        self.status = AccountStatus.CLOSED
        self.updated_at = datetime.utcnow()
        self._events.append(AccountClosed(account_id=self.account_id, email=self.email, closed_at=self.updated_at))

    def pull_events(self) -> list[AccountDomainEvent]:
        events, self._events = self._events, []
        return events

    def pull_pending_transactions(self) -> list[Transaction]:
        transactions, self._pending_transactions = self._pending_transactions, []
        return transactions
