from __future__ import annotations

from datetime import datetime
from typing import Literal

from ...common.generate_id import generate_id
from .errors import (
    RefundApproveRequiresRequestedRefundError,
    RefundCompleteRequiresApprovedRefundError,
    RefundRejectRequiresRequestedRefundError,
)
from .events import RefundApproved, RefundRequested
from .refund_status import RefundStatus

# The fixed taxonomy RefundReasonClassifier classifies a refund's free-text reason into, for
# ops-analytics reporting only (see refund_reason_insights_query.py) — it never feeds back into
# RefundEligibilityService's approve/reject judgment. Lives here (not in the application layer),
# the same placement as Transaction's TransactionCategory.
RefundReasonCategory = Literal[
    "DEFECTIVE_PRODUCT", "WRONG_ITEM", "NOT_AS_DESCRIBED", "CHANGED_MIND", "LATE_DELIVERY", "DUPLICATE_CHARGE", "OTHER"
]


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
        reason_category: RefundReasonCategory | None = None,
    ) -> None:
        self.refund_id = refund_id
        self.payment_id = payment_id
        self.amount = amount
        self.reason = reason
        self.status = status
        self.created_at = created_at
        self.decision_note = decision_note
        self.reason_category = reason_category
        self._events: list[RefundApproved | RefundRequested] = []

    @classmethod
    def create(cls, payment_id: str, amount: int, reason: str) -> Refund:
        refund = cls(
            refund_id=generate_id(),
            payment_id=payment_id,
            amount=amount,
            reason=reason,
            status=RefundStatus.REQUESTED,
            created_at=datetime.utcnow(),
        )
        # Published unconditionally — before RefundEligibilityService's approve/reject
        # judgment even runs (see request_refund_handler.py). Classification must happen (and
        # be reported on) identically whether the refund ends up APPROVED or REJECTED — a pure
        # ops-analytics side channel that never feeds back into the eligibility decision.
        refund._events.append(
            RefundRequested(
                refund_id=refund.refund_id,
                payment_id=refund.payment_id,
                reason=refund.reason,
                created_at=refund.created_at,
            )
        )
        return refund

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

    def categorize_reason(self, category: RefundReasonCategory) -> None:
        """Set asynchronously by ClassifyRefundReasonEventHandler reacting to
        RefundRequested — never called from the approve()/reject() eligibility path. No
        further Domain Event is published here; this is a read-model enrichment, not
        something any other BC needs to react to.
        """
        self.reason_category = category

    def pull_events(self) -> list[RefundApproved | RefundRequested]:
        events, self._events = self._events, []
        return events
