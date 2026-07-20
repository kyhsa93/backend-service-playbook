from abc import ABC, abstractmethod

from .refund import Refund


class RefundQuery(ABC):
    """읽기 전용 인터페이스 — Query Handler 전용(card 도메인의 CardQuery와 동일한 이유).

    Refund는 owner_id를 갖지 않는다(paymentId로만 원 결제를 참조한다) — 소유권 검증은
    Query Handler가 PaymentQuery로 원 결제를 먼저 조회해 확인한다.
    """

    @abstractmethod
    async def find_refunds(
        self,
        page: int,
        take: int,
        refund_id: str | None = None,
        payment_id: str | None = None,
        status: list[str] | None = None,
    ) -> tuple[list[Refund], int]: ...


class RefundRepository(RefundQuery, ABC):
    @abstractmethod
    async def save_refund(self, refund: Refund) -> None: ...
