from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from typing import Literal

from ...common.generate_id import generate_id
from .money import Money

TransactionType = Literal["DEPOSIT", "WITHDRAWAL"]


@dataclass(frozen=True)
class Transaction:
    transaction_id: str
    account_id: str
    type: TransactionType
    amount: Money
    created_at: datetime

    @classmethod
    def create(cls, account_id: str, type: TransactionType, amount: Money) -> Transaction:
        return cls(
            transaction_id=generate_id(),
            account_id=account_id,
            type=type,
            amount=amount,
            created_at=datetime.utcnow(),
        )
