from __future__ import annotations

from datetime import datetime

from ...payment.domain.payment_repository import PaymentQuery
from ..application.adapter.payment_adapter import CardPaymentSummary, PaymentAdapter

PAGE_SIZE = 200


class PaymentAdapterImpl(PaymentAdapter):
    """The implementation of PaymentAdapter (ACL). It's injected with and calls the read
    interface (PaymentQuery) the Payment BC exposes, and translates Payment BC's model into
    the minimal shape the Card BC uses (CardPaymentSummary). It never references Payment's
    Repository implementation or domain objects directly (the same pattern as
    card/infrastructure/account_adapter_impl.py).

    Reuses `find_payments()`'s (list, total) pagination as-is, iterating page by page and
    aggregating only COMPLETED payments — no separate count-only method is added to the
    Repository/Query interface (following repository-pattern.md's single find_<noun>s
    method convention).
    """

    def __init__(self, payment_query: PaymentQuery) -> None:
        self._payment_query = payment_query

    async def summarize_payments(self, card_id: str, since: datetime, until: datetime) -> CardPaymentSummary:
        payment_count = 0
        total_amount = 0
        page = 0

        while True:
            payments, total = await self._payment_query.find_payments(
                page=page,
                take=PAGE_SIZE,
                card_id=card_id,
                status=["COMPLETED"],
                since=since,
                until=until,
            )
            if not payments:
                break

            payment_count += len(payments)
            total_amount += sum(p.amount for p in payments)

            if (page + 1) * PAGE_SIZE >= total:
                break
            page += 1

        return CardPaymentSummary(payment_count=payment_count, total_amount=total_amount)
