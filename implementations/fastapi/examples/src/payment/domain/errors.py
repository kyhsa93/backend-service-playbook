from .error_codes import PaymentErrorCode


class PaymentError(Exception):
    code: PaymentErrorCode


class PaymentNotFoundError(PaymentError):
    code = PaymentErrorCode.PAYMENT_NOT_FOUND

    def __init__(self, payment_id: str) -> None:
        super().__init__("Payment not found.")
        self.payment_id = payment_id


class LinkedCardNotFoundError(PaymentError):
    code = PaymentErrorCode.LINKED_CARD_NOT_FOUND

    def __init__(self) -> None:
        super().__init__("The card to link could not be found.")


class PaymentRequiresActiveCardError(PaymentError):
    code = PaymentErrorCode.PAYMENT_REQUIRES_ACTIVE_CARD

    def __init__(self) -> None:
        super().__init__("Only an active card can be used for payment.")


class LinkedAccountNotFoundError(PaymentError):
    code = PaymentErrorCode.LINKED_ACCOUNT_NOT_FOUND

    def __init__(self) -> None:
        super().__init__("The linked account could not be found.")


class PaymentRequiresActiveAccountError(PaymentError):
    code = PaymentErrorCode.PAYMENT_REQUIRES_ACTIVE_ACCOUNT

    def __init__(self) -> None:
        super().__init__("Only an active account can be used for payment.")


class InsufficientBalanceError(PaymentError):
    code = PaymentErrorCode.INSUFFICIENT_BALANCE

    def __init__(self) -> None:
        super().__init__("Payment cannot be made due to insufficient account balance.")


class PaymentCancelRequiresCompletedPaymentError(PaymentError):
    code = PaymentErrorCode.PAYMENT_CANCEL_REQUIRES_COMPLETED_PAYMENT

    def __init__(self) -> None:
        super().__init__("Only a completed payment can be cancelled.")


class PaymentCompleteRequiresPendingPaymentError(PaymentError):
    code = PaymentErrorCode.PAYMENT_COMPLETE_REQUIRES_PENDING_PAYMENT

    def __init__(self) -> None:
        super().__init__("Only a pending payment can be marked complete.")


class PaymentFailRequiresPendingPaymentError(PaymentError):
    code = PaymentErrorCode.PAYMENT_FAIL_REQUIRES_PENDING_PAYMENT

    def __init__(self) -> None:
        super().__init__("Only a pending payment can be marked failed.")


class RefundApproveRequiresRequestedRefundError(PaymentError):
    code = PaymentErrorCode.REFUND_APPROVE_REQUIRES_REQUESTED_REFUND

    def __init__(self) -> None:
        super().__init__("Only a requested refund can be approved.")


class RefundRejectRequiresRequestedRefundError(PaymentError):
    code = PaymentErrorCode.REFUND_REJECT_REQUIRES_REQUESTED_REFUND

    def __init__(self) -> None:
        super().__init__("Only a requested refund can be rejected.")


class RefundCompleteRequiresApprovedRefundError(PaymentError):
    code = PaymentErrorCode.REFUND_COMPLETE_REQUIRES_APPROVED_REFUND

    def __init__(self) -> None:
        super().__init__("Only an approved refund can be marked complete.")
