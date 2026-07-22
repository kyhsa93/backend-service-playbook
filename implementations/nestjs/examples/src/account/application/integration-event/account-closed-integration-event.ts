// The Integration Event (a public contract) the Account BC exposes to external BCs.
export class AccountClosedIntegrationEventV1 {
  public readonly eventName = 'account.closed.v1' as const

  constructor(
    public readonly accountId: string,
    public readonly closedAt: string
  ) {}
}
