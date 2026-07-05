from __future__ import annotations

import uuid
from datetime import datetime
from typing import Union

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
    MoneyDeposited,
    MoneyWithdrawn,
)
from .money import Money
from .transaction import Transaction

AccountDomainEvent = Union[
    AccountCreated, MoneyDeposited, MoneyWithdrawn, AccountSuspended, AccountReactivated, AccountClosed
]


class Account:
    def __init__(
        self,
        account_id: str,
        owner_id: str,
        balance: Money,
        status: AccountStatus,
        created_at: datetime,
        updated_at: datetime,
    ) -> None:
        self.account_id = account_id
        self.owner_id = owner_id
        self.balance = balance
        self.status = status
        self.created_at = created_at
        self.updated_at = updated_at
        self._events: list[AccountDomainEvent] = []
        self._pending_transactions: list[Transaction] = []

    @classmethod
    def create(cls, owner_id: str, currency: str) -> Account:
        now = datetime.utcnow()
        account = cls(
            account_id=str(uuid.uuid4()),
            owner_id=owner_id,
            balance=Money(0, currency),
            status=AccountStatus.ACTIVE,
            created_at=now,
            updated_at=now,
        )
        account._events.append(AccountCreated(
            account_id=account.account_id,
            owner_id=owner_id,
            currency=currency,
            created_at=now,
        ))
        return account

    def deposit(self, amount: int) -> Transaction:
        if self.status != AccountStatus.ACTIVE:
            raise DepositRequiresActiveAccountError()
        if amount <= 0:
            raise InvalidAmountError()
        money = Money(amount, self.balance.currency)
        self.balance = self.balance.add(money)
        self.updated_at = datetime.utcnow()
        transaction = Transaction.create(self.account_id, "DEPOSIT", money)
        self._pending_transactions.append(transaction)
        self._events.append(MoneyDeposited(
            account_id=self.account_id,
            transaction_id=transaction.transaction_id,
            amount=money,
            balance_after=self.balance,
            created_at=transaction.created_at,
        ))
        return transaction

    def withdraw(self, amount: int) -> Transaction:
        if self.status != AccountStatus.ACTIVE:
            raise WithdrawRequiresActiveAccountError()
        if amount <= 0:
            raise InvalidAmountError()
        money = Money(amount, self.balance.currency)
        if self.balance.is_less_than(money):
            raise InsufficientBalanceError()
        self.balance = self.balance.subtract(money)
        self.updated_at = datetime.utcnow()
        transaction = Transaction.create(self.account_id, "WITHDRAWAL", money)
        self._pending_transactions.append(transaction)
        self._events.append(MoneyWithdrawn(
            account_id=self.account_id,
            transaction_id=transaction.transaction_id,
            amount=money,
            balance_after=self.balance,
            created_at=transaction.created_at,
        ))
        return transaction

    def suspend(self) -> None:
        if self.status != AccountStatus.ACTIVE:
            raise SuspendRequiresActiveAccountError()
        self.status = AccountStatus.SUSPENDED
        self.updated_at = datetime.utcnow()
        self._events.append(AccountSuspended(account_id=self.account_id, suspended_at=self.updated_at))

    def reactivate(self) -> None:
        if self.status != AccountStatus.SUSPENDED:
            raise ReactivateRequiresSuspendedAccountError()
        self.status = AccountStatus.ACTIVE
        self.updated_at = datetime.utcnow()
        self._events.append(AccountReactivated(account_id=self.account_id, reactivated_at=self.updated_at))

    def close(self) -> None:
        if self.status == AccountStatus.CLOSED:
            raise AccountAlreadyClosedError()
        if not self.balance.is_zero():
            raise AccountBalanceNotZeroError()
        self.status = AccountStatus.CLOSED
        self.updated_at = datetime.utcnow()
        self._events.append(AccountClosed(account_id=self.account_id, closed_at=self.updated_at))

    def pull_events(self) -> list[AccountDomainEvent]:
        events, self._events = self._events, []
        return events

    def pull_pending_transactions(self) -> list[Transaction]:
        transactions, self._pending_transactions = self._pending_transactions, []
        return transactions
