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
        await self._repo.save_payment(payment)
        # PaymentCancelled → the Account BC subscribes to payment.cancelled.v1 and executes
        # the compensating credit — handled asynchronously by OutboxPoller/OutboxConsumer (domain-events.md).
        return payment
