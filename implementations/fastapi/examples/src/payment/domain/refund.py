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
    """The Refund Aggregate. Refund itself cannot decide on the original payment's (Payment's)
    status/amount — RefundEligibilityService (a Domain Service) loads both the Payment and
    Refund Aggregates together, and approve()/reject() are called with the coordinated result
    (a RefundDecision).
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
        # account_id/owner_id are not part of RefundEligibilityService's decision — they are
        # just reference data the Application layer reads from the original payment (Payment)
        # after the decision, to assemble the Integration Event propagated to external BCs
        # (they are not promoted to a field of Refund itself).
        if self.status != RefundStatus.REQUESTED:
            raise RefundApproveRequiresRequestedRefundError()
        self.status = RefundStatus.APPROVED
        self.decision_note = "The refund has been approved."
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
        # Currently, refund processing ends with Account subscribing to refund.approved.v1
        # and executing the credit — there is no callback path that reports that credit's
        # success back to the Payment BC (not exposed on the REST surface). The method is
        # kept for a complete Payment-domain state model (verified by a Domain unit test),
        # but no Command currently calls it — left unwired for the same reason as
        # Payment.fail().
        if self.status != RefundStatus.APPROVED:
            raise RefundCompleteRequiresApprovedRefundError()
        self.status = RefundStatus.COMPLETED

    def pull_events(self) -> list[RefundApproved]:
        events, self._events = self._events, []
        return events
