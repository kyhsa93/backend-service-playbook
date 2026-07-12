from __future__ import annotations

from dataclasses import dataclass
from typing import ClassVar


@dataclass(frozen=True)
class AccountSuspendedIntegrationEventV1:
    """Account BC가 외부 BC(Card 등)에 공개하는 Integration Event (공개 계약).

    내부 Domain Event(AccountSuspended)와 분리해 이름·스키마를 안정적으로 유지하고 버전을
    명시한다. `event_name`은 ClassVar라 `dataclasses.asdict()` 페이로드에는 포함되지 않고,
    `OutboxWriter.save_all()`이 Outbox row의 `event_type`으로 사용한다.
    """

    event_name: ClassVar[str] = "account.suspended.v1"
    account_id: str
    suspended_at: str
