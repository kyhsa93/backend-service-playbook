from __future__ import annotations

from dataclasses import dataclass
from typing import ClassVar


@dataclass(frozen=True)
class PaymentCancelledIntegrationEventV1:
    """Payment BC가 외부 BC(Account)에 공개하는 Integration Event (공개 계약).

    Account가 보상 크레딧(deposit)을 실행하는 데 필요한 최소 정보만 싣는다.
    """

    event_name: ClassVar[str] = "payment.cancelled.v1"
    payment_id: str
    account_id: str
    amount: int
    cancelled_at: str
