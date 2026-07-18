from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime


@dataclass(frozen=True)
class PaymentCompleted:
    payment_id: str
    card_id: str
    account_id: str
    owner_id: str
    amount: int
    completed_at: datetime


@dataclass(frozen=True)
class PaymentCancelled:
    payment_id: str
    account_id: str
    owner_id: str
    amount: int
    reason: str
    cancelled_at: datetime


@dataclass(frozen=True)
class RefundApproved:
    refund_id: str
    payment_id: str
    account_id: str
    owner_id: str
    amount: int
    approved_at: datetime
