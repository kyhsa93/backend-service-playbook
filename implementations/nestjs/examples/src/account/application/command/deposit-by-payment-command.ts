export class DepositByPaymentCommand {
  public readonly accountId: string
  public readonly amount: number
  // Payment BC's paymentId (a payment-cancellation compensating credit) or refundId (a
  // refund-approval credit). Used as the key for idempotency checks (a Level 2 Ledger).
  public readonly referenceId: string

  constructor(command: DepositByPaymentCommand) {
    Object.assign(this, command)
  }
}
