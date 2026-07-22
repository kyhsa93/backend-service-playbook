from dataclasses import dataclass

from ...domain.errors import PaymentNotFoundError
from ...domain.payment_repository import PaymentQuery
from ...domain.refund_repository import RefundQuery
from .result import GetRefundResult, GetRefundsResult


@dataclass
class GetRefundsQuery:
    payment_id: str
    requester_id: str
    page: int = 0
    take: int = 20


class GetRefundsHandler:
    """The Refund table itself has no owner_id (Refund references the original payment only
    via payment_id) — ownership is verified by first looking up the Payment. The same
    pattern as Account's GetTransactionsHandler, which verifies account ownership first
    before looking up transaction history.
    """

    def __init__(self, payment_query: PaymentQuery, refund_query: RefundQuery) -> None:
        self._payment_query = payment_query
        self._refund_query = refund_query

    async def execute(self, query: GetRefundsQuery) -> GetRefundsResult:
        payments, _ = await self._payment_query.find_payments(
            page=0, take=1, payment_id=query.payment_id, owner_id=query.requester_id
        )
        if not payments:
            raise PaymentNotFoundError(query.payment_id)

        refunds, count = await self._refund_query.find_refunds(
            page=query.page, take=query.take, payment_id=query.payment_id
        )
        return GetRefundsResult(
            refunds=[
                GetRefundResult(
                    refund_id=r.refund_id,
                    payment_id=r.payment_id,
                    amount=r.amount,
                    reason=r.reason,
                    status=r.status.value,
                    decision_note=r.decision_note,
                    created_at=r.created_at,
                )
                for r in refunds
            ],
            count=count,
        )
