from __future__ import annotations

from dataclasses import dataclass

from .payment import Payment
from .payment_status import PaymentStatus
from .refund import Refund


@dataclass(frozen=True)
class RefundDecision:
    approved: bool
    reason: str | None = None


class RefundEligibilityService:
    """A Domain Service — a pure class with no framework dependency. It is never registered
    in any DI container such as FastAPI's Depends — the Application layer instantiates it
    directly whenever needed (it's stateless, pure decision logic, so reuse across requests
    is not an issue).

    The decision "the original payment must be in the COMPLETED state, and the refund amount
    cannot exceed the payment amount" cannot be made by Payment alone or by Refund alone.
    Payment doesn't know about a refund attempt against it (a refund only exists as a Refund
    Aggregate), and Refund doesn't know the original payment's amount/status (it only
    references it via payment_id). Making this decision requires loading both Aggregates and
    comparing them in the same place, so this coordination logic can't be put into either
    Aggregate's method (doing so would require taking the other Aggregate whole as a
    parameter, breaking the boundary) — it lives here, in a separate Domain Service instead.
    (See root docs/architecture/domain-service.md, implementations/fastapi/docs/architecture/
    layer-architecture.md)
    """

    def evaluate(self, payment: Payment, refund: Refund) -> RefundDecision:
        if payment.status != PaymentStatus.COMPLETED:
            return RefundDecision(approved=False, reason="A refund can only be requested for a completed payment.")
        if refund.amount > payment.amount:
            return RefundDecision(approved=False, reason="The refund amount cannot exceed the payment amount.")
        return RefundDecision(approved=True)
