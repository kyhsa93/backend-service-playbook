from dataclasses import dataclass

from ...domain.errors import PaymentNotFoundError
from ...domain.payment_repository import PaymentQuery
from .result import GetPaymentResult


@dataclass
class GetPaymentQuery:
    payment_id: str
    requester_id: str


class GetPaymentHandler:
    def __init__(self, query: PaymentQuery) -> None:
        self._query = query

    async def execute(self, query: GetPaymentQuery) -> GetPaymentResult:
        payments, _ = await self._query.find_payments(
            page=0, take=1, payment_id=query.payment_id, owner_id=query.requester_id
        )
        payment = payments[0] if payments else None
        if payment is None:
            raise PaymentNotFoundError(query.payment_id)
        return GetPaymentResult(
            payment_id=payment.payment_id,
            card_id=payment.card_id,
            account_id=payment.account_id,
            owner_id=payment.owner_id,
            amount=payment.amount,
            status=payment.status.value,
            created_at=payment.created_at,
        )
