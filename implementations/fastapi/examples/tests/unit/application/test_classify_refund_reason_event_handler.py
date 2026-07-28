from unittest.mock import AsyncMock

import pytest

from src.payment.application.event.classify_refund_reason_event_handler import ClassifyRefundReasonEventHandler
from src.payment.domain.refund import Refund


@pytest.fixture
def classifier() -> AsyncMock:
    return AsyncMock()


@pytest.fixture
def refund_repo() -> AsyncMock:
    return AsyncMock()


def make_refund() -> Refund:
    refund = Refund.create(payment_id="payment-1", amount=5000, reason="The item arrived broken")
    refund.pull_events()
    return refund


@pytest.mark.asyncio
async def test_handle_when_the_refund_exists_then_classifies_and_saves_it(
    classifier: AsyncMock, refund_repo: AsyncMock
) -> None:
    refund = make_refund()
    refund_repo.find_refunds.return_value = ([refund], 1)
    classifier.classify.return_value = "DEFECTIVE_PRODUCT"
    handler = ClassifyRefundReasonEventHandler(classifier, refund_repo)

    await handler.handle(
        {"refund_id": refund.refund_id, "payment_id": "payment-1", "reason": "The item arrived broken"}
    )

    refund_repo.find_refunds.assert_awaited_once_with(page=0, take=1, refund_id=refund.refund_id)
    classifier.classify.assert_awaited_once_with("The item arrived broken")
    saved = refund_repo.save_refund.await_args.args[0]
    assert saved.refund_id == refund.refund_id
    assert saved.reason_category == "DEFECTIVE_PRODUCT"


@pytest.mark.asyncio
async def test_handle_when_the_refund_no_longer_exists_then_skips_classification_without_raising(
    classifier: AsyncMock, refund_repo: AsyncMock
) -> None:
    refund_repo.find_refunds.return_value = ([], 0)
    handler = ClassifyRefundReasonEventHandler(classifier, refund_repo)

    await handler.handle({"refund_id": "non-existent", "payment_id": "payment-1", "reason": "some reason"})

    classifier.classify.assert_not_awaited()
    refund_repo.save_refund.assert_not_awaited()


@pytest.mark.asyncio
async def test_handle_a_refund_that_was_ultimately_rejected_is_still_classified(
    classifier: AsyncMock, refund_repo: AsyncMock
) -> None:
    """Classification is a pure ops-analytics side channel — it must happen (and be reported
    on) identically whether the refund ends up APPROVED or REJECTED. RefundRequested is
    published unconditionally by Refund.create(), before RefundEligibilityService's
    approve/reject judgment even runs, so this handler never even looks at refund.status.
    """
    refund = make_refund()
    refund.reject("The refund amount cannot exceed the payment amount.")
    refund_repo.find_refunds.return_value = ([refund], 1)
    classifier.classify.return_value = "CHANGED_MIND"
    handler = ClassifyRefundReasonEventHandler(classifier, refund_repo)

    await handler.handle(
        {"refund_id": refund.refund_id, "payment_id": "payment-1", "reason": "The item arrived broken"}
    )

    classifier.classify.assert_awaited_once_with("The item arrived broken")
    saved = refund_repo.save_refund.await_args.args[0]
    assert saved.status.value == "REJECTED"
    assert saved.reason_category == "CHANGED_MIND"
