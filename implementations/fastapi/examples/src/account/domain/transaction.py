from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from typing import Literal

from ...common.generate_id import generate_id
from .money import Money

TransactionType = Literal["DEPOSIT", "WITHDRAWAL", "INTEREST"]


@dataclass(frozen=True)
class Transaction:
    transaction_id: str
    account_id: str
    type: TransactionType
    amount: Money
    created_at: datetime
    # An optional field that lets a transaction arising from a reaction to an external BC's
    # (Payment's) Integration Event be correlated with the other BC's Aggregate ID
    # (payment_id/refund_id). It's absent (None) for a deposit/withdrawal the user requested
    # directly — it's only filled in by a Payment-reaction command, and on an at-least-once
    # redelivery this value (+type) is used as the Level 2 Ledger key that prevents
    # duplicate processing (see "Event Handler Idempotency" in domain-events.md).
    reference_id: str | None = None

    @classmethod
    def create(
        cls, account_id: str, type: TransactionType, amount: Money, reference_id: str | None = None
    ) -> Transaction:
        return cls(
            transaction_id=generate_id(),
            account_id=account_id,
            type=type,
            amount=amount,
            created_at=datetime.utcnow(),
            reference_id=reference_id,
        )
