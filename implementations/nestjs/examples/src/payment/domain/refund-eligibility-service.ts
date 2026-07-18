import { Payment } from '@/payment/domain/payment'
import { PaymentStatus } from '@/payment/payment-enum'
import { PaymentErrorMessage } from '@/payment/payment-error-message'
import { Refund } from '@/payment/domain/refund'

export interface RefundDecision {
  readonly approved: boolean
  readonly reason?: string
}

// Domain Service — 프레임워크 데코레이터 없는 순수 클래스(NestJS DI 컨테이너에도
// 등록하지 않는다. Application 레이어가 필요할 때 `new`로 직접 만들어 쓴다).
//
// "원 결제가 COMPLETED 상태여야 하고, 환불 금액이 결제 금액을 넘을 수 없다"는 판단은
// Payment 혼자서도, Refund 혼자서도 내릴 수 없다. Payment는 자신에 대한 환불 시도를
// 모르고(환불은 Refund Aggregate로만 존재), Refund는 원 결제의 금액·상태를 모른다
// (paymentId로 참조만 한다). 이 판단을 내리려면 두 Aggregate를 모두 로드해 같은
// 자리에서 비교해야 하므로, 이 조율 로직은 어느 한쪽 Aggregate의 메서드로 넣을 수
// 없고(넣는다면 다른 쪽 Aggregate 전체를 파라미터로 받아야 해 경계가 무너진다) 여기
// 즉 별도의 Domain Service에 위치한다. (root docs/architecture/domain-service.md 참조)
export class RefundEligibilityService {
  public evaluate(payment: Payment, refund: Refund): RefundDecision {
    if (payment.status !== PaymentStatus.COMPLETED) {
      return { approved: false, reason: PaymentErrorMessage['완료된 결제에 대해서만 환불을 요청할 수 있습니다.'] }
    }
    if (refund.amount > payment.amount) {
      return { approved: false, reason: PaymentErrorMessage['환불 금액은 결제 금액을 초과할 수 없습니다.'] }
    }
    return { approved: true }
  }
}
