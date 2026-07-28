from __future__ import annotations

from dataclasses import dataclass, replace
from datetime import datetime
from typing import Literal

from ...common.generate_id import generate_id
from .money import Money

TransactionType = Literal["DEPOSIT", "WITHDRAWAL", "INTEREST"]

# The fixed taxonomy TransactionAutoCategorizer classifies a withdrawal's merchant_name into.
# Lives here (not in the application layer) for the same reason SpendingAnalysis's trend does —
# it's a value the domain read/write model carries.
TransactionCategory = Literal[
    "FOOD", "TRANSPORT", "SHOPPING", "HOUSING", "MEDICAL", "ENTERTAINMENT", "UTILITIES", "OTHER"
]


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
    # The payee/memo the requester optionally attaches to a withdrawal — the only free-text
    # signal TransactionAutoCategorizer has to classify against. Absent for deposits/interest
    # and for a withdrawal the requester didn't attach one to.
    merchant_name: str | None = None
    # Filled in asynchronously, after the transaction is created — CategorizeTransactionEventHandler
    # reacts to MoneyWithdrawn and categorizes it later, so this is always None at the moment
    # Account.withdraw() constructs the Transaction, and only present when this object is
    # reconstructed from a row a categorization run has already updated.
    category: TransactionCategory | None = None

    @classmethod
    def create(
        cls,
        account_id: str,
        type: TransactionType,
        amount: Money,
        reference_id: str | None = None,
        merchant_name: str | None = None,
    ) -> Transaction:
        return cls(
            transaction_id=generate_id(),
            account_id=account_id,
            type=type,
            amount=amount,
            created_at=datetime.utcnow(),
            reference_id=reference_id,
            merchant_name=merchant_name,
        )

    def categorize(self, category: TransactionCategory) -> Transaction:
        """The domain method CategorizeTransactionEventHandler drives TransactionRepository's
        find -> modify -> save_<noun> cycle through (see repository-pattern.md) — Transaction
        is otherwise immutable, so this returns a new instance rather than mutating in place.
        """
        return replace(self, category=category)
