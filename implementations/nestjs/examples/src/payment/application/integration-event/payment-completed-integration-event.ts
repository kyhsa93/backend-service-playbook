// The Integration Event (a public contract) Payment BC exposes to external BCs.
// A thin contract carrying only the minimal information Account needs for the actual debit
// (withdraw) — accountId+amount — never exposing Payment's internal model like ownerId/cardId.
// paymentId is also carried as the correlation key Account BC uses for its idempotency check
// (a Level 2 Ledger: a referenceId duplicate check).
export class PaymentCompletedIntegrationEventV1 {
  public readonly eventName = 'payment.completed.v1' as const

  constructor(
    public readonly paymentId: string,
    public readonly accountId: string,
    public readonly amount: number,
    public readonly completedAt: string
  ) {}
}
