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
    """Refund 테이블 자체는 owner_id를 갖지 않는다(Refund는 payment_id로만 원 결제를
    참조한다) — 소유권 검증은 Payment를 먼저 조회해 확인한다. Account의
    GetTransactionsHandler가 계좌 소유권을 먼저 확인한 뒤 거래 내역을 조회하는 것과
    동일한 패턴이다.
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
