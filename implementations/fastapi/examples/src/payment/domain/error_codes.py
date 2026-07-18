from enum import Enum


class PaymentErrorCode(str, Enum):
    """errors.py의 모든 예외 클래스와 1:1로 대응한다(error-handling.md 참고).

    RefundEligibilityService(Domain Service)가 반환하는 거부 사유("완료된 결제에 대해서만
    환불을 요청할 수 있습니다.", "환불 금액은 결제 금액을 초과할 수 없습니다.")는 예외로
    던져지지 않고 REJECTED 상태의 Refund에 담겨 반환되므로(도메인 관점에서 유효한 상태
    전이 — 200/201 응답) 여기에 대응 코드가 없다 — 이 enum은 "실제로 던져지는 예외"와만
    1:1이다.
    """

    PAYMENT_NOT_FOUND = "PAYMENT_NOT_FOUND"
    LINKED_CARD_NOT_FOUND = "LINKED_CARD_NOT_FOUND"
    PAYMENT_REQUIRES_ACTIVE_CARD = "PAYMENT_REQUIRES_ACTIVE_CARD"
    LINKED_ACCOUNT_NOT_FOUND = "LINKED_ACCOUNT_NOT_FOUND"
    PAYMENT_REQUIRES_ACTIVE_ACCOUNT = "PAYMENT_REQUIRES_ACTIVE_ACCOUNT"
    INSUFFICIENT_BALANCE = "INSUFFICIENT_BALANCE"
    PAYMENT_CANCEL_REQUIRES_COMPLETED_PAYMENT = "PAYMENT_CANCEL_REQUIRES_COMPLETED_PAYMENT"
    # 아래 4개는 현재 REST 표면에서 도달하지 않는 방어적 코드다 — Application 레이어가
    # 이미 올바른 선행 상태를 보장한 뒤에만 호출하므로(예: RefundEligibilityService가
    # REQUESTED 상태만 approve()/reject()에 넘김). Payment.fail()/Refund.complete()처럼
    # 아직 어떤 Command도 연결하지 않은 도메인 메서드의 가드와 짝을 이룬다(aggregate
    # invariant coverage — REST reachability와는 별개로 유지한다).
    PAYMENT_COMPLETE_REQUIRES_PENDING_PAYMENT = "PAYMENT_COMPLETE_REQUIRES_PENDING_PAYMENT"
    PAYMENT_FAIL_REQUIRES_PENDING_PAYMENT = "PAYMENT_FAIL_REQUIRES_PENDING_PAYMENT"
    REFUND_APPROVE_REQUIRES_REQUESTED_REFUND = "REFUND_APPROVE_REQUIRES_REQUESTED_REFUND"
    REFUND_REJECT_REQUIRES_REQUESTED_REFUND = "REFUND_REJECT_REQUIRES_REQUESTED_REFUND"
    REFUND_COMPLETE_REQUIRES_APPROVED_REFUND = "REFUND_COMPLETE_REQUIRES_APPROVED_REFUND"
