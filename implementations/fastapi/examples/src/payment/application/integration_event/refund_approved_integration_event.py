from __future__ import annotations

from dataclasses import dataclass
from typing import ClassVar


@dataclass(frozen=True)
class RefundApprovedIntegrationEventV1:
    """An Integration Event (a public contract) the Payment BC exposes to an external BC
    (Account).

    Carries only the minimal information Account needs to execute the refund credit
    (deposit).
    """

    event_name: ClassVar[str] = "refund.approved.v1"
    refund_id: str
    payment_id: str
    account_id: str
    amount: int
    approved_at: str
