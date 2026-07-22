// The Integration Event (a public contract) the Account BC exposes to external BCs.
// Kept separate from the internal Domain Event (AccountSuspended) to keep the name/schema
// stable, with an explicit version. The eventName literal is used as the Outbox row's eventType.
export class AccountSuspendedIntegrationEventV1 {
  public readonly eventName = 'account.suspended.v1' as const

  constructor(
    public readonly accountId: string,
    public readonly suspendedAt: string
  ) {}
}
