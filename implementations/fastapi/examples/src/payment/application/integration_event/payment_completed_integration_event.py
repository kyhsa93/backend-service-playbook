from __future__ import annotations

from dataclasses import dataclass
from typing import ClassVar


@dataclass(frozen=True)
class PaymentCompletedIntegrationEventV1:
    """Payment BC가 외부 BC(Account)에 공개하는 Integration Event (공개 계약).

    Account가 실제 차감(withdraw)에 필요한 최소 정보(account_id+amount)만 싣는 얇은
    계약이다 — owner_id/card_id 등 Payment 내부 모델은 노출하지 않는다. `event_name`은
    ClassVar라 `dataclasses.asdict()` 페이로드에는 포함되지 않고, `OutboxWriter.save_all()`이
    Outbox row의 `event_type`으로 사용한다.
    """

    event_name: ClassVar[str] = "payment.completed.v1"
    payment_id: str
    account_id: str
    amount: int
    completed_at: str
