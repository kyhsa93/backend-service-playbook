from __future__ import annotations

from dataclasses import dataclass
from typing import ClassVar


@dataclass(frozen=True)
class AccountClosedIntegrationEventV1:
    """Account BC가 외부 BC(Card 등)에 공개하는 Integration Event (공개 계약)."""

    event_name: ClassVar[str] = "account.closed.v1"
    account_id: str
    closed_at: str
