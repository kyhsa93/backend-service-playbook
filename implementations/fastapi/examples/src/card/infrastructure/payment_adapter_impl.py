from __future__ import annotations

from datetime import datetime

from ...payment.domain.payment_repository import PaymentQuery
from ..application.adapter.payment_adapter import CardPaymentSummary, PaymentAdapter

PAGE_SIZE = 200


class PaymentAdapterImpl(PaymentAdapter):
    """PaymentAdapter의 구현체(ACL). Payment BC가 공개한 읽기 인터페이스(PaymentQuery)를
    주입받아 호출하고, Payment BC의 모델을 Card BC가 쓰는 최소 형태(CardPaymentSummary)로
    번역한다. Payment의 Repository 구현체나 도메인 객체를 직접 참조하지 않는다
    (card/infrastructure/account_adapter_impl.py와 동일한 패턴).

    `find_payments()`의 (list, total) 페이지네이션을 그대로 재사용해 페이지 단위로 순회하며
    COMPLETED 결제만 집계한다 — 별도의 count 전용 메서드를 Repository/Query 인터페이스에
    새로 추가하지 않는다(repository-pattern.md의 find_<noun>s 단일 메서드 컨벤션).
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
