from __future__ import annotations

from dataclasses import dataclass
from typing import ClassVar


@dataclass(frozen=True)
class AccountClosedIntegrationEventV1:
    """An Integration Event (a public contract) the Account BC exposes to external BCs (Card, etc.)."""

    event_name: ClassVar[str] = "account.closed.v1"
    account_id: str
    closed_at: str
