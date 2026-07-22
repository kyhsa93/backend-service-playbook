from __future__ import annotations

from dataclasses import dataclass
from typing import ClassVar


@dataclass(frozen=True)
class PaymentCompletedIntegrationEventV1:
    """An Integration Event (a public contract) the Payment BC exposes to an external BC
    (Account).

    A thin contract carrying only the minimal information (account_id+amount) Account
    needs for the actual debit (withdraw) — it never exposes Payment's internal model,
    such as owner_id/card_id. `event_name` is a ClassVar, so it is not included in the
    `dataclasses.asdict()` payload, and `OutboxWriter.save_all()` uses it as the Outbox
    row's `event_type`.
    """

    event_name: ClassVar[str] = "payment.completed.v1"
    payment_id: str
    account_id: str
    amount: int
    completed_at: str
