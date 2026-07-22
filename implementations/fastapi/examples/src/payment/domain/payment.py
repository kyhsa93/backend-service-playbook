from __future__ import annotations

from datetime import datetime
from typing import Union

from ...common.generate_id import generate_id
from .errors import (
    PaymentCancelRequiresCompletedPaymentError,
    PaymentCompleteRequiresPendingPaymentError,
    PaymentFailRequiresPendingPaymentError,
)
from .events import PaymentCancelled, PaymentCompleted
from .payment_status import PaymentStatus

PaymentDomainEvent = Union[PaymentCompleted, PaymentCancelled]


class Payment:
    """The Payment Aggregate. It only references which card was used via card_id and which
    account is the debit target via account_id (no FK crossing the BC boundary) — the
    actual status/balance judgment of the card/account is finished by the Application layer
    via a synchronous lookup through CardAdapter/AccountAdapter (ACL), before this Aggregate
    is created — Payment itself doesn't know "is the card active" or "is the balance
    sufficient."
    """

    def __init__(
        self,
        payment_id: str,
        card_id: str,
        account_id: str,
        owner_id: str,
        amount: int,
        status: PaymentStatus,
        created_at: datetime,
    ) -> None:
        self.payment_id = payment_id
        self.card_id = card_id
        self.account_id = account_id
        self.owner_id = owner_id
        self.amount = amount
        self.status = status
        self.created_at = created_at
        self._events: list[PaymentDomainEvent] = []

    @classmethod
    def create(cls, card_id: str, account_id: str, owner_id: str, amount: int) -> Payment:
        # A pure creation factory called after the card's active status and the account's
        # sufficient balance have already been decided by the Application layer's
        # synchronous Adapter calls — it only creates a PENDING payment, with no event.
        return cls(
            payment_id=generate_id(),
            card_id=card_id,
            account_id=account_id,
            owner_id=owner_id,
            amount=amount,
            status=PaymentStatus.PENDING,
            created_at=datetime.utcnow(),
        )

    def complete(self) -> None:
        if self.status != PaymentStatus.PENDING:
            raise PaymentCompleteRequiresPendingPaymentError()
        self.status = PaymentStatus.COMPLETED
        self._events.append(
            PaymentCompleted(
                payment_id=self.payment_id,
                card_id=self.card_id,
                account_id=self.account_id,
                owner_id=self.owner_id,
                amount=self.amount,
                completed_at=datetime.utcnow(),
            )
        )

    def fail(self, reason: str) -> None:
        # Currently, CreatePaymentHandler decides pass/fail via a synchronous Adapter before
        # creation, so there's no path where the Payment Aggregate is created as PENDING and
        # then fails. However, the state transition itself is kept on the Aggregate
        # (verified by a Domain unit test), in preparation for a future scenario where a
        # failure arrives asynchronously, such as a payment-gateway callback. Since no
        # Command enters this path yet, there is no Domain Event consumer to carry the
        # reason either, so unlike cancel(), no event is published.
        if self.status != PaymentStatus.PENDING:
            raise PaymentFailRequiresPendingPaymentError()
        self.status = PaymentStatus.FAILED

    def cancel(self, reason: str) -> None:
        # A payment cancellation reverses an already-confirmed (COMPLETED) payment, so it's only allowed from COMPLETED.
        if self.status != PaymentStatus.COMPLETED:
            raise PaymentCancelRequiresCompletedPaymentError()
        self.status = PaymentStatus.CANCELLED
        self._events.append(
            PaymentCancelled(
                payment_id=self.payment_id,
                account_id=self.account_id,
                owner_id=self.owner_id,
                amount=self.amount,
                reason=reason,
                cancelled_at=datetime.utcnow(),
            )
        )

    def pull_events(self) -> list[PaymentDomainEvent]:
        events, self._events = self._events, []
        return events
