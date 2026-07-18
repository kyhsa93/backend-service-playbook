// Payment BC가 외부 BC에 공개하는 Integration Event (공개 계약).
// Account가 보상 크레딧(deposit)을 실행하는 데 필요한 최소 정보만 싣는다.
export class PaymentCancelledIntegrationEventV1 {
  public readonly eventName = 'payment.cancelled.v1' as const

  constructor(
    public readonly paymentId: string,
    public readonly accountId: string,
    public readonly amount: number,
    public readonly cancelledAt: string
  ) {}
}
