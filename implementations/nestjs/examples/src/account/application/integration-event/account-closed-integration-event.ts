// Account BC가 외부 BC에 공개하는 Integration Event (공개 계약).
export class AccountClosedIntegrationEventV1 {
  public readonly eventName = 'account.closed.v1' as const

  constructor(
    public readonly accountId: string,
    public readonly closedAt: string
  ) {}
}
