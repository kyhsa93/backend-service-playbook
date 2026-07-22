from enum import Enum


class PaymentErrorCode(str, Enum):
    """Corresponds 1:1 with every exception class in errors.py (see error-handling.md).

    The rejection reasons returned by RefundEligibilityService (a Domain Service) ("A refund
    can only be requested for a completed payment.", "The refund amount cannot exceed the
    payment amount.") are never thrown as exceptions — they are returned carried in a Refund
    with REJECTED status (a valid state transition from a domain point of view — a 200/201
    response), so there is no corresponding code for them here — this enum corresponds 1:1
    only with "exceptions that are actually thrown."
    """

    PAYMENT_NOT_FOUND = "PAYMENT_NOT_FOUND"
    LINKED_CARD_NOT_FOUND = "LINKED_CARD_NOT_FOUND"
    PAYMENT_REQUIRES_ACTIVE_CARD = "PAYMENT_REQUIRES_ACTIVE_CARD"
    LINKED_ACCOUNT_NOT_FOUND = "LINKED_ACCOUNT_NOT_FOUND"
    PAYMENT_REQUIRES_ACTIVE_ACCOUNT = "PAYMENT_REQUIRES_ACTIVE_ACCOUNT"
    INSUFFICIENT_BALANCE = "INSUFFICIENT_BALANCE"
    PAYMENT_CANCEL_REQUIRES_COMPLETED_PAYMENT = "PAYMENT_CANCEL_REQUIRES_COMPLETED_PAYMENT"
    # The 4 codes below are defensive codes not currently reachable from the REST surface —
    # they're only invoked after the Application layer has already guaranteed the correct
    # preceding state (e.g. RefundEligibilityService only passes a REQUESTED refund to
    # approve()/reject()). They pair with the guards of domain methods not yet wired to any
    # Command, such as Payment.fail()/Refund.complete() (aggregate invariant coverage —
    # kept independent of REST reachability).
    PAYMENT_COMPLETE_REQUIRES_PENDING_PAYMENT = "PAYMENT_COMPLETE_REQUIRES_PENDING_PAYMENT"
    PAYMENT_FAIL_REQUIRES_PENDING_PAYMENT = "PAYMENT_FAIL_REQUIRES_PENDING_PAYMENT"
    REFUND_APPROVE_REQUIRES_REQUESTED_REFUND = "REFUND_APPROVE_REQUIRES_REQUESTED_REFUND"
    REFUND_REJECT_REQUIRES_REQUESTED_REFUND = "REFUND_REJECT_REQUIRES_REQUESTED_REFUND"
    REFUND_COMPLETE_REQUIRES_APPROVED_REFUND = "REFUND_COMPLETE_REQUIRES_APPROVED_REFUND"
