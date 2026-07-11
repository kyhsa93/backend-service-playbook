// Account BC가 외부 BC에 공개하는 Integration Event (공개 계약).
// 내부 Domain Event(AccountSuspended)와 분리해 이름·스키마를 안정적으로 유지하고
// 버전을 명시한다. eventName 리터럴이 Outbox row의 eventType으로 사용된다.
export class AccountSuspendedIntegrationEventV1 {
  public readonly eventName = 'account.suspended.v1' as const

  constructor(
    public readonly accountId: string,
    public readonly suspendedAt: string
  ) {}
}
