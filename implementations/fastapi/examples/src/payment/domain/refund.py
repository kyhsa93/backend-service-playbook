from __future__ import annotations

from datetime import datetime

from ...common.generate_id import generate_id
from .errors import (
    RefundApproveRequiresRequestedRefundError,
    RefundCompleteRequiresApprovedRefundError,
    RefundRejectRequiresRequestedRefundError,
)
from .events import RefundApproved
from .refund_status import RefundStatus


class Refund:
    """Refund Aggregate. 원 결제(Payment)의 상태·금액에 대한 판단은 Refund 자신이 할 수
    없다 — RefundEligibilityService(Domain Service)가 Payment+Refund 두 Aggregate를
    함께 로드해 조율한 결과(RefundDecision)를 받아 approve()/reject()를 호출한다.
    """

    def __init__(
        self,
        refund_id: str,
        payment_id: str,
        amount: int,
        reason: str,
        status: RefundStatus,
        created_at: datetime,
        decision_note: str | None = None,
    ) -> None:
        self.refund_id = refund_id
        self.payment_id = payment_id
        self.amount = amount
        self.reason = reason
        self.status = status
        self.created_at = created_at
        self.decision_note = decision_note
        self._events: list[RefundApproved] = []

    @classmethod
    def create(cls, payment_id: str, amount: int, reason: str) -> Refund:
        return cls(
            refund_id=generate_id(),
            payment_id=payment_id,
            amount=amount,
            reason=reason,
            status=RefundStatus.REQUESTED,
            created_at=datetime.utcnow(),
        )

    def approve(self, account_id: str, owner_id: str) -> None:
        # account_id/owner_id는 RefundEligibilityService의 판단 결과가 아니라, 판단 이후
        # 외부 BC에 전파할 Integration Event를 조립하기 위해 Application 레이어가 원 결제
        # (Payment)에서 읽어 넘기는 참조 데이터일 뿐이다(Refund 자신의 필드로 승격하지 않는다).
        if self.status != RefundStatus.REQUESTED:
            raise RefundApproveRequiresRequestedRefundError()
        self.status = RefundStatus.APPROVED
        self.decision_note = "환불이 승인되었습니다."
        self._events.append(
            RefundApproved(
                refund_id=self.refund_id,
                payment_id=self.payment_id,
                account_id=account_id,
                owner_id=owner_id,
                amount=self.amount,
                approved_at=datetime.utcnow(),
            )
        )

    def reject(self, reason: str) -> None:
        if self.status != RefundStatus.REQUESTED:
            raise RefundRejectRequiresRequestedRefundError()
        self.status = RefundStatus.REJECTED
        self.decision_note = reason

    def complete(self) -> None:
        # 현재는 refund.approved.v1을 Account가 구독해 크레딧을 실행하는 것으로 환불
        # 처리가 끝나고, 그 크레딧 성공을 Payment BC로 다시 알려주는 콜백 경로는 없다
        # (REST 표면에 없음). Payment 도메인의 완결된 상태 모델을 위해 메서드는
        # 남겨두되(Domain 단위 테스트로 검증), 현재 어떤 Command도 이를 호출하지
        # 않는다 — Payment.fail()과 같은 이유로 미연결 상태다.
        if self.status != RefundStatus.APPROVED:
            raise RefundCompleteRequiresApprovedRefundError()
        self.status = RefundStatus.COMPLETED

    def pull_events(self) -> list[RefundApproved]:
        events, self._events = self._events, []
        return events
