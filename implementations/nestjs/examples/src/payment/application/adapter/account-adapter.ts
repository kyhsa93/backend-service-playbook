// Account BC를 동기 조회하기 위한 Adapter 인터페이스 (Anticorruption Layer).
// 결제 가능 여부(계좌 활성 여부 + 잔액 충분 여부)를 현재 요청 안에서 즉시 확인해야
// 하므로 동기 Adapter 패턴을 사용한다. 실제 차감은 이 동기 조회의 몫이 아니다 —
// payment.completed.v1 Integration Event를 Account BC가 구독해 비동기로 수행한다
// (cross-domain-communication.md의 "동기=조회, 비동기 Integration Event=상태변경" 원칙).
export abstract class AccountAdapter {
  abstract findAccount(query: {
    readonly accountId: string
    readonly ownerId: string
  }): Promise<{ accountId: string; active: boolean; balanceAmount: number; currency: string } | null>
}
