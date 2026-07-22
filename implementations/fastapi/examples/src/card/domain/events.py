from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime


@dataclass(frozen=True)
class CardStatementSent:
    """A Domain Event published by the monthly card-statement delivery batch (Task Queue →
    card.statement.send) via `Card.send_statement()`. The first Domain Event the Card BC
    publishes — the actual SES send is handled by `CardStatementSentEventHandler`, which
    receives this event asynchronously through Outbox → SQS (the same flow as
    domain-events.md). This fully separates the statistics batch's transaction (iterating
    multiple cards in one session) from the actual email send, so if processing another
    card fails partway through the batch, the email for a card already committed is not
    sent twice."""

    card_id: str
    account_id: str
    owner_id: str
    email: str
    period: str  # "YYYY-MM" — the month being aggregated
    payment_count: int
    total_amount: int
    sent_at: datetime
