from abc import ABC, abstractmethod
from datetime import datetime

from .payment import Payment


class PaymentQuery(ABC):
    """읽기 전용 인터페이스 — Query Handler 전용. `save()` 등 쓰기 메서드를 노출하지 않는다
    (cqrs-pattern.md 참고). `PaymentRepository`(쓰기 모델)와 계약을 공유하지만 별도 타입이며,
    Query Handler는 반드시 이 타입으로만 의존해야 한다(account 도메인의 AccountQuery와 동일).

    조회 메서드는 단일 `find_payments(...)`로 통일한다(issue #236 — Account Repository가
    find_by_id/find_all로 나뉘어 있던 것을 단일 메서드+선택적 필터 키워드 인자로 통일한
    것과 동일한 컨벤션). `take=1`로 단건 조회를 표현한다.

    `since`/`until`은 매월 카드 사용내역 발송 배치(card.application.adapter.payment_adapter의
    PaymentAdapter)가 "지난 한 달" 범위로 결제를 집계하기 위해 추가됐다 — 새 메서드를
    만들지 않고 기존 find_payments()에 선택적 필터를 얹는 것으로 표현한다(반대로 새
    `count_*` 메서드를 추가하면 repository-naming 규칙이 금지하는 패턴이 된다).
    """

    @abstractmethod
    async def find_payments(
        self,
        page: int,
        take: int,
        payment_id: str | None = None,
        owner_id: str | None = None,
        card_id: str | None = None,
        account_id: str | None = None,
        status: list[str] | None = None,
        since: datetime | None = None,
        until: datetime | None = None,
    ) -> tuple[list[Payment], int]: ...


class PaymentRepository(PaymentQuery, ABC):
    @abstractmethod
    async def save_payment(self, payment: Payment) -> None: ...
