from __future__ import annotations

from datetime import datetime
from typing import Union

from ...common.generate_id import generate_id
from .card_status import CardStatus
from .errors import (
    CancelledCardCannotBeSuspendedError,
    CardAlreadyCancelledError,
    CardAlreadySuspendedError,
)
from .events import CardStatementSent

CardDomainEvent = Union[CardStatementSent]


class Card:
    def __init__(
        self,
        card_id: str,
        account_id: str,
        owner_id: str,
        brand: str,
        status: CardStatus,
        created_at: datetime,
        last_statement_sent_month: str | None = None,
    ) -> None:
        self.card_id = card_id
        self.account_id = account_id
        self.owner_id = owner_id
        self.brand = brand
        self.status = status
        self.created_at = created_at
        # The Level 1 idempotency marker for the monthly card-statement delivery batch
        # ("YYYY-MM") — the same design as Account.last_interest_paid_at (see "Event Handler
        # Idempotency" in domain-events.md).
        self.last_statement_sent_month = last_statement_sent_month
        self._events: list[CardDomainEvent] = []

    @classmethod
    def issue(cls, account_id: str, owner_id: str, brand: str) -> Card:
        # The Card Aggregate cannot know whether the linked account is active — whether
        # issuance is allowed (account status) is decided by the Application layer via a
        # synchronous lookup through AccountAdapter (ACL), before it calls this factory.
        return cls(
            card_id=generate_id(),
            account_id=account_id,
            owner_id=owner_id,
            brand=brand,
            status=CardStatus.ACTIVE,
            created_at=datetime.utcnow(),
        )

    def suspend(self) -> None:
        if self.status == CardStatus.CANCELLED:
            raise CancelledCardCannotBeSuspendedError()
        if self.status == CardStatus.SUSPENDED:
            raise CardAlreadySuspendedError()
        self.status = CardStatus.SUSPENDED

    def cancel(self) -> None:
        if self.status == CardStatus.CANCELLED:
            raise CardAlreadyCancelledError()
        self.status = CardStatus.CANCELLED

    def send_statement(self, period: str, payment_count: int, total_amount: int, email: str) -> None:
        """An Aggregate method invoked system-driven by the monthly card-statement delivery
        batch (Task Queue → card.statement.send). It's called after the Application layer
        has already finished computing the payment count/total via a synchronous lookup
        through PaymentAdapter (ACL) — Card itself doesn't know the Payment BC.

        Idempotency is guaranteed by a single field, `last_statement_sent_month` (has it
        already been sent this month) (Level 1) — a complete no-op if already processed
        this month.
        """
        if self.last_statement_sent_month == period:
            return
        self.last_statement_sent_month = period
        self._events.append(
            CardStatementSent(
                card_id=self.card_id,
                account_id=self.account_id,
                owner_id=self.owner_id,
                email=email,
                period=period,
                payment_count=payment_count,
                total_amount=total_amount,
                sent_at=datetime.utcnow(),
            )
        )

    def pull_events(self) -> list[CardDomainEvent]:
        events, self._events = self._events, []
        return events
