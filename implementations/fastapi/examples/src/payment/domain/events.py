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


@dataclass(frozen=True)
class RefundRequested:
    """Published unconditionally by Refund.create() — before RefundEligibilityService's
    approve/reject judgment even runs. ClassifyRefundReasonEventHandler reacts to this to
    build ops-analytics insight from every refund's stated reason, independent of whether the
    refund is ultimately approved or rejected (a rejected refund's reason is just as useful a
    signal for the ops dashboard as an approved one's).
    """

    refund_id: str
    payment_id: str
    reason: str
    created_at: datetime
