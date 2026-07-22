from dataclasses import dataclass

from ...domain.payment_repository import PaymentQuery
from .result import GetPaymentResult, GetPaymentsResult


@dataclass
class GetPaymentsQuery:
    # Looks up only the authenticated requester's own payment history — a client-supplied
    # ownerId is never trusted (no endpoint in this repository accepts a client-specified
    # owner id).
    requester_id: str
    page: int = 0
    take: int = 20


class GetPaymentsHandler:
    def __init__(self, query: PaymentQuery) -> None:
        self._query = query

    async def execute(self, query: GetPaymentsQuery) -> GetPaymentsResult:
        payments, count = await self._query.find_payments(page=query.page, take=query.take, owner_id=query.requester_id)
        return GetPaymentsResult(
            payments=[
                GetPaymentResult(
                    payment_id=p.payment_id,
                    card_id=p.card_id,
                    account_id=p.account_id,
                    owner_id=p.owner_id,
                    amount=p.amount,
                    status=p.status.value,
                    created_at=p.created_at,
                )
                for p in payments
            ],
            count=count,
        )
