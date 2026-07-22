// The Integration Event (a public contract) Payment BC exposes to external BCs.
// Carries only the minimal information Account needs to execute the compensating credit (deposit).
export class PaymentCancelledIntegrationEventV1 {
  public readonly eventName = 'payment.cancelled.v1' as const

  constructor(
    public readonly paymentId: string,
    public readonly accountId: string,
    public readonly amount: number,
    public readonly cancelledAt: string
  ) {}
}
