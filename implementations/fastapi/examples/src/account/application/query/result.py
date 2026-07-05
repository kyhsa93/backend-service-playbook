from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime


@dataclass
class MoneyResult:
    amount: int
    currency: str


@dataclass
class GetAccountResult:
    account_id: str
    owner_id: str
    email: str
    balance: MoneyResult
    status: str
    created_at: datetime
    updated_at: datetime


@dataclass
class TransactionSummary:
    transaction_id: str
    type: str
    amount: MoneyResult
    created_at: datetime


@dataclass
class GetTransactionsResult:
    transactions: list[TransactionSummary]
    count: int
