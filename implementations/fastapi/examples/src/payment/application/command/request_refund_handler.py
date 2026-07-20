from dataclasses import dataclass

from ...domain.errors import PaymentNotFoundError
from ...domain.payment_repository import PaymentRepository
from ...domain.refund import Refund
from ...domain.refund_eligibility_service import RefundEligibilityService
from ...domain.refund_repository import RefundRepository


@dataclass
class RequestRefundCommand:
    requester_id: str
    payment_id: str
    amount: int
    reason: str


class RequestRefundHandler:
    def __init__(self, payment_repo: PaymentRepository, refund_repo: RefundRepository) -> None:
        self._payment_repo = payment_repo
        self._refund_repo = refund_repo
        # RefundEligibilityService는 프레임워크 의존이 없는 순수 Domain Service다. FastAPI의
        # Depends에 등록하지 않고 직접 인스턴스화해 쓴다(상태 없는 순수 판단 로직이라 매
        # 요청 재사용에 문제가 없다).
        self._refund_eligibility_service = RefundEligibilityService()

    async def execute(self, cmd: RequestRefundCommand) -> Refund:
        payments, _ = await self._payment_repo.find_payments(
            page=0, take=1, payment_id=cmd.payment_id, owner_id=cmd.requester_id
        )
        payment = payments[0] if payments else None
        if payment is None:
            raise PaymentNotFoundError(cmd.payment_id)

        refund = Refund.create(payment_id=payment.payment_id, amount=cmd.amount, reason=cmd.reason)

        # 어느 한 Aggregate만으로는 내릴 수 없는 판단(원 결제 상태 + 환불 금액 비교)을
        # Payment+Refund 두 Aggregate를 함께 로드한 이 Application 레이어가
        # RefundEligibilityService(Domain Service)에 위임해 조율한다.
        decision = self._refund_eligibility_service.evaluate(payment, refund)
        if decision.approved:
            refund.approve(account_id=payment.account_id, owner_id=payment.owner_id)
        else:
            # 환불 거부는 도메인 관점에서 유효한 상태 전이다(입력이 잘못된 것이 아니라 두
            # Aggregate를 조율해 내린 결론) — 따라서 여기서 예외를 던지지 않고 REJECTED로
            # 저장한 Refund를 그대로 반환한다. Interface 레이어가 이를 에러가 아닌
            # 201 + status: REJECTED로 응답한다.
            refund.reject(decision.reason or "환불 요청이 거부되었습니다.")

        await self._refund_repo.save_refund(refund)
        # RefundApproved → refund.approved.v1을 Account BC가 구독해 환불 크레딧을 실행한다
        # — OutboxPoller/OutboxConsumer가 비동기로 처리한다. 거부된 경우에는 Domain Event가
        # 없으므로 Outbox에 적재될 것이 없다.
        return refund
