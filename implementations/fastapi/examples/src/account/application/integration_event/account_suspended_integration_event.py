from __future__ import annotations

from dataclasses import dataclass
from typing import ClassVar


@dataclass(frozen=True)
class AccountSuspendedIntegrationEventV1:
    """An Integration Event (a public contract) the Account BC exposes to external BCs
    (Card, etc.).

    Kept separate from the internal Domain Event (AccountSuspended) to keep its name/schema
    stable and its version explicit. `event_name` is a ClassVar, so it is not included in
    the `dataclasses.asdict()` payload, and `OutboxWriter.save_all()` uses it as the Outbox
    row's `event_type`.
    """

    event_name: ClassVar[str] = "account.suspended.v1"
    account_id: str
    suspended_at: str
