from dataclasses import dataclass

from ...domain.payment_repository import PaymentQuery
from .result import GetPaymentResult, GetPaymentsResult


@dataclass
class GetPaymentsQuery:
    # 인증된 요청자 본인의 결제 내역만 조회한다 — 클라이언트가 넘긴 ownerId를 신뢰하지
    # 않는다(이 저장소의 어떤 엔드포인트도 클라이언트가 지정한 소유자 id를 받지 않는다).
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
