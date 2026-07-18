from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime


@dataclass
class GetPaymentResult:
    payment_id: str
    card_id: str
    account_id: str
    owner_id: str
    amount: int
    status: str
    created_at: datetime


@dataclass
class GetPaymentsResult:
    payments: list[GetPaymentResult]
    count: int


@dataclass
class GetRefundResult:
    refund_id: str
    payment_id: str
    amount: int
    reason: str
    status: str
    decision_note: str | None
    created_at: datetime


@dataclass
class GetRefundsResult:
    refunds: list[GetRefundResult]
    count: int
