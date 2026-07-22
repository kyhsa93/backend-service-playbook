export class WithdrawByPaymentCommand {
  public readonly accountId: string
  public readonly amount: number
  // Payment BC's paymentId. Used as the key for idempotency checks (a Level 2 Ledger).
  public readonly referenceId: string

  constructor(command: WithdrawByPaymentCommand) {
    Object.assign(this, command)
  }
}
