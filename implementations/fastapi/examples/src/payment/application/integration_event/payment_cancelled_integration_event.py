from __future__ import annotations

from dataclasses import dataclass
from typing import ClassVar


@dataclass(frozen=True)
class PaymentCancelledIntegrationEventV1:
    """An Integration Event (a public contract) the Payment BC exposes to an external BC
    (Account).

    Carries only the minimal information Account needs to execute the compensating credit
    (deposit).
    """

    event_name: ClassVar[str] = "payment.cancelled.v1"
    payment_id: str
    account_id: str
    amount: int
    cancelled_at: str
