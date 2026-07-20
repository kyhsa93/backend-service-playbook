from dataclasses import dataclass

from ...domain.errors import PaymentNotFoundError
from ...domain.payment import Payment
from ...domain.payment_repository import PaymentRepository


@dataclass
class CancelPaymentCommand:
    requester_id: str
    payment_id: str
    reason: str


class CancelPaymentHandler:
    def __init__(self, repo: PaymentRepository) -> None:
        self._repo = repo

    async def execute(self, cmd: CancelPaymentCommand) -> Payment:
        payments, _ = await self._repo.find_payments(
            page=0, take=1, payment_id=cmd.payment_id, owner_id=cmd.requester_id
        )
        payment = payments[0] if payments else None
        if payment is None:
            raise PaymentNotFoundError(cmd.payment_id)

        payment.cancel(cmd.reason)
        await self._repo.save(payment)
        # PaymentCancelled → payment.cancelled.v1을 Account BC가 구독해 보상 크레딧을 실행한다
        # — OutboxPoller/OutboxConsumer가 비동기로 처리한다(domain-events.md).
        return payment
