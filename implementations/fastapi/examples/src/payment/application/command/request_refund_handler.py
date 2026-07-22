from dataclasses import dataclass

from ...domain.errors import PaymentNotFoundError
from ...domain.payment_repository import PaymentRepository
from ...domain.refund import Refund
from ...domain.refund_eligibility_service import RefundEligibilityService
from ...domain.refund_repository import RefundRepository
from ..service.refund_reason_classifier import RefundReasonClassifier


@dataclass
class RequestRefundCommand:
    requester_id: str
    payment_id: str
    amount: int
    reason: str


class RequestRefundHandler:
    def __init__(
        self,
        payment_repo: PaymentRepository,
        refund_repo: RefundRepository,
        refund_reason_classifier: RefundReasonClassifier,
    ) -> None:
        self._payment_repo = payment_repo
        self._refund_repo = refund_repo
        # RefundReasonClassifier is a Technical Service (Depends-bound to its real LLM-backed
        # implementation) — unlike RefundEligibilityService below, it wraps external I/O, so
        # it's injected rather than instantiated directly.
        self._refund_reason_classifier = refund_reason_classifier
        # RefundEligibilityService is a pure Domain Service with no framework dependency. It
        # is instantiated directly rather than registered with FastAPI's Depends (it's
        # stateless, pure decision logic, so reuse across requests is not an issue).
        self._refund_eligibility_service = RefundEligibilityService()

    async def execute(self, cmd: RequestRefundCommand) -> Refund:
        payments, _ = await self._payment_repo.find_payments(
            page=0, take=1, payment_id=cmd.payment_id, owner_id=cmd.requester_id
        )
        payment = payments[0] if payments else None
        if payment is None:
            raise PaymentNotFoundError(cmd.payment_id)

        refund = Refund.create(payment_id=payment.payment_id, amount=cmd.amount, reason=cmd.reason)
        classification = await self._refund_reason_classifier.classify(cmd.reason)

        # A decision that neither Aggregate alone can make (comparing the original payment's
        # status + the refund amount + the fraud-risk signal classified above) is delegated to
        # RefundEligibilityService (a Domain Service), coordinated by this Application layer,
        # which has loaded both the Payment and Refund Aggregates and classified the refund's
        # free-text reason.
        decision = self._refund_eligibility_service.evaluate(payment, refund, classification)
        if decision.approved:
            refund.approve(account_id=payment.account_id, owner_id=payment.owner_id)
        else:
            # A refund rejection is a valid state transition from a domain point of view (not
            # an invalid input, but a conclusion reached by coordinating both Aggregates) —
            # so no exception is thrown here; the Refund saved as REJECTED is returned as-is.
            # The Interface layer responds with 201 + status: REJECTED for this, not an error.
            refund.reject(decision.reason or "The refund request has been rejected.")

        await self._refund_repo.save_refund(refund)
        # RefundApproved → Account BC subscribes to refund.approved.v1 and executes the refund
        # credit — handled asynchronously by OutboxPoller/OutboxConsumer. When rejected, there
        # is no Domain Event, so nothing gets loaded into the Outbox.
        return refund
