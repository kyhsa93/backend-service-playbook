// Technical Service — account/application/service/notification-service.ts와 같은 모양의
// SES 이메일 발송 추상화다. Payment BC 전용으로 별도 두는 이유는 "Technical Service는
// 도메인에 스코프한다(YAGNI)" 원칙 때문이다 — Account의 NotificationService를 그대로
// 재사용(cross-BC export)하지 않고, Payment가 필요로 하는 최소 계약을 자체적으로 갖는다
// (docs/architecture/domain-service.md 참고).
//
// sendStatement는 "발송"과 "멱등성 기록"을 한 번의 호출로 묶는다 — Account의
// NotificationServiceImpl이 SES 발송 직후 SentEmailEntity를 같은 메서드 안에서 기록하는
// 것과 동일한 패턴이다. hasSentStatement()는 그 기록을 Level 1 방식으로 조회해
// payment.send-card-statements Task의 카드×월 단위 중복 발송을 막는다
// (send-card-statements-command-handler.ts 참고).
export abstract class CardStatementNotificationService {
  abstract hasSentStatement(cardId: string, statementMonth: string): Promise<boolean>

  abstract sendStatement(params: {
    cardId: string
    accountId: string
    statementMonth: string
    paymentCount: number
    totalAmount: number
    currency: string
    recipient: string
  }): Promise<void>
}
