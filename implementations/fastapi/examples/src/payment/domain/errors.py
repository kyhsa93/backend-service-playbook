from .error_codes import PaymentErrorCode


class PaymentError(Exception):
    code: PaymentErrorCode


class PaymentNotFoundError(PaymentError):
    code = PaymentErrorCode.PAYMENT_NOT_FOUND

    def __init__(self, payment_id: str) -> None:
        super().__init__("결제를 찾을 수 없습니다.")
        self.payment_id = payment_id


class LinkedCardNotFoundError(PaymentError):
    code = PaymentErrorCode.LINKED_CARD_NOT_FOUND

    def __init__(self) -> None:
        super().__init__("연결할 카드를 찾을 수 없습니다.")


class PaymentRequiresActiveCardError(PaymentError):
    code = PaymentErrorCode.PAYMENT_REQUIRES_ACTIVE_CARD

    def __init__(self) -> None:
        super().__init__("활성 상태의 카드로만 결제할 수 있습니다.")


class LinkedAccountNotFoundError(PaymentError):
    code = PaymentErrorCode.LINKED_ACCOUNT_NOT_FOUND

    def __init__(self) -> None:
        super().__init__("연결된 계좌를 찾을 수 없습니다.")


class PaymentRequiresActiveAccountError(PaymentError):
    code = PaymentErrorCode.PAYMENT_REQUIRES_ACTIVE_ACCOUNT

    def __init__(self) -> None:
        super().__init__("활성 상태의 계좌로만 결제할 수 있습니다.")


class InsufficientBalanceError(PaymentError):
    code = PaymentErrorCode.INSUFFICIENT_BALANCE

    def __init__(self) -> None:
        super().__init__("계좌 잔액이 부족하여 결제할 수 없습니다.")


class PaymentCancelRequiresCompletedPaymentError(PaymentError):
    code = PaymentErrorCode.PAYMENT_CANCEL_REQUIRES_COMPLETED_PAYMENT

    def __init__(self) -> None:
        super().__init__("완료된 결제만 취소할 수 있습니다.")


class PaymentCompleteRequiresPendingPaymentError(PaymentError):
    code = PaymentErrorCode.PAYMENT_COMPLETE_REQUIRES_PENDING_PAYMENT

    def __init__(self) -> None:
        super().__init__("결제 대기 상태에서만 완료 처리할 수 있습니다.")


class PaymentFailRequiresPendingPaymentError(PaymentError):
    code = PaymentErrorCode.PAYMENT_FAIL_REQUIRES_PENDING_PAYMENT

    def __init__(self) -> None:
        super().__init__("결제 대기 상태에서만 실패 처리할 수 있습니다.")


class RefundApproveRequiresRequestedRefundError(PaymentError):
    code = PaymentErrorCode.REFUND_APPROVE_REQUIRES_REQUESTED_REFUND

    def __init__(self) -> None:
        super().__init__("환불 요청 상태에서만 승인할 수 있습니다.")


class RefundRejectRequiresRequestedRefundError(PaymentError):
    code = PaymentErrorCode.REFUND_REJECT_REQUIRES_REQUESTED_REFUND

    def __init__(self) -> None:
        super().__init__("환불 요청 상태에서만 거부할 수 있습니다.")


class RefundCompleteRequiresApprovedRefundError(PaymentError):
    code = PaymentErrorCode.REFUND_COMPLETE_REQUIRES_APPROVED_REFUND

    def __init__(self) -> None:
        super().__init__("승인된 환불만 완료 처리할 수 있습니다.")
