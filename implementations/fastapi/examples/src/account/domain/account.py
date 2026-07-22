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
        # An idempotency marker for the regular interest-payment batch (scheduling.md) —
        # decides "has interest already been paid today" using only the Aggregate's own
        # state (Level 1 intrinsic idempotency, see "Event Handler Idempotency" in
        # domain-events.md). With no separate Ledger table, this single field lets an
        # at-least-once redelivery be safely ignored — see apply_interest().
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
        """An Aggregate method invoked system-driven by the regular interest-payment batch
        (Task Queue → account.interest.apply). Since the user doesn't request this directly,
        unlike deposit() it doesn't go through the existing command surface — the batch's
        Command Handler calls this method directly.

        Idempotency is guaranteed by a single field, `last_interest_paid_at` (has it already
        been processed today) (Level 1). If interest was already paid today, this is a
        complete no-op (it doesn't even touch the field) — safe even if the Task message is
        redelivered at-least-once. If the computed interest is 0 (when the balance is very
        small), the credit is skipped, but the fact itself that "today's processing is done"
        is still recorded, preventing recomputation on the same day.
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
