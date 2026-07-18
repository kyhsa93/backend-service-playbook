from __future__ import annotations

from dataclasses import dataclass

from .payment import Payment
from .payment_status import PaymentStatus
from .refund import Refund


@dataclass(frozen=True)
class RefundDecision:
    approved: bool
    reason: str | None = None


class RefundEligibilityService:
    """Domain Service — 프레임워크 의존이 없는 순수 클래스다. FastAPI의 Depends 등 어떤
    DI 컨테이너에도 등록하지 않는다 — Application 레이어가 필요할 때 직접 `new`(생성)해
    쓴다(상태가 없는 순수 판단 로직이라 매 요청 재사용에 문제가 없다).

    "원 결제가 COMPLETED 상태여야 하고, 환불 금액이 결제 금액을 넘을 수 없다"는 판단은
    Payment 혼자서도, Refund 혼자서도 내릴 수 없다. Payment는 자신에 대한 환불 시도를
    모르고(환불은 Refund Aggregate로만 존재), Refund는 원 결제의 금액·상태를 모른다
    (payment_id로 참조만 한다). 이 판단을 내리려면 두 Aggregate를 모두 로드해 같은
    자리에서 비교해야 하므로, 이 조율 로직은 어느 한쪽 Aggregate의 메서드로 넣을 수
    없고(넣는다면 다른 쪽 Aggregate 전체를 파라미터로 받아야 해 경계가 무너진다) 여기
    즉 별도의 Domain Service에 위치한다.
    (root docs/architecture/domain-service.md, implementations/fastapi/docs/architecture/
    layer-architecture.md 참조)
    """

    def evaluate(self, payment: Payment, refund: Refund) -> RefundDecision:
        if payment.status != PaymentStatus.COMPLETED:
            return RefundDecision(approved=False, reason="완료된 결제에 대해서만 환불을 요청할 수 있습니다.")
        if refund.amount > payment.amount:
            return RefundDecision(approved=False, reason="환불 금액은 결제 금액을 초과할 수 없습니다.")
        return RefundDecision(approved=True)
