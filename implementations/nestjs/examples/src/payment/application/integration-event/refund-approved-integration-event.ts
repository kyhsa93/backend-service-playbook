// The Integration Event (a public contract) Payment BC exposes to external BCs.
// Carries only the minimal information Account needs to execute the refund credit (deposit).
export class RefundApprovedIntegrationEventV1 {
  public readonly eventName = 'refund.approved.v1' as const

  constructor(
    public readonly refundId: string,
    public readonly paymentId: string,
    public readonly accountId: string,
    public readonly amount: number,
    public readonly approvedAt: string
  ) {}
}
