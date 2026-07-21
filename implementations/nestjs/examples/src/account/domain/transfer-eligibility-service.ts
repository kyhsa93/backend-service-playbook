import { Account } from '@/account/domain/account'
import { AccountStatus } from '@/account/account-enum'
import { Money } from '@/account/domain/money'
import { AccountErrorMessage as ErrorMessage } from '@/account/account-error-message'

export interface TransferDecision {
  readonly approved: boolean
  readonly reason?: string
}

// Domain Service — 프레임워크 데코레이터 없는 순수 클래스(NestJS DI 컨테이너에도
// 등록하지 않는다. Application 레이어가 필요할 때 `new`로 직접 만들어 쓴다,
// RefundEligibilityService와 동일한 이유).
//
// "출금 계좌와 입금 계좌가 서로 다르고, 둘 다 활성 상태이며, 통화가 같고, 출금 계좌
// 잔액이 충분한가"라는 판단은 어느 한쪽 Account만으로는 내릴 수 없다 — 두 Aggregate
// 인스턴스를 모두 로드해 같은 자리에서 비교해야 한다(root docs/architecture/
// domain-service.md 참조).
//
// 거부 사유는 free-text가 아니라 실제 사용자가 직접 deposit/withdraw를 호출했을 때
// 던져지는 것과 완전히 동일한 AccountErrorMessage 문자열이다 — Transfer는 Refund와
// 달리 자신만의 영속 Aggregate가 없어(거부를 REJECTED 상태로 저장할 대상이 없음)
// 거부가 곧바로 HTTP 에러(400)로 나가야 하고, 그 에러는 직접 호출과 클라이언트
// 입장에서 구분할 수 없어야 한다.
export class TransferEligibilityService {
  public evaluate(source: Account, target: Account, amount: Money): TransferDecision {
    if (source.accountId === target.accountId) {
      return { approved: false, reason: ErrorMessage['출금 계좌와 입금 계좌가 동일할 수 없습니다.'] }
    }
    if (source.status !== AccountStatus.ACTIVE) {
      return { approved: false, reason: ErrorMessage['활성 상태의 계좌만 출금할 수 있습니다.'] }
    }
    if (target.status !== AccountStatus.ACTIVE) {
      return { approved: false, reason: ErrorMessage['활성 상태의 계좌만 입금할 수 있습니다.'] }
    }
    if (source.balance.currency !== target.balance.currency) {
      return { approved: false, reason: ErrorMessage['통화가 일치하지 않습니다.'] }
    }
    if (source.balance.isLessThan(amount)) {
      return { approved: false, reason: ErrorMessage['잔액이 부족합니다.'] }
    }
    return { approved: true }
  }
}
